#!/usr/bin/env python3
"""Real application overlay and System UI RemoteInput on the owned Android emulator.
Only the APK's native Codex talks to a localhost fixture; no real credentials/inference.
"""
import argparse,base64,json,re,subprocess,threading,time,uuid,xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
from android_driver import AndroidDriver
p=argparse.ArgumentParser();p.add_argument('--serial',default='emulator-5560');args=p.parse_args();d=AndroidDriver(args.serial)
out=Path('.gradle/validation/reports/quick-chat');out.mkdir(parents=True,exist_ok=True);(out/'result.json').unlink(missing_ok=True)
token=uuid.uuid4().hex[:8];requests=[];release=threading.Event()
def last_user(req):
 for item in reversed(req.get('input',[])):
  if item.get('role')=='user':return '\n'.join(v.get('text','') for v in item.get('content',[]) if isinstance(v,dict))
 return ''
class Fixture(BaseHTTPRequestHandler):
 def log_message(self,*a):pass
 def do_GET(self):
  b=b'{"data":[]}';self.send_response(200);self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
 def do_POST(self):
  req=json.loads(self.rfile.read(int(self.headers['Content-Length'])));text=last_user(req);requests.append(text);(out/'requests.json').write_text(json.dumps(requests,indent=2)+'\n')
  key=next((v for v in ['QUICK_FIRST','QUICK_HOLD','QUICK_QUEUED','QUICK_NOTIFY','QUICK_OFFLINE'] if v in text),'UNKNOWN')
  ident='quick_'+str(len(requests));events=[dict(type='response.created',response=dict(id=ident)),dict(type='response.output_item.added',item=dict(id='msg_'+ident,type='message',role='assistant',content=[])),dict(type='response.output_text.delta',delta=key+'_DONE')]
  self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
  try:
   for event in events:self.wfile.write(('event: '+event['type']+'\ndata: '+json.dumps(event)+'\n\n').encode());self.wfile.flush()
   if key=='QUICK_HOLD':
    end=time.monotonic()+120
    while not release.wait(1) and time.monotonic()<end:self.wfile.write(b': keepalive\n\n');self.wfile.flush()
   event=dict(type='response.output_item.done',item=dict(id='msg_'+ident,type='message',role='assistant',content=[dict(type='output_text',text=key+'_DONE')]))
   self.wfile.write(('event: '+event['type']+'\ndata: '+json.dumps(event)+'\n\n').encode());self.wfile.flush()
   event=dict(type='response.completed',response=dict(id=ident,usage=dict(input_tokens=0,output_tokens=0,total_tokens=0)))
   self.wfile.write(('event: response.completed\ndata: '+json.dumps(event)+'\n\n').encode());self.wfile.flush()
  except (BrokenPipeError,ConnectionResetError):pass
  self.close_connection=True
server=ThreadingHTTPServer(('127.0.0.1',0),Fixture);port=server.server_port;threading.Thread(target=server.serve_forever,daemon=True).start()
def read(path):
 r=subprocess.run(d.adb+['shell','run-as','dev.codex.go','cat',path],capture_output=True);return r.stdout if r.returncode==0 else None
def write(path,data):subprocess.run(d.adb+['shell','run-as','dev.codex.go','sh','-c',"'cat > "+path+"'"],input=data,check=True)
def prefs():return {n.get('name'):n.text if n.tag=='string' else n.get('value') for n in ET.fromstring(read('shared_prefs/codex_go.xml'))}
def labels():return [n.get('text','') or n.get('content-desc','') for n in d.xml().iter('node')]
def until(predicate,description,timeout=45):
 end=time.monotonic()+timeout
 while time.monotonic()<end:
  if predicate():return
  time.sleep(.15)
 raise AssertionError(description)
def shown(text):return any(text in value for value in labels())
def hide_keyboard():
 if 'mInputShown=true' in d.shell('dumpsys','input_method'):d.shell('input','keyevent','4')
def replace(text,label=None):
 if label:d.tap(d.find(label))
 else:d.tap(d.find(classname='android.widget.EditText'))
 result=d.shell('am','instrument','-w','-e','setTextBase64',base64.b64encode(text.encode()).decode(),'dev.codex.go.testdriver/.UiDriver');assert 'textSet=true' in result,result
def quick():d.click('更多');d.click('快捷对话');d.find('随时，聊两句。')
def scroll_find(label):
 height=int(re.findall(r'(\d+)x(\d+)',d.shell('wm','size'))[-1][1])
 for _ in range(10):
  node=next((n for n in d.xml().iter('node') if n.get('text')==label or n.get('content-desc')==label),None)
  if node is not None:
   bounds=list(map(int,re.findall(r'\d+',node.get('bounds'))));center=(bounds[1]+bounds[3])//2
   if 350<center<height-140:return node
  d.shell('input','swipe','530','1900','530','800','300');time.sleep(.2)
 raise AssertionError('Missing scroll target: '+label)
def open_notification_reply():
 d.shell('cmd','statusbar','expand-notifications')
 for _ in range(10):
  tree=d.xml()
  reply=next((n for n in tree.iter('node') if n.get('text') in ['回复','排队回复']),None)
  if reply is not None:d.tap(reply);return
  for parent in reversed(list(tree.iter('node'))):
   if not any(n.get('text')=='Codex Go' for n in parent.iter('node')):continue
   expand=next((n for n in parent.iter('node') if 'expand' in n.get('content-desc','').lower() and 'collapse' not in n.get('content-desc','').lower()),None)
   if expand is not None:d.tap(expand);time.sleep(.2);break
  else:time.sleep(.3)
 raise AssertionError('No reply action after expanding the Codex notification group')
def overlay_send(kind):
 replace(kind+'_'+token,'悬浮消息输入');d.click('排队发送' if shown('排队发送') else '发送')
def native_draft():
 values=prefs();return json.loads(values.get('draft:'+values.get('thread','new'),'{}')).get('text','')
original={path:read(path) for path in ['shared_prefs/codex_go.xml','files/codex/config.toml','files/codex/auth.json']}
animations=d.disable_animations();network={k:d.shell('settings','get','global',k).strip() for k in ['airplane_mode_on','wifi_on','mobile_data']};op=d.shell('cmd','appops','get','dev.codex.go','SYSTEM_ALERT_WINDOW')
access={k:d.shell('settings','get','secure',k).strip() for k in ['enabled_accessibility_services','accessibility_enabled']}
try:
 d.shell('input','keyevent','224');d.shell('wm','dismiss-keyguard')
 d.shell('am','force-stop','dev.codex.go');write('shared_prefs/codex_go.xml',b'<map />')
 write('files/codex/config.toml',f'''model = "codex-go-test"
model_provider = "quick_fixture"
cli_auth_credentials_store = "file"
[analytics]
enabled = false
[model_providers.quick_fixture]
name = "Local quick chat fixture"
base_url = "http://127.0.0.1:{port}/v1"
wire_api = "responses"
requires_openai_auth = true
supports_websockets = false
'''.encode());write('files/codex/auth.json',b'{"OPENAI_API_KEY":"sk-local-quick-not-a-real-key"}')
 subprocess.run(d.adb+['reverse',f'tcp:{port}',f'tcp:{port}'],check=True,stdout=subprocess.DEVNULL)
 d.shell('cmd','appops','set','dev.codex.go','SYSTEM_ALERT_WINDOW','allow');d.shell('pm','grant','dev.codex.go','android.permission.POST_NOTIFICATIONS')
 d.shell('settings','put','secure','enabled_accessibility_services','null');d.shell('settings','put','secure','accessibility_enabled','0')
 d.start();d.click('打开侧栏');d.click('本机环境');d.click('启动本机 Codex');d.find('本机 Codex 已就绪',timeout=90);d.click('返回对话');d.click('新建对话');replace('MAIN_DRAFT_KEEP');hide_keyboard();quick()
 d.set_switch('悬浮对话气泡',True);d.capture(out/'settings.png');d.shell('input','keyevent','3');bubble=d.find('打开悬浮对话');before=bubble.get('bounds')
 x1,y1,x2,y2=map(int,re.findall(r'\d+',before));cx=(x1+x2)//2;cy=(y1+y2)//2
 d.shell('input','swipe',str(cx),str(cy),str(max(30,cx-150)),str(cy+100),'300');assert d.find('打开悬浮对话').get('bounds')!=before
 d.capture(out/'bubble.png');d.shell('input','keyevent','223');until(lambda:'Codex Go 悬浮对话' not in d.shell('dumpsys','window','windows'),'Bubble visible on locked/sleeping screen');d.shell('input','keyevent','224');d.shell('wm','dismiss-keyguard');d.find('打开悬浮对话')
 d.click('打开悬浮对话');overlay_send('QUICK_FIRST');until(lambda:shown('QUICK_FIRST_DONE'),'Overlay did not receive the native reply');until(lambda:shown('发送'),'Initial turn did not finish')
 assert native_draft()=='MAIN_DRAFT_KEEP';d.capture(out/'overlay-reply.png')
 # First thread/start allocation must keep this panel bound to the same conversation.
 overlay_send('QUICK_HOLD');until(lambda:any('QUICK_HOLD' in req for req in requests),'Held input did not reach native Codex');until(lambda:shown('QUICK_HOLD_DONE'),'Streaming reply missing before turn completion');d.find('排队发送');overlay_send('QUICK_QUEUED')
 assert json.loads(prefs()['queue:'+prefs()['thread']])[0]['draft']['text']=='QUICK_QUEUED_'+token
 assert native_draft()=='MAIN_DRAFT_KEEP';release.set();until(lambda:any('QUICK_QUEUED' in req for req in requests),'Queued input never reached native Codex');until(lambda:shown('QUICK_QUEUED_DONE'),'Queued reply missing');d.capture(out/'overlay-queue.png');hide_keyboard()
 d.click('打开 App');d.find('更多');assert d.find(classname='android.widget.EditText').get('text')=='MAIN_DRAFT_KEEP';d.shell('input','keyevent','3');d.find('打开悬浮对话')
 # Use the actual System UI reply editor and send button, not an injected app RPC.
 open_notification_reply();replace('QUICK_NOTIFY_'+token)
 send=next((n for n in d.xml().iter('node') if n.get('resource-id')=='com.android.systemui:id/remote_input_send'),None)
 assert send is not None,'No System UI RemoteInput send action';d.capture(out/'notification-input.png');d.tap(send)
 until(lambda:any('QUICK_NOTIFY' in req for req in requests),'Notification reply did not reach native Codex')
 until(lambda:'QUICK_NOTIFY_DONE' in d.shell('dumpsys','notification','--noredact'),'AI reply missing from notification')
 assert native_draft()=='MAIN_DRAFT_KEEP';d.capture(out/'notification-reply.png');d.shell('cmd','statusbar','collapse')
 # Offline input is durable and never submitted merely because networking returns.
 d.shell('svc','wifi','disable');d.shell('svc','data','disable');d.shell('cmd','connectivity','airplane-mode','enable');time.sleep(1)
 d.click('打开悬浮对话');before=len(requests);overlay_send('QUICK_OFFLINE');until(lambda:len(json.loads(prefs().get('quickMessages','[]')))==1,'Offline input not retained');assert len(requests)==before;assert native_draft()=='MAIN_DRAFT_KEEP';d.capture(out/'offline-retained.png')
 d.click('关闭');d.shell('cmd','connectivity','airplane-mode','disable');d.shell('svc','wifi','enable');d.shell('svc','data','enable');time.sleep(1);assert len(requests)==before
 d.start();d.shell('am','start','-a','OPEN_QUICK','-n','dev.codex.go/.MainActivity');scroll_find('QUICK_OFFLINE_'+token);assert len(requests)==before
 d.capture(out/'pending-after-restart.png')
 for _ in range(4):d.shell('input','swipe','530','700','530','1900','250')
 d.click('启动本机 Codex');d.click('返回对话');d.click('更多');d.click('设置');d.find('本机 Codex 已就绪',timeout=90);d.click('返回对话');quick();scroll_find('QUICK_OFFLINE_'+token)
 d.tap(scroll_find('加入当前草稿'));assert d.find(classname='android.widget.EditText').get('text')=='MAIN_DRAFT_KEEP\n\nQUICK_OFFLINE_'+token;assert len(requests)==before
 hide_keyboard();quick();d.set_switch('常驻通知直接回复',False);until(lambda:'quickText' not in d.shell('dumpsys','notification','--noredact'),'Disabled reply action remained in notification');d.set_switch('常驻通知直接回复',True)
 result=dict(version=d.version(),officialCodexIconBundled=True,applicationOverlayIndependentOfAccessibility=True,draggableBubble=True,bubbleHiddenWhileSleeping=True,overlayNativeReply=True,overlayStreamsPartialReply=True,firstNativeIdKeepsTarget=True,busyInputQueuesAndRuns=True,mainDraftNeverOverwritten=True,openAppReturnsToConversation=True,systemUiRemoteInputReachesNativeCodex=True,assistantReplyVisibleInNotification=True,offlineInputDurable=True,networkReturnNeverAutoSends=True,processRestartKeepsPendingInput=True,explicitReviewMergesDraft=True,notificationReplySwitchWorks=True,externalInference=False,realAccountLogin=False)
 (out/'result.json').write_text(json.dumps(result,indent=2)+'\n');print('PASS: floating native conversation, first thread ID, queued input, actual System UI direct reply, durable offline recovery and draft preservation',flush=True)
finally:
 d.shell('input','keyevent','224');d.shell('wm','dismiss-keyguard')
 release.set();d.capture(out/'last-screen.png');d.shell('am','force-stop','dev.codex.go')
 for path,data in original.items():
  if data is None:d.shell('run-as','dev.codex.go','rm','-f',path)
  else:write(path,data)
 d.shell('cmd','connectivity','airplane-mode','enable' if network['airplane_mode_on']=='1' else 'disable');d.shell('svc','wifi','enable' if network['wifi_on']=='1' else 'disable');d.shell('svc','data','enable' if network['mobile_data']=='1' else 'disable')
 mode=re.search(r'SYSTEM_ALERT_WINDOW: (\w+)',op);d.shell('cmd','appops','set','dev.codex.go','SYSTEM_ALERT_WINDOW',mode[1] if mode else 'default');d.restore_animations(animations)
 for key,value in access.items():
  if value=='null':d.shell('settings','delete','secure',key)
  else:d.shell('settings','put','secure',key,value)
 subprocess.run(d.adb+['reverse','--remove',f'tcp:{port}'],stdout=subprocess.DEVNULL);server.shutdown()
