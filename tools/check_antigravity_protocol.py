#!/usr/bin/env python3
"""Deterministic ACP adapter tests. No Google account, network model or phone."""
import asyncio,contextlib,importlib.util,io,json,os,sys,tempfile,unittest,uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
spec=importlib.util.spec_from_file_location('mobile_acp',ROOT/'app/src/main/assets/antigravity_bridge.py');api=importlib.util.module_from_spec(spec);spec.loader.exec_module(api)
class FakePeer:
 def __init__(self,owner,command):self.owner=owner;self.calls=[];self.wait=asyncio.Event();self.approval=None;self.prompt_timeout='unused'
 async def start(self):pass
 async def close(self):self.closed=True
 async def call(self,method,p,timeout=60):
  self.calls.append((method,p))
  if method=='initialize':return {'protocolVersion':1,'agentCapabilities':{'promptCapabilities':{'image':True},'sessionCapabilities':{'load':{},'list':{}},'auth':{'logout':{}}}}
  if method=='session/new':return {'sessionId':str(uuid.uuid4()),'configOptions':[{'id':'model','category':'model','type':'select','currentValue':'model-a','options':[{'name':'Models','options':[{'value':'model-a','name':'A'},{'value':'model-b','name':'B'}]}]},{'id':'thinking','category':'thought_level','type':'select','currentValue':'low','options':[{'value':'low','name':'Low'},{'value':'high','name':'High'}]}]}
  if method=='session/load':return (await self.call('session/new',{}))|{'sessionId':p['sessionId']}
  if method=='session/list':return getattr(self,'session_pages',{}).get(p.get('cursor'),{'sessions':[]})
  if method=='session/set_config_option':
   options=self.owner.sessions[p['sessionId']]['configOptions']
   for x in options:
    if x['id']==p['configId']:x['currentValue']=p['value']
   return {'configOptions':options}
  if method=='session/prompt':
   self.prompt_timeout=timeout
   sid=p['sessionId'];await self.owner.native({'method':'session/update','params':{'sessionId':sid,'update':{'sessionUpdate':'agent_message_chunk','content':{'type':'text','text':'Hello 喵'}}}})
   await self.wait.wait();return {'stopReason':'cancelled' if getattr(self,'cancelled',False) else 'end_turn'}
  if method=='logout':return {}
  raise api.RpcFailure(-32601,'unsupported')
 async def send(self,message):
  self.calls.append(('send',message))
  if message.get('method')=='session/cancel' and getattr(self,'fail_cancel',False):raise BrokenPipeError('Test pipe closed')
  if message.get('method')=='session/cancel' and getattr(self,'block_cancel',False):await asyncio.Event().wait()
  if message.get('method')=='session/cancel' and not getattr(self,'ignore_cancel',False):self.cancelled=True;self.wait.set()
  if 'result' in message and self.approval and not self.approval.done():self.approval.set_result(message['result'])
NativePeer=api.Peer
api.Peer=FakePeer
class AdapterTests(unittest.IsolatedAsyncioTestCase):
 async def asyncSetUp(self):
  self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name);self.events=[]
  self.bridge=api.Bridge([sys.executable,str(self.root/'login.py')],str(self.root/'sessions.json'));self.bridge.emit=lambda m,p:self.events.append((m,p));await self.bridge.start()
 async def asyncTearDown(self):
  if self.bridge.login:self.bridge.login.cancel();await asyncio.gather(self.bridge.login,return_exceptions=True)
  for task in list(self.bridge.tasks):task.cancel()
  await asyncio.gather(*list(self.bridge.tasks),return_exceptions=True)
  self.bridge.server.close();await self.bridge.server.wait_closed();self.temp.cleanup()
 async def new(self):
  result=await self.bridge.handle('thread/start',{'cwd':str(self.root),'dynamicTools':[{'name':'phone_observe','description':'Observe','inputSchema':{'type':'object'}}]});return result['thread']['id']
 async def http(self,path,value):
  reader,writer=await asyncio.open_connection('127.0.0.1',self.bridge.port);data=json.dumps(value).encode();writer.write(f'POST /{self.bridge.token}/{path} HTTP/1.1\r\nContent-Length: {len(data)}\r\n\r\n'.encode()+data);await writer.drain();head=await reader.readuntil(b'\r\n\r\n');body=await reader.read();writer.close();await writer.wait_closed();return head,json.loads(body)
 async def test_catalog_model_and_thinking_are_discovered_and_applied(self):
  await self.bridge.handle('account/read',{});catalog=await self.bridge.handle('model/list',{});self.assertEqual(['model-a','model-b'],[x['id'] for x in catalog['data']])
  sid=await self.new();await self.bridge.configure(sid,'model','model-b');await self.bridge.configure(sid,'thought','high');self.assertEqual('model-b',self.bridge.selected(sid,'model'))
  with self.assertRaises(api.RpcFailure):await self.bridge.configure(sid,'model','invented')
 async def test_stream_cancel_and_history_survive_restart(self):
  sid=await self.new();await self.bridge.handle('turn/start',{'threadId':sid,'input':[{'type':'text','text':'hi'}]});await asyncio.sleep(.02)
  self.assertTrue(any(m=='item/agentMessage/delta' and p['delta']=='Hello 喵' for m,p in self.events));await self.bridge.handle('turn/interrupt',{'threadId':sid});await asyncio.sleep(.02)
  self.assertIsNone(self.bridge.peer.prompt_timeout)
  self.assertTrue(any(m=='turn/completed' and p['turn']['status']=='interrupted' for m,p in self.events));other=api.Bridge([],str(self.root/'sessions.json'));self.assertEqual(2,len(other.threads[sid]['items']))
  await self.bridge.handle('thread/archive',{'threadId':sid});self.assertEqual(1,len((await self.bridge.handle('thread/list',{'archived':True}))['data']))
 async def test_mobile_tools_require_private_token_and_active_turn(self):
  sid=await self.new();token=next(t for t,s in self.bridge.tool_tokens.items() if s==sid);_,result=await self.http(token,{'method':'list'});self.assertEqual('phone_observe',result['tools'][0]['name'])
  head,_=await self.http(token,{'method':'call','name':'phone_observe','arguments':{}});self.assertIn(b'400',head)
  await self.bridge.handle('turn/start',{'threadId':sid,'input':[{'type':'text','text':'observe'}]});captured=io.StringIO()
  with contextlib.redirect_stdout(captured):
   call=asyncio.create_task(self.http(token,{'method':'call','name':'phone_observe','arguments':{}}));await asyncio.sleep(.03)
   message=json.loads(captured.getvalue());self.assertEqual(sid,message['params']['threadId']);self.bridge.answer(message['id'],{'success':True,'contentItems':[{'type':'inputText','text':'screen facts'}]});_,result=await call
  self.assertTrue(result['success']);self.bridge.peer.wait.set();await asyncio.sleep(.01)
 async def test_approvals_never_upgrade_once_to_always(self):
  sid=await self.new();self.bridge.active[sid]='turn';self.bridge.peer.approval=asyncio.get_running_loop().create_future();out=io.StringIO()
  with contextlib.redirect_stdout(out):
   task=asyncio.create_task(self.bridge.native({'id':22,'method':'session/request_permission','params':{'sessionId':sid,'toolCall':{'title':'Edit file'},'options':[{'kind':'allow_always','optionId':'always'},{'kind':'allow_once','optionId':'once'},{'kind':'reject_once','optionId':'deny'}]}}));await asyncio.sleep(.01);message=json.loads(out.getvalue());self.bridge.answer(message['id'],{'decision':'accept'});await task
  self.assertEqual('once',self.bridge.peer.approval.result()['outcome']['optionId'])
 async def test_workspace_image_boundary_and_unknown_methods_fail(self):
  sid=await self.new();await self.bridge.handle('turn/start',{'threadId':sid,'input':[{'type':'localImage','path':'/outside/private.png'}]});await asyncio.sleep(.03)
  self.assertTrue(any(m=='turn/completed' and p['turn']['status']=='failed' for m,p in self.events))
  with self.assertRaises(api.RpcFailure):await self.bridge.handle('unsupported/method',{})
 async def test_large_diff_is_complete_and_can_receive_once_only_approval(self):
  sid=await self.new();self.bridge.active[sid]='turn';self.bridge.peer.approval=asyncio.get_running_loop().create_future();out=io.StringIO();text='first\n'+'x'*90000+'\nlast'
  with contextlib.redirect_stdout(out):
   task=asyncio.create_task(self.bridge.native({'id':23,'method':'session/request_permission','params':{'sessionId':sid,'toolCall':{'title':'Edit','content':[{'type':'diff','path':'large.txt','newText':text}]},'options':[{'kind':'allow_once','optionId':'once'}]}}));await asyncio.sleep(.01);message=json.loads(out.getvalue())
   self.assertTrue(message['params']['details'].endswith(text));self.assertFalse(message['params'].get('truncated',False));self.bridge.answer(message['id'],{'decision':'accept'});await task
  self.assertEqual('once',self.bridge.peer.approval.result()['outcome']['optionId'])
 async def test_google_terminal_login_and_cancel_no_auth_material_persisted(self):
  (self.root/'login.py').write_text("import sys\nprint('Open https://accounts.google.com/o/oauth2/auth?state=TESTONLY',flush=True)\ncode=input()\nsys.exit(0 if code=='test-code' else 1)\n")
  result=await self.bridge.handle('account/login/start',{});self.assertTrue(result['requiresCode']);await self.bridge.handle('account/login/submitCode',{'code':'test-code'});task=self.bridge.login;await task
  self.assertTrue(any(m=='account/login/completed' and p['success'] for m,p in self.events));self.assertFalse(self.bridge.storage.exists())
  await self.bridge.handle('account/login/start',{});await self.bridge.handle('account/login/cancel',{});self.assertIsNone(self.bridge.login)
 async def test_native_session_pagination_reaches_later_chats_and_rejects_loops(self):
  self.bridge.peer.session_pages={None:{'sessions':[{'sessionId':'first','cwd':str(self.root)}],'nextCursor':'next'},'next':{'sessions':[{'sessionId':'last','cwd':str(self.root)}]}}
  values=(await self.bridge.handle('thread/list',{}))['data'];self.assertEqual({'first','last'},{x['id'] for x in values})
  self.bridge.peer.session_pages['next']['nextCursor']='next'
  with self.assertRaises(api.RpcFailure):await self.bridge.handle('thread/list',{})
 async def test_explicit_stop_cannot_hang_forever_when_agent_ignores_cancel(self):
  sid=await self.new();self.bridge.peer.ignore_cancel=True
  await self.bridge.handle('turn/start',{'threadId':sid,'input':[{'type':'text','text':'work'}]});await asyncio.sleep(.01)
  previous=api.STOP_GRACE;api.STOP_GRACE=.01
  try:
   await self.bridge.handle('turn/interrupt',{'threadId':sid});await asyncio.sleep(.05)
   self.assertTrue(self.bridge.failed);self.assertTrue(self.bridge.peer.closed);self.assertNotIn(sid,self.bridge.active)
   ended=[p for m,p in self.events if m=='turn/completed'];self.assertEqual('failed',ended[-1]['turn']['status'])
   with self.assertRaises(api.RpcFailure):await self.bridge.handle('turn/start',{'threadId':sid,'input':[]})
   count=len(self.events);await self.bridge.native({'method':'session/update','params':{'sessionId':sid,'update':{'sessionUpdate':'agent_message_chunk','content':{'type':'text','text':'late output'}}}});self.assertEqual(count,len(self.events))
  finally:api.STOP_GRACE=previous
 async def test_owned_native_process_ignoring_sigterm_is_killed_on_close(self):
  ready=asyncio.Event()
  class Owner:
   def spawn(self,task):return asyncio.create_task(task)
   async def native(self,message):ready.set()
  script="import signal,time,json;signal.signal(signal.SIGTERM,signal.SIG_IGN);print(json.dumps({'method':'ready'}),flush=True);time.sleep(60)"
  peer=NativePeer(Owner(),[sys.executable,'-c',script]);await peer.start()
  try:
   await asyncio.wait_for(ready.wait(),2);await asyncio.wait_for(peer.close(),4)
   self.assertIsNotNone(peer.process.returncode)
  finally:
   if peer.process.returncode is None:peer.process.kill();await peer.process.wait()
 async def test_stop_delivery_itself_cannot_block_the_watchdog(self):
  sid=await self.new();self.bridge.peer.block_cancel=True
  await self.bridge.handle('turn/start',{'threadId':sid,'input':[{'type':'text','text':'work'}]});await asyncio.sleep(.01)
  previous=api.STOP_GRACE;api.STOP_GRACE=.01
  try:
   await asyncio.wait_for(self.bridge.handle('turn/interrupt',{'threadId':sid}),.1);await asyncio.sleep(.05)
   self.assertTrue(self.bridge.failed);self.assertTrue(self.bridge.peer.closed);self.assertNotIn(sid,self.bridge.active)
  finally:api.STOP_GRACE=previous
 async def test_stale_stop_does_not_cancel_a_newer_turn(self):
  sid=await self.new();self.bridge.active[sid]='new-turn'
  with self.assertRaises(api.RpcFailure):await self.bridge.handle('turn/interrupt',{'threadId':sid,'turnId':'old-turn'})
  self.assertFalse(any(m=='send' and p.get('method')=='session/cancel' for m,p in self.bridge.peer.calls))
 async def test_failed_stop_delivery_and_full_disk_still_close_the_owned_engine(self):
  sid=await self.new();await self.bridge.handle('turn/start',{'threadId':sid,'input':[{'type':'text','text':'work'}]});await asyncio.sleep(.01)
  self.bridge.peer.fail_cancel=True
  def full_disk():raise OSError('Test disk full')
  self.bridge.save=full_disk
  await self.bridge.handle('turn/interrupt',{'threadId':sid});pending=list(self.bridge.tasks)
  await asyncio.wait_for(asyncio.gather(*pending,return_exceptions=True),.2)
  self.assertTrue(self.bridge.failed);self.assertTrue(self.bridge.peer.closed);self.assertNotIn(sid,self.bridge.active)

if __name__=='__main__':unittest.main(verbosity=2)
