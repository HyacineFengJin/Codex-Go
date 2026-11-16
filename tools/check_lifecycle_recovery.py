#!/usr/bin/env python3
"""Real Android/native Codex lifecycle against a local, deterministic Responses server."""
import argparse,collections,json,re,subprocess,threading,time,uuid,xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
from android_driver import AndroidDriver

p=argparse.ArgumentParser();p.add_argument('--serial',default='emulator-5570');a=p.parse_args();d=AndroidDriver(a.serial)
out=Path('.gradle/validation/reports/lifecycle-recovery');out.mkdir(parents=True,exist_ok=True);(out/'result.json').unlink(missing_ok=True)
token=uuid.uuid4().hex[:8];requests=[];counts=collections.Counter();release=threading.Event();project=None;thread=None
filename='life-native-'+token+'.txt';forbidden='life-must-not-run-'+token+'.txt'
def kind_of(body):
 for message in reversed(body.get('input',[])):
  if message.get('role')!='user':continue
  content=message.get('content',[]);text=content if isinstance(content,str) else '\n'.join(x.get('text','') for x in content if isinstance(x,dict))
  for kind in ['LIFE_SIMPLE','LIFE_FILE','LIFE_HOLD','LIFE_QUEUED','LIFE_AFTER_REBOOT']:
   if kind+'_'+token in text:return kind
 return 'UNKNOWN'
class Fixture(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_GET(self):
  data=b'{"data":[]}';self.send_response(200);self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
 def do_POST(self):
  body=json.loads(self.rfile.read(int(self.headers['Content-Length'])));kind=kind_of(body);counts[kind]+=1;requests.append({'kind':kind,'model':body.get('model'),'reasoning':body.get('reasoning')})
  response='resp_life_'+str(len(requests));call='call_'+kind+'_'+token
  has_output=any(x.get('call_id')==call and x.get('type') in ['function_call_output','custom_tool_call_output'] for x in body.get('input',[]))
  self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
  def event(data):self.wfile.write(('event: '+data['type']+'\ndata: '+json.dumps(data)+'\n\n').encode());self.wfile.flush()
  try:
   event({'type':'response.created','response':{'id':response}})
   if kind=='LIFE_HOLD' and not has_output:
    event({'type':'response.output_item.added','item':{'id':'working','type':'message','role':'assistant','content':[]}})
    event({'type':'response.output_text.delta','delta':'LIFE_HOLD_WORKING'})
    release.wait(150)
   if kind in ['LIFE_FILE','LIFE_HOLD'] and not has_output:
    command=('printf LIFE_FILE > '+filename+'; pwd') if kind=='LIFE_FILE' else ('printf MUST_NOT_RUN > '+forbidden)
    event({'type':'response.output_item.done','item':{'type':'function_call','id':'fc_'+kind,'call_id':call,'name':'exec_command','arguments':json.dumps({'cmd':command,'max_output_tokens':1000})}})
   else:event({'type':'response.output_item.done','item':{'type':'message','id':'done_'+response,'role':'assistant','content':[{'type':'output_text','text':kind+'_DONE'}]}})
   event({'type':'response.completed','response':{'id':response,'usage':{'input_tokens':0,'output_tokens':0,'total_tokens':0}}})
  except (BrokenPipeError,ConnectionResetError):pass
  self.close_connection=True

server=ThreadingHTTPServer(('127.0.0.1',0),Fixture);port=server.server_port;threading.Thread(target=server.serve_forever,daemon=True).start()
def reverse():subprocess.run(d.adb+['reverse',f'tcp:{port}',f'tcp:{port}'],check=True,stdout=subprocess.DEVNULL)
def prefs():return ET.fromstring(d.shell('run-as','dev.codex.go','cat','shared_prefs/codex_go.xml'))
def value(key,default=None):return next((n.text if n.tag=='string' else n.get('value') for n in prefs() if n.get('name')==key),default)
def labels():return [n.get('text','').strip() or n.get('content-desc','') for n in d.xml().iter('node')]
def text(message):d.set_text(message)
def hide_keyboard():
 if 'mInputShown=true' in d.shell('dumpsys','input_method'):d.shell('input','keyevent','KEYCODE_BACK');time.sleep(.15)
def current_draft():return d.find(classname='android.widget.EditText').get('text','')
def assert_draft(expected):assert current_draft()==expected,(current_draft(),expected)
def open_app():
 d.shell('input','keyevent','KEYCODE_WAKEUP');d.shell('wm','dismiss-keyguard');d.shell('am','start','-f','0x10000000','-n','dev.codex.go/.MainActivity')
def wait_ready(allow_start=False):
 d.click('打开快捷控制')
 started=False
 end=time.monotonic()+100
 while time.monotonic()<end:
  nodes=labels()
  if allow_start and not started and '启动 Codex' in nodes:d.click('启动 Codex');started=True;continue
  if '已就绪' in nodes or '已就绪' in '\n'.join(nodes):d.click('关闭快捷控制');return
  if any(x in nodes for x in ['启动失败','本机环境已断开']):raise AssertionError('Native startup failed: '+'\n'.join(nodes))
  time.sleep(.2)
 raise AssertionError('Native engine did not become ready: '+str(nodes))
def complete(reply):
 end=time.monotonic()+70
 while time.monotonic()<end:
  nodes=labels()
  if '允许本次' in nodes:d.click('允许本次')
  if reply in nodes:return
  time.sleep(.1)
 raise AssertionError('Reply missing: '+reply+'; labels='+str(labels()))
def send(kind):text(kind+'_'+token);d.click('发送消息')
def queue_message():
 text('LIFE_QUEUED_'+token)
 mode=next(x for x in labels() if '发送方式' in x);d.click(mode);d.click('排队，本轮结束后发送')
def booted():
 end=time.monotonic()+55
 while time.monotonic()<end:
  result=subprocess.run(d.adb+['shell','getprop','sys.boot_completed'],capture_output=True,text=True)
  if result.stdout.strip()=='1':return
  time.sleep(.5)
 raise AssertionError('Emulator reboot not completed')

try:
 reverse();d.shell('am','force-stop','dev.codex.go');d.shell('run-as','dev.codex.go','mkdir','-p','files/codex')
 config=f'''model="codex-go-test"
model_provider="codex_go_lifecycle"
cli_auth_credentials_store="file"
[analytics]
enabled=false
[model_providers.codex_go_lifecycle]
name="Owned lifecycle fixture"
base_url="http://127.0.0.1:{port}/v1"
wire_api="responses"
requires_openai_auth=true
supports_websockets=false
request_max_retries=0
stream_max_retries=0
stream_idle_timeout_ms=300000
'''
 d.private_write('files/codex/config.toml',config.encode());d.private_write('files/codex/auth.json',json.dumps({'OPENAI_API_KEY':'sk-local-lifecycle-'+token}).encode());d.preference('autoPrepare',True)
 d.shell('pm','grant','dev.codex.go','android.permission.POST_NOTIFICATIONS');d.shell('cmd','locale','set-app-locales','dev.codex.go','--locales','zh-Hans');open_app()
 # First use still requires the user's explicit start; subsequent openings remember it.
 nodes=labels();start=next((x for x in nodes if x.strip() in ['启动 Codex','启动本机 Codex','启动本机Codex','启动Codex']),None)
 if start:d.click(start)
 wait_ready(allow_start=True);print('Lifecycle: actual native startup ready',flush=True)
 d.click('更多');d.click('项目');d.scroll_click('创建项目');text('LIFECYCLE_'+token);d.click('创建并进入');d.find('发送消息')
 project=value('project');assert project and project!='default';folder='files/projects/'+project
 send('LIFE_SIMPLE');complete('LIFE_SIMPLE_DONE');hide_keyboard();thread=value('thread');assert thread
 send('LIFE_FILE');complete('LIFE_FILE_DONE');hide_keyboard();assert d.shell('run-as','dev.codex.go','cat',folder+'/'+filename)=='LIFE_FILE'
 draft='LIFE_UNSENT_'+token;text(draft);hide_keyboard();d.shell('input','keyevent','KEYCODE_HOME');open_app();assert_draft(draft)
 d.shell('input','keyevent','KEYCODE_BACK');open_app();assert_draft(draft);print('Lifecycle: Home and Back reopen preserve native session and draft',flush=True)
 send('LIFE_HOLD');d.find('LIFE_HOLD_WORKING',timeout=45);queue_message();draft='LIFE_PENDING_'+token;text(draft);hide_keyboard()
 d.shell('input','keyevent','KEYCODE_HOME');open_app();assert_draft(draft);assert counts['LIFE_QUEUED']==0
 d.shell('input','keyevent','KEYCODE_HOME');pid=d.shell('pidof','dev.codex.go').strip();assert re.fullmatch(r'\d+',pid)
 d.shell('run-as','dev.codex.go','kill','-9',pid);time.sleep(.5);release.set();time.sleep(.5);before=len(requests)
 open_app();wait_ready();assert_draft(draft);assert value('thread')==thread
 time.sleep(1);assert len(requests)==before and counts['LIFE_QUEUED']==0,'Process recovery replayed a task or queue'
 assert json.loads(value('queue:'+thread,'[]'))[0]['draft']['text']=='LIFE_QUEUED_'+token
 assert subprocess.run(d.adb+['shell','run-as','dev.codex.go','test','-e',folder+'/'+forbidden]).returncode!=0
 print('Lifecycle: abrupt main-process death restored draft/history and paused queue without replay',flush=True)
 d.shell('am','force-stop','dev.codex.go');before=len(requests);open_app();wait_ready();assert_draft(draft);assert len(requests)==before
 d.shell('input','keyevent','KEYCODE_HOME');before=len(requests)
 subprocess.run(d.adb+['reboot'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL);booted();reverse();open_app();wait_ready();assert_draft(draft)
 assert value('project')==project and value('thread')==thread and counts['LIFE_QUEUED']==0 and len(requests)==before
 assert d.shell('run-as','dev.codex.go','cat',folder+'/'+filename)=='LIFE_FILE'
 assert json.loads(d.shell('run-as','dev.codex.go','cat','files/codex/auth.json'))['OPENAI_API_KEY']=='sk-local-lifecycle-'+token
 print('Lifecycle: device reboot restored project, credentials, draft, native file and paused queue',flush=True)
 send('LIFE_AFTER_REBOOT');complete('LIFE_AFTER_REBOOT_DONE');assert counts['LIFE_AFTER_REBOOT']==1 and counts['LIFE_QUEUED']==0
 result=dict(version=d.version(),realNativeCodexRuntime=True,localResponsesOnly=True,homeAndBackReopen=True,completedNativeFileSurvives=True,abruptProcessDeathRecovery=True,forceStopReopen=True,deviceRebootReopen=True,projectThreadAndDraftRestored=True,fakeCredentialPreserved=True,queuePausedWithoutReplay=True,unconfirmedToolNeverExecuted=True,newTaskAfterRebootWorks=True,requestCounts=dict(counts),externalInference=False,realAccountLogin=False)
 (out/'result.json').write_text(json.dumps(result,indent=2)+'\n');d.capture(out/'after-reboot.png');print('PASS: native task/file flow; Home, Back, kill, force-stop, reboot and explicit new task',flush=True)
finally:
 release.set();(out/'requests.json').write_text(json.dumps(requests,indent=2)+'\n')
 d.shell('am','force-stop','dev.codex.go');subprocess.run(d.adb+['reverse','--remove',f'tcp:{port}'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL);server.shutdown()
