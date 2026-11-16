#!/usr/bin/python3
"""ACP v1 backend, mapped onto the app's existing internal conversation wire.

Only this adapter knows Antigravity protocol details. No model credentials cross
its private tool bridge, and Google authentication is handled by the official CLI.
"""
import asyncio,base64,datetime,json,os,secrets,sys,time,uuid,re,pty,fcntl,termios,struct,signal
from pathlib import Path

LIMIT=128*1024*1024
STOP_GRACE=5
class RpcFailure(Exception):
 def __init__(self,code,message):super().__init__(message);self.code=code

def stamp(value):
 if isinstance(value,(int,float)):return int(value)
 try:return int(datetime.datetime.fromisoformat(value.replace('Z','+00:00')).timestamp())
 except (ValueError,AttributeError):return int(time.time())
def wire(value):return json.dumps(value,ensure_ascii=False,separators=(',',':'))
def text_content(content):
 return '\n'.join(x.get('text','') for x in content if x.get('type')=='text')
def choices(options):
 for value in options:
  if 'options' in value:yield from choices(value['options'])
  else:yield value

class Peer:
 def __init__(self,owner,command):self.owner=owner;self.command=command;self.pending={};self.counter=0;self.process=None
 async def start(self):
  self.process=await asyncio.create_subprocess_exec(*self.command,stdin=asyncio.subprocess.PIPE,stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.PIPE,limit=LIMIT,start_new_session=True)
  self.reader=asyncio.create_task(self.read());self.errors=asyncio.create_task(self.stderr())
 async def stderr(self):
  # Auth URLs and profile details must not enter the host's normal logs.
  while await self.process.stderr.readline():pass
 async def send(self,value):
  self.process.stdin.write((wire({'jsonrpc':'2.0',**value})+'\n').encode());await self.process.stdin.drain()
 async def call(self,method,params,timeout=60):
  self.counter+=1;identifier=self.counter;future=asyncio.get_running_loop().create_future();self.pending[identifier]=future
  try:
   await self.send({'id':identifier,'method':method,'params':params});return await asyncio.wait_for(future,timeout)
  finally:self.pending.pop(identifier,None)
 async def read(self):
  try:
   while line:=await self.process.stdout.readline():
    message=json.loads(line)
    if 'method' in message:self.owner.spawn(self.owner.native(message))
    elif (future:=self.pending.get(message.get('id'))) is not None and not future.done():
     if 'error' in message:future.set_exception(RpcFailure(message['error'].get('code',-32603),message['error'].get('message','Agent request failed')))
     else:future.set_result(message.get('result',{}))
  finally:
   for future in list(self.pending.values()):
    if not future.done():future.set_exception(RpcFailure(-32001,'Antigravity process disconnected'))
 async def close(self):
  if self.process:
   try:os.killpg(self.process.pid,signal.SIGTERM)
   except ProcessLookupError:pass
   try:await asyncio.wait_for(self.process.wait(),2)
   except asyncio.TimeoutError:
    try:os.killpg(self.process.pid,signal.SIGKILL)
    except ProcessLookupError:pass
    await self.process.wait()
   finally:
    # The parent may have exited before its MCP children.
    try:os.killpg(self.process.pid,signal.SIGKILL)
    except ProcessLookupError:pass
  tasks=[x for x in (getattr(self,'reader',None),getattr(self,'errors',None)) if x]
  for task in tasks:task.cancel()
  await asyncio.gather(*tasks,return_exceptions=True)

class Bridge:
 def __init__(self,command,storage):
  self.command=command;self.storage=Path(storage);self.peer=Peer(self,command);self.native_info={};self.catalog={};self.authenticated=False
  self.threads={};self.sessions={};self.active={};self.open_items={};self.permissions={};self.external={};self.external_owners={};self.loading=set();self.login=None
  self.hidden=set();self.counter=0;self.token=secrets.token_urlsafe(32);self.tool_tokens={};self.login_url=None;self.tasks=set();self.turn_tasks={};self.stopping=set();self.failed=False;self.running_task=None
  if self.storage.exists():
   try:self.threads=json.loads(self.storage.read_text())
   except (ValueError,OSError):pass
  if self.storage.with_suffix('.catalog.json').exists():
   try:self.hidden=set(json.loads(self.storage.with_suffix('.catalog.json').read_text()))
   except (ValueError,OSError):pass
 def emit(self,method,params):print(wire({'method':method,'params':params}),flush=True)
 def save(self):
  self.storage.parent.mkdir(parents=True,exist_ok=True);temporary=self.storage.with_suffix('.tmp');temporary.write_text(wire(self.threads));os.chmod(temporary,0o600);temporary.replace(self.storage)
 async def initialize(self):
  await self.peer.start()
  self.native_info=await self.peer.call('initialize',{'protocolVersion':1,'clientInfo':{'name':'codex-go','title':'Codex Go','version':'0.21.0'},'clientCapabilities':{'fs':{'readTextFile':False,'writeTextFile':False},'terminal':False}})
  if self.native_info.get('protocolVersion')!=1:raise RpcFailure(-32602,'Unsupported ACP protocol version')
 async def start(self):
  self.server=await asyncio.start_server(self.http,'127.0.0.1',0,limit=1024*1024)
  self.port=self.server.sockets[0].getsockname()[1]
  await self.initialize()
 async def probe(self):
  try:
   if not self.catalog:
    value=await self.peer.call('session/new',{'cwd':os.getcwd(),'mcpServers':[]});self.catalog=value;self.hidden.add(value['sessionId']);self.storage.parent.mkdir(parents=True,exist_ok=True);self.storage.with_suffix('.catalog.json').write_text(wire(sorted(self.hidden)))
    if 'close' in self.native_info.get('agentCapabilities',{}).get('sessionCapabilities',{}):
     # Keep the catalog session for session config discovery. No prompts run here.
     pass
   self.authenticated=True
  except RpcFailure as error:
   if error.code not in (-32000,-32001):raise
   self.authenticated=False
  return self.authenticated
 def options(self,value):return value.get('configOptions',[])
 def option(self,value,category):
  for option in self.options(value):
   marker=(option.get('category','')+' '+option.get('id','')).lower()
   if category=='model' and option.get('category',option.get('id'))=='model':return option
   if category=='thought' and any(x in marker for x in ('thought','thinking','reasoning','effort')):return option
  return None
 def model_list(self):
  model=self.option(self.catalog,'model');effort=self.option(self.catalog,'thought')
  if not model:return []
  efforts=[{'reasoningEffort':x['value'],'description':x.get('name',x['value'])} for x in choices(effort.get('options',[]))] if effort else []
  return [{'id':x['value'],'model':x['value'],'displayName':x.get('name',x['value']),'description':x.get('description',''),'isDefault':x['value']==model.get('currentValue'),'supportedReasoningEfforts':efforts,'defaultReasoningEffort':effort.get('currentValue') if effort else None,'inputModalities':['text','image'] if self.native_info.get('agentCapabilities',{}).get('promptCapabilities',{}).get('image') else ['text']} for x in choices(model.get('options',[]))]
 async def configure(self,sid,category,selected):
  if not selected:return
  config=self.sessions.get(sid,self.catalog);option=self.option(config,category)
  if not option:raise RpcFailure(-32602,f'Agent does not expose {category} selection')
  if selected not in {x.get('value') for x in choices(option.get('options',[]))}:raise RpcFailure(-32602,'Selected model or thinking level is unavailable')
  if selected==option.get('currentValue'):return
  result=await self.peer.call('session/set_config_option',{'sessionId':sid,'configId':option['id'],'value':selected})
  if result.get('configOptions') is not None:config['configOptions']=result['configOptions']
  else:option['currentValue']=selected
  self.sessions[sid]=config
 def thread(self,sid):
  value=self.threads[sid]
  return {'id':sid,'cwd':value['cwd'],'name':value.get('name'),'preview':value.get('preview',''),'updatedAt':value.get('updatedAt',0),'turns':[]}
 def mcp(self,sid):
  for old,session in list(self.tool_tokens.items()):
   if session==sid:self.tool_tokens.pop(old,None)
  token=secrets.token_urlsafe(32);self.tool_tokens[token]=sid
  return [{'name':'codex-go-mobile','command':sys.executable,'args':[str(Path(__file__).with_name('agent_mcp.py')),f'http://127.0.0.1:{self.port}/{self.token}/{token}'],'env':[]}]
 async def load(self,sid,tools=None):
  if sid not in self.threads:raise RpcFailure(-32602,'Session is not known to this client')
  entry=self.threads[sid]
  if tools is not None:entry['tools']=tools
  old_items=entry.get('items',[]);entry['items']=[];self.loading.add(sid);self.open_items.pop(sid,None)
  try:
   result=await self.peer.call('session/load',{'sessionId':sid,'cwd':entry['cwd'],'mcpServers':self.mcp(sid)})
   self.sessions[sid]=result
  finally:
   self.loading.discard(sid);self.open_items.pop(sid,None)
   if not entry['items']:entry['items']=old_items
  self.save();return {'thread':self.thread(sid),'model':self.selected(sid,'model'),'reasoningEffort':self.selected(sid,'thought')}
 def selected(self,sid,category):
  option=self.option(self.sessions.get(sid,{}),category);return option.get('currentValue') if option else None
 async def handle(self,method,p):
  if self.failed:raise RpcFailure(-32001,'Agent did not confirm cancellation. Restart the engine before continuing.')
  if method=='initialize':return {'agentInfo':self.native_info.get('agentInfo',{})}
  if method=='initialized':return {}
  if method=='config/read':return {'config':{}}
  if method=='account/read':
   if p.get('refreshToken'):self.catalog={}
   okay=await self.probe();return {'account':{'type':'antigravity','email':'Google account'} if okay else None}
  if method=='model/list':return {'data':self.model_list(),'nextCursor':None}
  if method=='account/login/start':
   if self.login:raise RpcFailure(-32602,'Sign-in already pending')
   identifier=str(uuid.uuid4());self.login_url=asyncio.get_running_loop().create_future()
   self.login=self.spawn(self.terminal_login(identifier))
   try:url=await asyncio.wait_for(asyncio.shield(self.login_url),45)
   except BaseException:
    if self.login:self.login.cancel();await asyncio.gather(self.login,return_exceptions=True)
    raise
   return {'loginId':identifier,'userCode':'','verificationUrl':url,'requiresCode':True}
  if method=='account/login/submitCode':
   value=p.get('code','').strip()
   if not value or len(value)>8192 or not self.login or not hasattr(self,'auth_master'):raise RpcFailure(-32602,'No pending Google login')
   os.write(self.auth_master,(value+'\n').encode());return {}
  if method=='account/login/cancel':
   if self.login:self.login.cancel();await asyncio.gather(self.login,return_exceptions=True);self.login=None
   return {}
  if method=='account/logout':
   if 'logout' not in self.native_info.get('agentCapabilities',{}).get('auth',{}):raise RpcFailure(-32601,'Agent does not support logout')
   await self.peer.call('logout',{});self.authenticated=False;self.catalog={};return {}
  if method=='thread/start':
   info=await self.peer.call('session/new',{'cwd':p.get('cwd',os.getcwd()),'mcpServers':[]});sid=info['sessionId'];self.sessions[sid]=info
   self.threads[sid]={'cwd':p.get('cwd',os.getcwd()),'name':None,'preview':'','updatedAt':int(time.time()),'items':[],'tools':p.get('dynamicTools',[]),'guidance':p.get('developerInstructions',''),'archived':False}
   # Reopen once with the session-scoped mobile MCP endpoint.
   self.sessions[sid]=await self.peer.call('session/load',{'sessionId':sid,'cwd':self.threads[sid]['cwd'],'mcpServers':self.mcp(sid)})
   await self.configure(sid,'model',p.get('model'));self.save();return {'thread':self.thread(sid),'model':self.selected(sid,'model')}
  if method in ('thread/resume','thread/read'):
   sid=p['threadId']
   if method=='thread/resume':return await self.load(sid,p.get('dynamicTools'))
   result={'thread':self.thread(sid)}
   if p.get('includeTurns'):result['thread']['turns']=[{'id':'history','items':self.threads[sid].get('items',[])}]
   return result
  if method=='thread/items/list':
   items=self.threads[p['threadId']].get('items',[]);offset=int(p.get('cursor') or 0);limit=min(100,p.get('limit',100));chunk=items[offset:offset+limit]
   return {'data':[{'item':x} for x in chunk],'nextCursor':str(offset+limit) if offset+limit<len(items) else None}
  if method=='thread/list':
   if not p.get('cursor'):
    try:
     cursor=None;seen=set()
     while True:
      query={'cwd':p.get('cwd')}
      if cursor is not None:query['cursor']=cursor
      raw=await self.peer.call('session/list',query)
      for item in raw.get('sessions',[]):
       sid=item['sessionId']
       if sid in self.hidden:continue
       if sid not in self.threads:self.threads[sid]={'cwd':item.get('cwd',p.get('cwd',os.getcwd())),'name':item.get('title'),'preview':'','updatedAt':stamp(item.get('updatedAt')),'items':[],'tools':[],'archived':False}
      cursor=raw.get('nextCursor')
      if cursor is None:break
      if cursor in seen:raise RpcFailure(-32603,'Session list returned a repeated cursor')
      seen.add(cursor)
    except RpcFailure as error:
     if error.code not in (-32000,-32601):raise
   values=[self.thread(sid) for sid,value in self.threads.items() if value.get('archived',False)==p.get('archived',False) and (not p.get('cwd') or value['cwd']==p['cwd'])];values.sort(key=lambda x:x['updatedAt'],reverse=True);offset=int(p.get('cursor') or 0);limit=min(p.get('limit',50),100)
   return {'data':values[offset:offset+limit],'nextCursor':str(offset+limit) if offset+limit<len(values) else None}
  if method in ('thread/name/set','thread/archive','thread/unarchive'):
   entry=self.threads[p['threadId']]
   if method=='thread/name/set':entry['name']=p['name']
   else:entry['archived']=method=='thread/archive'
   self.save();return {}
  if method=='turn/start':
   sid=p['threadId']
   if sid in self.active:raise RpcFailure(-32602,'A turn is already active')
   await self.configure(sid,'model',p.get('model'));await self.configure(sid,'thought',p.get('effort'))
   turn=str(uuid.uuid4());self.active[sid]=turn;self.emit('turn/started',{'threadId':sid,'turn':{'id':turn}})
   task=self.spawn(self.prompt(sid,turn,p.get('input',[])));self.turn_tasks[sid]=task
   task.add_done_callback(lambda done:self.turn_tasks.pop(sid,None) if self.turn_tasks.get(sid) is done else None)
   return {'turn':{'id':turn}}
  if method=='turn/interrupt':
   sid=p['threadId'];turn=self.active.get(sid)
   if turn and p.get('turnId') not in (None,turn):raise RpcFailure(-32602,'Cancellation belongs to an older turn')
   if turn and (sid,turn) not in self.stopping:self.stopping.add((sid,turn));self.spawn(self.confirm_stop(sid,turn,self.turn_tasks.get(sid)))
   return {}
  raise RpcFailure(-32601,'This operation is not supported by Antigravity ACP')
 def spawn(self,awaitable):
  task=asyncio.create_task(awaitable);self.tasks.add(task);task.add_done_callback(self.tasks.discard);return task
 async def confirm_stop(self,sid,turn,task):
  try:
   if self.active.get(sid)!=turn:return
   async def request_and_wait():
    await self.peer.send({'method':'session/cancel','params':{'sessionId':sid}})
    if task:await asyncio.shield(task)
   try:await asyncio.wait_for(request_and_wait(),STOP_GRACE)
   except Exception:pass
   if self.active.get(sid)!=turn:return
   # This deadline only applies AFTER an explicit stop. Never assume that a
   # silent backend has stopped, or start the next queued task against it.
   self.failed=True
   try:
    for session,active in list(self.active.items()):await self.complete(session,active,'error','Agent did not confirm cancellation; the engine was stopped. Review any side effects before retrying.')
   finally:
    try:await self.peer.close()
    finally:
     if task and not task.done():task.cancel()
     if self.running_task:self.running_task.cancel()
  finally:self.stopping.discard((sid,turn))
 async def terminal_login(self,identifier):
  master,slave=pty.openpty();self.auth_master=master
  fcntl.ioctl(slave,termios.TIOCSWINSZ,struct.pack('HHHH',40,4096,0,0))
  environment=dict(os.environ);environment.pop('DISPLAY',None);environment.pop('BROWSER',None)
  environment['SSH_CONNECTION']='127.0.0.1 1 127.0.0.1 2';environment['TERM']='xterm-256color'
  process=None
  try:
   process=await asyncio.create_subprocess_exec(*self.command,'--login',stdin=slave,stdout=slave,stderr=slave,env=environment,start_new_session=True);os.close(slave);slave=None
   loop=asyncio.get_running_loop();buffer=''
   while process.returncode is None:
    future=loop.create_future()
    def ready():
     if not future.done():future.set_result(None)
    loop.add_reader(master,ready)
    try:await asyncio.wait_for(future,600)
    finally:loop.remove_reader(master)
    try:data=os.read(master,65536)
    except OSError:break
    if not data:break
    buffer=(buffer+re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]','',data.decode(errors='replace')))[-32768:]
    if self.login_url and not self.login_url.done():
     from urllib.parse import urlparse
     for url in re.findall(r'https://[^\s<>]+',buffer):
      if urlparse(url).hostname=='accounts.google.com':self.login_url.set_result(url);break
   code=await process.wait()
   if code!=0:raise RpcFailure(-32000,'Google sign-in did not complete; retry sign-in')
   await self.peer.close();self.peer=Peer(self,self.command);self.catalog={};await self.initialize();self.authenticated=True
   self.emit('account/login/completed',{'loginId':identifier,'success':True})
  except asyncio.CancelledError:raise
  except Exception as error:
   if self.login_url and not self.login_url.done():self.login_url.set_exception(error)
   self.emit('account/login/completed',{'loginId':identifier,'success':False,'error':'Google sign-in did not complete'})
  finally:
   if process and process.returncode is None:
    try:os.killpg(process.pid,signal.SIGTERM)
    except ProcessLookupError:pass
    await process.wait()
   if slave is not None:os.close(slave)
   os.close(master);del self.auth_master;self.login=None
 async def prompt(self,sid,turn,source):
  entry=self.threads[sid];content=[];history=[]
  try:
   if entry.get('guidance') and not entry.get('items'):content.append({'type':'text','text':'Mobile client context:\n'+entry['guidance']})
   for item in source:
    if item.get('type')=='text':content.append({'type':'text','text':item['text']});history.append({'type':'text','text':item['text']})
    elif item.get('type')=='localImage':
     if not self.native_info.get('agentCapabilities',{}).get('promptCapabilities',{}).get('image'):raise RpcFailure(-32602,'Agent does not support images')
     path=Path(item['path']).resolve();root=Path(entry['cwd']).resolve()
     if root not in path.parents:raise RpcFailure(-32602,'Image is outside this workspace')
     data=path.read_bytes();mime='image/png' if data.startswith(b'\x89PNG') else 'image/jpeg' if data.startswith(b'\xff\xd8') else None
     if not mime:raise RpcFailure(-32602,'Unsupported image format')
     content.append({'type':'image','mimeType':mime,'data':base64.b64encode(data).decode()});history.append({'type':'localImage','path':str(path)})
   entry['items'].append({'id':str(uuid.uuid4()),'type':'userMessage','content':history});entry['preview']=text_content(history)[:160];entry['updatedAt']=int(time.time());self.save()
   result=await self.peer.call('session/prompt',{'sessionId':sid,'prompt':content},None)
   await self.complete(sid,turn,result.get('stopReason','end_turn'))
  except Exception as error:await self.complete(sid,turn,'error',str(error)[:2000])
 async def complete(self,sid,turn,reason,error=None):
  if self.active.get(sid)!=turn:return
  item=self.open_items.pop(sid,None)
  if item:self.emit('item/completed',{'threadId':sid,'turnId':turn,'item':item})
  self.active.pop(sid,None)
  for key,session in list(self.external_owners.items()):
   if session==sid:self.answer(key,{'success':False,'contentItems':[{'type':'inputText','text':'Turn ended or cancelled'}]})
  for key,(session,_) in list(self.permissions.items()):
   if session==sid:self.answer(key,{'decision':'decline'})
  self.save();value={'id':turn,'status':'interrupted' if reason=='cancelled' else 'failed' if error else 'completed'}
  if error:value['error']={'message':error}
  self.emit('turn/completed',{'threadId':sid,'turn':value})
 def append_chunk(self,sid,kind,text):
  entry=self.threads[sid];item=self.open_items.get(sid)
  if not item or item.get('type')!=kind:
   if item and sid not in self.loading:self.emit('item/completed',{'threadId':sid,'turnId':self.active.get(sid),'item':item})
   item={'id':str(uuid.uuid4()),'type':kind,'text':''} if kind!='userMessage' else {'id':str(uuid.uuid4()),'type':kind,'content':[{'type':'text','text':''}]}
   entry.setdefault('items',[]).append(item);self.open_items[sid]=item
   if sid not in self.loading:self.emit('item/started',{'threadId':sid,'turnId':self.active.get(sid),'item':dict(item)})
  if kind=='userMessage':item['content'][0]['text']+=text
  else:item['text']+=text
  if sid not in self.loading and kind=='agentMessage':self.emit('item/agentMessage/delta',{'threadId':sid,'turnId':self.active.get(sid),'itemId':item['id'],'delta':text})
 async def native(self,message):
  if self.failed:return
  method=message['method'];p=message.get('params',{});sid=p.get('sessionId')
  if 'id' in message:
   if method=='session/request_permission' and sid in self.active:
    self.counter+=1;key='agy-permission:'+str(self.counter);future=asyncio.get_running_loop().create_future();self.permissions[key]=(sid,future)
    call=p.get('toolCall',{});detail=[]
    for value in call.get('content',[]):
     if value.get('type')=='content':detail.append(value.get('content',{}).get('text',''))
     if value.get('type')=='diff':detail.append('Path: '+value.get('path','')+'\nBefore:\n'+str(value.get('oldText',''))+'\nAfter:\n'+value.get('newText',''))
    details='\n'.join(detail)
    print(wire({'id':key,'method':'antigravity/requestPermission','params':{'threadId':sid,'turnId':self.active[sid],'command':call.get('title','Antigravity tool'),'details':details}}),flush=True)
    try:
     response=await future;allow=response.get('decision')=='accept'
     choice=next((x for x in p.get('options',[]) if x.get('kind')==('allow_once' if allow else 'reject_once')),None)
     outcome={'outcome':'selected','optionId':choice['optionId']} if choice else {'outcome':'cancelled'}
     await self.peer.send({'id':message['id'],'result':{'outcome':outcome}})
    finally:self.permissions.pop(key,None)
   else:await self.peer.send({'id':message['id'],'error':{'code':-32601,'message':'Unsupported client capability'}})
   return
  if method!='session/update' or sid not in self.threads:return
  if sid not in self.loading and sid not in self.active:return
  update=p.get('update',{});kind=update.get('sessionUpdate');content=update.get('content',{})
  if kind=='agent_message_chunk' and content.get('type')=='text':self.append_chunk(sid,'agentMessage',content.get('text',''))
  elif kind=='user_message_chunk' and sid in self.loading and content.get('type')=='text':self.append_chunk(sid,'userMessage',content.get('text',''))
  elif kind in ('tool_call','tool_call_update'):
   previous=self.open_items.pop(sid,None)
   if previous and sid not in self.loading:self.emit('item/completed',{'threadId':sid,'turnId':self.active.get(sid),'item':previous})
   identifier=update.get('toolCallId');items=self.threads[sid]['items'];item=next((x for x in items if x.get('id')==identifier),None)
   if not item:item={'id':identifier,'type':'dynamicToolCall','tool':update.get('title',update.get('name','Antigravity tool')),'arguments':update.get('rawInput',{}),'contentItems':[]};items.append(item)
   if update.get('content') is not None:item['contentItems']=[{'type':'inputText','text':x.get('content',{}).get('text','')} for x in update['content'] if x.get('type')=='content' and x.get('content',{}).get('type')=='text']
   item['status']='failed' if update.get('status')=='failed' else 'completed' if update.get('status')=='completed' else 'inProgress'
   if sid not in self.loading:self.emit('item/completed' if item['status']!='inProgress' else 'item/started',{'threadId':sid,'turnId':self.active.get(sid),'item':item})
  elif kind=='config_option_update':self.sessions.setdefault(sid,{})['configOptions']=update.get('configOptions',[])
  elif kind=='session_info_update':
   if update.get('title'):self.threads[sid]['name']=update['title'];self.emit('thread/name/updated',{'threadId':sid,'threadName':update['title']})
 def answer(self,key,result):
  future=self.external.get(key) or (self.permissions.get(key) or (None,None))[1]
  if future is not None and not future.done():future.set_result(result)
 async def http(self,reader,writer):
  result={};status=200
  try:
   head=await asyncio.wait_for(reader.readuntil(b'\r\n\r\n'),10)
   if len(head)>8192:raise ValueError('Headers too large')
   lines=head.decode('ascii').split('\r\n');method,path,_=lines[0].split();headers=dict(x.split(':',1) for x in lines[1:] if ':' in x);length=int(next((v for k,v in headers.items() if k.lower()=='content-length'),'0'))
   if method!='POST' or not 0<length<=1024*1024:raise ValueError('Invalid request')
   body=json.loads(await asyncio.wait_for(reader.readexactly(length),10));parts=path.strip('/').split('/')
   if len(parts)!=2 or not secrets.compare_digest(parts[0],self.token):raise ValueError('Unknown endpoint')
   sid=self.tool_tokens.get(parts[1]);entry=self.threads.get(sid,{})
   if not sid:raise ValueError('Unknown session')
   if body.get('method')=='list':result={'tools':[{'name':x['name'],'description':x.get('description',''),'inputSchema':x.get('inputSchema',{'type':'object'})} for x in entry.get('tools',[])]}
   elif body.get('method')=='call':
    tool=body.get('name');turn=self.active.get(sid)
    if not turn or tool not in {x['name'] for x in entry.get('tools',[])}:raise ValueError('Tool or turn unavailable')
    identifier='agy-tool:'+str(uuid.uuid4());future=asyncio.get_running_loop().create_future();self.external[identifier]=future;self.external_owners[identifier]=sid
    try:
     print(wire({'id':identifier,'method':'item/tool/call','params':{'threadId':sid,'turnId':turn,'tool':tool,'arguments':body.get('arguments',{})}}),flush=True)
     result=await future
    finally:self.external.pop(identifier,None);self.external_owners.pop(identifier,None)
   else:raise ValueError('Unknown tool request')
  except Exception as error:status=400;result={'success':False,'contentItems':[{'type':'inputText','text':str(error)[:500]}]}
  data=wire(result).encode();writer.write(f'HTTP/1.1 {status} OK\r\nContent-Type: application/json\r\nContent-Length: {len(data)}\r\nConnection: close\r\n\r\n'.encode()+data)
  try:await writer.drain()
  finally:writer.close();await writer.wait_closed()
 async def dispatch(self,message):
  identifier=message.get('id')
  if 'method' not in message:
   self.answer(identifier,message.get('result',{'decision':'decline'}));return
  try:
   result=await self.handle(message['method'],message.get('params',{}))
   if identifier is not None:print(wire({'id':identifier,'result':result}),flush=True)
  except Exception as error:
   if identifier is not None:print(wire({'id':identifier,'error':{'code':getattr(error,'code',-32603),'message':str(error)[:2000]}}),flush=True)
 async def run(self):
  self.running_task=asyncio.current_task()
  await self.start();reader=asyncio.StreamReader(limit=LIMIT);protocol=asyncio.StreamReaderProtocol(reader);await asyncio.get_running_loop().connect_read_pipe(lambda:protocol,sys.stdin.buffer)
  try:
   while line:=await reader.readline():self.spawn(self.dispatch(json.loads(line)))
  finally:
   tasks=list(self.tasks)
   for task in tasks:task.cancel()
   if self.login:self.login.cancel()
   await asyncio.gather(*tasks,return_exceptions=True)
   self.server.close();await self.server.wait_closed();await self.peer.close()

if __name__=='__main__':
 try:asyncio.run(Bridge(sys.argv[1:],os.environ.get('CODEX_GO_ACP_STATE','/root/.gemini/codex-go-sessions.json')).run())
 except asyncio.CancelledError:raise SystemExit(1)
 except Exception as error:print('Antigravity bridge failed: '+type(error).__name__,file=sys.stderr);raise SystemExit(1)
