#!/usr/bin/env python3
"""Owned emulator: native execution continues while another thread/project is browsed.
Responses are local fixtures; thread history, approvals, queues, notifications and persistence are real.
"""
import argparse,json,subprocess,threading,time,uuid
from pathlib import Path
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import xml.etree.ElementTree as ET
from android_driver import AndroidDriver
parser=argparse.ArgumentParser();parser.add_argument('--serial',default='emulator-5560');args=parser.parse_args()
d=AndroidDriver(args.serial);out=Path('.gradle/validation/reports/background-flow');out.mkdir(parents=True,exist_ok=True);(out/'result.json').unlink(missing_ok=True)
token=uuid.uuid4().hex[:8];project_name='Browse_'+token;requests=[];releases={k:threading.Event() for k in ['BG_A','BG_A_DONE','BG_QUEUE','BG_FAIL','BG_STOP']};created=[]
def read(path):
 p=subprocess.run(d.adb+['shell','run-as','dev.codex.go','cat',path],capture_output=True);return p.stdout if p.returncode==0 else None
def write(path,data):subprocess.run(d.adb+['shell','run-as','dev.codex.go','sh','-c',"'cat > "+path+"'"],input=data,check=True)
def value(key,default=None):return next((n.text for n in ET.fromstring(read('shared_prefs/codex_go.xml')) if n.get('name')==key),default)
def draft(slot):return json.loads(value('draft:'+slot,'{}'))
def patch(entries):
 root=ET.fromstring(read('shared_prefs/codex_go.xml'))
 for n in list(root):
  if n.get('name') in entries:root.remove(n)
 for key,val in entries.items():
  if val is not None:ET.SubElement(root,'string',name=key).text=val
 write('shared_prefs/codex_go.xml',ET.tostring(root,encoding='utf-8',xml_declaration=True))
def labels():return [n.get('text','') or n.get('content-desc','') for n in d.xml().iter('node')]
def text(s):d.set_text(s);d.shell('input','keyevent','4')
def send(s):text(s+'_'+token);d.click('发送消息')
def wait_until(check,description,timeout=35):
 end=time.monotonic()+timeout
 while time.monotonic()<end:
  if check():return
  time.sleep(.15)
 raise AssertionError(description)
def scroll_click(label):
 for _ in range(3):d.shell('input','swipe','530','700','530','1900','200')
 for _ in range(10):
  node=next((n for n in d.xml().iter('node') if n.get('text')==label or n.get('content-desc')==label),None)
  if node is not None:d.tap(node);return
  d.shell('input','swipe','530','1900','530','700','200')
 raise AssertionError('Missing '+label)
def projects():d.click('更多');d.click('项目');d.find('你的项目')
def choose(name):projects();scroll_click('进入项目 '+name);d.find('更多')
def browse_b():
 choose(project_name);d.click('打开侧栏');d.find('BG_B_'+token);d.click('BG_B_'+token);d.find('BG_B_DONE')
def start_engine():
 d.click('打开侧栏');d.click('本机环境');d.click('启动 Codex');d.find('本机 Codex 已就绪',timeout=90);d.click('返回对话')
def last_user(request):
 for i in reversed(request.get('input',[])):
  if i.get('role')=='user':return '\n'.join(c.get('text','') for c in i.get('content',[]) if isinstance(c,dict))
 return ''
class Fixture(BaseHTTPRequestHandler):
 def log_message(self,*a):pass
 def do_GET(self):
  b=b'{"data":[]}';self.send_response(200);self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
 def do_POST(self):
  req=json.loads(self.rfile.read(int(self.headers['Content-Length'])));latest=last_user(req)
  kind=next((k for k in ['BG_QUEUE','BG_FAIL','BG_STOP','BG_B','BG_A'] if k in latest),'UNKNOWN');requests.append(dict(kind=kind,input=req.get('input',[])))
  if kind=='BG_FAIL':
   releases[kind].wait(240);b=b'{"error":{"message":"background fixture failed","type":"invalid_request_error","code":"invalid_api_key"}}'
   self.send_response(401);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b);return
  ident='bg_'+str(len(requests));mid='msg_'+ident;call='patch_'+token
  has_output=any(i.get('call_id')==call and i.get('type')=='custom_tool_call_output' for i in req.get('input',[]))
  hold_key='BG_A_DONE' if kind=='BG_A' and has_output else kind
  hold=hold_key in releases
  reply=kind+('_WORKING' if kind=='BG_STOP' or (kind=='BG_A' and not has_output) else '_DONE')
  self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
  def event(v):self.wfile.write(('event: '+v['type']+'\ndata: '+json.dumps(v)+'\n\n').encode());self.wfile.flush()
  try:
   event(dict(type='response.created',response=dict(id=ident)))
   event(dict(type='response.output_item.added',item=dict(id=mid,type='message',role='assistant',content=[])))
   event(dict(type='response.output_text.delta',delta=reply))
   if hold:
    while not releases[hold_key].wait(1):self.wfile.write(b': keepalive\n\n');self.wfile.flush()
   event(dict(type='response.output_item.done',item=dict(id=mid,type='message',role='assistant',content=[dict(type='output_text',text=reply)])))
   if kind=='BG_A' and not has_output:
    patch='*** Begin Patch\n*** Add File: /workspace/background-'+token+'.txt\n+BG_APPROVED\n*** End Patch'
    event(dict(type='response.output_item.done',item=dict(type='custom_tool_call',id='fc_'+ident,call_id=call,name='apply_patch',input=patch)))
   event(dict(type='response.completed',response=dict(id=ident,usage=dict(input_tokens=0,output_tokens=0,total_tokens=0))))
  except (BrokenPipeError,ConnectionResetError):pass
  self.close_connection=True
server=ThreadingHTTPServer(('127.0.0.1',0),Fixture);port=server.server_port;threading.Thread(target=server.serve_forever,daemon=True).start()
original={p:read(p) for p in ['shared_prefs/codex_go.xml','files/codex/config.toml','files/codex/auth.json']};animations=d.disable_animations()
try:
 subprocess.run(d.adb+['reverse',f'tcp:{port}',f'tcp:{port}'],check=True,stdout=subprocess.DEVNULL)
 d.shell('am','force-stop','dev.codex.go');patch({'project':'default','thread':None,'reading':None,'taskRecord':None,'draft:new':None,'queue:new':None,'retry:new':None,'shareInbox':'[]'})
 # The bundled catalog model advertises the native custom apply_patch tool used below.
 config=f'''model="gpt-6.1-sol"
model_provider="codex_go_test"
cli_auth_credentials_store="file"
[analytics]
enabled=false
[model_providers.codex_go_test]
name="Local background fixture"
base_url="http://127.0.0.1:{port}/v1"
wire_api="responses"
requires_openai_auth=true
supports_websockets=false
request_max_retries=0
stream_max_retries=0
stream_idle_timeout_ms=300000
'''
 d.private_write('files/codex/config.toml',config.encode());d.private_write('files/codex/auth.json',json.dumps({'OPENAI_API_KEY':'sk-local-background-'+token}).encode())
 d.start();start_engine();projects();scroll_click('创建项目');text(project_name);d.click('创建并进入')
 project=next(p['id'] for p in json.loads(value('projects')) if p['name']==project_name);created.append(project)
 send('BG_B');d.find('BG_B_DONE',timeout=45);d.find('发送消息');b=value('thread');text('B_DRAFT_'+token)
 choose('默认项目');send('BG_A');d.find('BG_A_WORKING',timeout=45);a=value('thread');old=json.loads(value('taskRecord'))['token']
 text('BG_QUEUE_'+token);d.click('发送方式 ▾');d.click('排队，本轮结束后发送');text('A_NEXT_'+token)
 before=len(requests);browse_b();assert value('thread')==a and value('project')=='default'
 text('B_CHANGED_'+token);assert draft(b)['text']=='B_CHANGED_'+token
 assert '停止任务' not in labels() and '插话' not in labels() and '发送方式 ▾' not in labels()
 d.click('发送消息');assert len(requests)==before and json.loads(value('reading'))['thread']==b
 print('Cross-project browsing keeps A running and B send disabled',flush=True)
 d.shell('am','start','-n','dev.codex.go.sharefixture/dev.codex.go.testdriver.ShareSourceActivity','--es','kind','one','--es','text','B_SHARED')
 d.find('分享收件箱');d.click('加入当前草稿');assert draft(b)['attachments'][0]['path'].startswith('/projects/'+project+'/imports/')
 assert value('thread')==a and len(requests)==before
 rotation=d.shell('settings','get','system','user_rotation').strip();accelerometer=d.shell('settings','get','system','accelerometer_rotation').strip()
 try:
  d.shell('settings','put','system','accelerometer_rotation','0');d.shell('settings','put','system','user_rotation','1');d.find('BG_B_'+token);assert d.find(classname='android.widget.EditText').get('text')=='B_CHANGED_'+token+'\n\nB_SHARED'
 finally:
  d.shell('settings','put','system','user_rotation',rotation);d.shell('settings','put','system','accelerometer_rotation',accelerometer)
 d.find('BG_B_DONE');d.capture(out/'b-while-a-runs.png');releases['BG_A'].set();d.find('等待确认 · 1 项',timeout=30)
 assert '允许本次' not in labels();assert read('files/workspace/background-'+token+'.txt') is None
 d.capture(out/'approval-stays-with-a.png');d.click('回到任务');d.find('允许本次');d.click('允许本次');d.find('BG_A_DONE',timeout=50)
 browse_b();releases['BG_A_DONE'].set();wait_until(lambda:any(r['kind']=='BG_QUEUE' for r in requests),'Owner queue did not start while B was viewed')
 assert json.loads(value('reading'))['thread']==b and value('thread')==a
 releases['BG_QUEUE'].set();d.find('任务已完成',timeout=35);d.find('BG_B_DONE');d.capture(out/'completion-keeps-b.png');d.click('回到任务');d.find('BG_QUEUE_DONE')
 assert read('files/workspace/background-'+token+'.txt')==b'BG_APPROVED\n';assert draft(a)['text']=='A_NEXT_'+token
 assert any(r['kind']=='BG_QUEUE' for r in requests);assert draft(b)['text']=='B_CHANGED_'+token+'\n\nB_SHARED'
 print('Native read-only browsing, cross-project B drafts/share, rotation, A approval and automatic queue verified',flush=True)
 send('BG_FAIL');d.find('停止任务');browse_b();releases['BG_FAIL'].set();d.find('任务失败',timeout=30)
 assert '账户验证未通过' not in labels() and '重试上一条消息' not in labels();assert d.find(classname='android.widget.EditText').get('text')=='B_CHANGED_'+token+'\n\nB_SHARED'
 print('A failure leaves B visible and its draft unchanged',flush=True)
 d.capture(out/'failure-keeps-b.png');failed_token=json.loads(value('taskRecord'))['token'];before=len(requests)
 d.start();start_engine();d.find('BG_B_DONE');d.find('任务状态待确认');assert value('thread')==a and len(requests)==before
 assert json.loads(value('taskRecord'))['token']==failed_token
 assert d.find(classname='android.widget.EditText').get('text')=='B_CHANGED_'+token+'\n\nB_SHARED'
 print('B selection/draft and A token restored after process restart',flush=True)
 before=len(requests);d.click('进入此对话');wait_until(lambda:value('thread')==b,'Explicit activation never selected B');d.find('BG_B_DONE')
 assert value('reading') is None and len(requests)==before and draft(b)['text']=='B_CHANGED_'+token+'\n\nB_SHARED'
 choose('默认项目');d.click('打开侧栏');d.find('BG_A_'+token);d.click('BG_A_'+token);d.find('上次任务状态待确认');send('BG_STOP');d.find('BG_STOP_WORKING',timeout=45);new=json.loads(value('taskRecord'))['token'];assert new!=old
 browse_b()
 d.shell('run-as','dev.codex.go','am','startservice','--user','0','-n','dev.codex.go/.runtime.EngineService','-a','INTERRUPT','--es','taskToken',old)
 d.shell('run-as','dev.codex.go','am','startservice','--user','0','-n','dev.codex.go/.runtime.EngineService','-a','REVOKE','--es','taskToken',old)
 d.shell('am','start','-a','OPEN_TASK','-n','dev.codex.go/.MainActivity','--es','taskToken',old)
 d.find('BG_B_DONE');d.find('任务正在运行');assert json.loads(value('reading'))['thread']==b
 # Actual foreground notification must route back to A, even while the activity remembers B.
 d.shell('input','keyevent','3');d.shell('cmd','statusbar','expand-notifications');d.find('Codex Go')
 notification=next(n for n in d.xml().iter('node') if n.get('resource-id')=='android:id/title' and n.get('text')=='Codex Go')
 d.tap(notification);d.find('BG_STOP_WORKING');d.find('停止任务')
 assert value('reading') is None;d.click('停止任务');d.find('任务已停止，可以继续对话。',timeout=30)
 assert draft(b)['text']=='B_CHANGED_'+token+'\n\nB_SHARED';d.capture(out/'notification-return-and-stop.png')
 print('Failure isolation, durable B selection/draft, task token recovery, stale action rejection and notification return/stop verified',flush=True)
 result=dict(version=d.version(),nativeBackgroundThreadRead=True,executionOwnerNeverChangesDuringBrowsing=True,bDraftShareAndDisabledSend=True,perConversationDraftsSurviveRotation=True,approvalsBelongToRunningTask=True,ownerQueueContinuesWhileViewingB=True,completionDoesNotReplaceViewedConversation=True,failureDoesNotReplaceViewedConversation=True,viewedSelectionAndDraftSurviveRestart=True,taskTokenSurvivesRecovery=True,explicitActivationDoesNotSend=True,staleActionsIgnored=True,notificationReturnsToExecutingThread=True,stopTargetsExecutingThread=True,externalInference=False,realAccountLogin=False)
 (out/'result.json').write_text(json.dumps(result,indent=2)+'\n');print('PASS: background conversation browsing',flush=True)
finally:
 for event in releases.values():event.set()
 d.capture(out/'last-screen.png');d.shell('cmd','statusbar','collapse');d.shell('am','force-stop','dev.codex.go')
 for p,data in original.items():
  if data is None:d.shell('run-as','dev.codex.go','rm','-f',p)
  else:write(p,data)
 for project in created:d.shell('run-as','dev.codex.go','rm','-rf','files/projects/'+project)
 d.shell('run-as','dev.codex.go','rm','-f','files/workspace/background-'+token+'.txt');d.shell('am','force-stop','dev.codex.go.sharefixture');d.restore_animations(animations)
 subprocess.run(d.adb+['reverse','--remove',f'tcp:{port}'],stdout=subprocess.DEVNULL);server.shutdown()
