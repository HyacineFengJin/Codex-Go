#!/usr/bin/env python3
"""Owned emulator only: stable project cwd, native files/Git, drafts, shares and recovery."""
import argparse,json,re,shlex,subprocess,threading,time,uuid
from pathlib import Path
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import xml.etree.ElementTree as ET
from android_driver import AndroidDriver
parser=argparse.ArgumentParser();parser.add_argument('--serial',default='emulator-5560');args=parser.parse_args()
d=AndroidDriver(args.serial);out=Path('.gradle/validation/reports/project-flow');out.mkdir(parents=True,exist_ok=True);(out/'result.json').unlink(missing_ok=True)
token=uuid.uuid4().hex[:8];aname='Alpha_'+token;bname='Beta_'+token;renamed='AlphaRenamed_'+token
filename='project-output-'+token+'.txt';ids={};requests=[];release=threading.Event()

def read(path):
 p=subprocess.run(d.adb+['shell','run-as','dev.codex.go','cat',path],capture_output=True);return p.stdout if p.returncode==0 else None
def write(path,data):subprocess.run(d.adb+['shell','run-as','dev.codex.go','sh','-c',"'cat > "+path+"'"],input=data,check=True)
def prefs():return ET.fromstring(read('shared_prefs/codex_go.xml'))
def value(key,default=None):return next((n.text for n in prefs() if n.get('name')==key),default)
def draft(slot):return json.loads(value('draft:'+slot,'{}'))
def inbox():return json.loads(value('shareInbox','[]'))
def project_list():return json.loads(value('projects','[]'))
def patch(entries):
 root=prefs()
 for n in list(root):
  if n.get('name') in entries:root.remove(n)
 for key,val in entries.items():
  if val is not None:ET.SubElement(root,'string',name=key).text=val
 write('shared_prefs/codex_go.xml',ET.tostring(root,encoding='utf-8',xml_declaration=True))
def labels():return [n.get('text','') or n.get('content-desc','') for n in d.xml().iter('node')]
def text_input(text):
 node=d.find(classname='android.widget.EditText');d.tap(node);d.shell('input','keyevent','KEYCODE_MOVE_END',*(['KEYCODE_DEL']*(len(node.get('text',''))+2)))
 d.shell('input','text',text)
def scroll_click(label):
 for _ in range(3):d.shell('input','swipe','530','700','530','1900','200')
 for _ in range(8):
  node=next((n for n in d.xml().iter('node') if n.get('text')==label or n.get('content-desc')==label),None)
  if node is not None:d.tap(d.find(label));return
  d.shell('input','swipe','530','1900','530','700','220')
 raise AssertionError('Missing project control: '+label)
def projects():
 if '返回对话' in labels():d.click('返回对话')
 d.click('更多');d.click('项目');d.find('你的项目')
def create(name):
 projects();scroll_click('创建项目');text_input(name);d.click('创建并进入');d.find('更多')
 return next(p['id'] for p in project_list() if p['name']==name)
def choose(name):
 projects();scroll_click('进入项目 '+name);d.find('更多')
 if '进入此对话' in labels():d.click('进入此对话');d.find('更多')
def open_history(title):
 d.click('打开侧栏');d.find(title,timeout=25);d.click(title);d.find('更多')
def start_engine():
 d.click('打开侧栏');d.click('本机环境');d.click('启动本机 Codex');d.find('本机 Codex 已就绪',timeout=80);d.click('返回对话')
def send_share(text,kind='one'):
 d.shell('am','start','-n','dev.codex.go.sharefixture/dev.codex.go.testdriver.ShareSourceActivity','--es','kind',kind,'--es','text',text)
 d.find('来自其他 App 的分享',timeout=25)
def complete(marker):
 end=time.monotonic()+70
 while time.monotonic()<end:
  visible=labels()
  if '允许本次' in visible:d.click('允许本次')
  if marker in visible:return
 raise AssertionError('Native project fixture missing: '+marker)
def latest_user(request):
 for item in reversed(request.get('input',[])):
  if item.get('role')=='user':return '\n'.join(c.get('text','') for c in item.get('content',[]) if isinstance(c,dict))
 return ''
class Fixture(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_GET(self):
  body=b'{"data":[]}';self.send_response(200);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
 def do_POST(self):
  request=json.loads(self.rfile.read(int(self.headers['Content-Length'])));requests.append(request)
  kind='A' if 'PROJECT_ALPHA' in latest_user(request) else 'B'
  cwd='/projects/'+ids[kind];proof='PROJECT_NATIVE_'+kind
  outputs=[i for i in request.get('input',[]) if i.get('type')=='function_call_output']
  has_proof=any(proof in str(i.get('output','')) for i in outputs)
  events=[dict(type='response.created',response=dict(id='project_'+str(len(requests))))]
  if not has_proof:
   attached=re.findall(r'\]\(sandbox:([^)]+)\)',latest_user(request))
   code=f'''import os,pathlib,subprocess,urllib.parse
assert os.getcwd()=={cwd!r},os.getcwd()
assert subprocess.run(['git','init','-q','.']).returncode==0
assert subprocess.check_output(['git','rev-parse','--show-toplevel'],text=True).strip()=={cwd!r}
pathlib.Path({filename!r}).write_text({(kind+'_CONTENT'+chr(10)+cwd)!r})
for raw in {attached!r}:
 assert pathlib.Path(urllib.parse.unquote(raw)).read_text()=='independent-source-one'
print({proof!r},{cwd!r},flush=True)
'''
   call=dict(type='function_call',id='project_fc_'+str(len(requests)),call_id='project_call_'+str(len(requests)),name='exec_command',arguments=json.dumps(dict(cmd='python3 -c '+shlex.quote(code),yield_time_ms=10000,max_output_tokens=1500)))
   events.append(dict(type='response.output_item.done',item=call));hold=False
  else:
   mid='project_message_'+str(len(requests));reply=('PROJECT_ALPHA_DONE' if kind=='A' else 'PROJECT_BETA_WORKING')+f'\n\n[{filename}](sandbox:{cwd}/{filename})'
   events += [dict(type='response.output_item.added',item=dict(id=mid,type='message',role='assistant',content=[])),dict(type='response.output_text.delta',delta=reply)];hold=kind=='B'
  self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
  def event(value):self.wfile.write(('event: '+value['type']+'\ndata: '+json.dumps(value)+'\n\n').encode());self.wfile.flush()
  try:
   for e in events:event(e)
   if hold:release.wait(180)
   if has_proof:event(dict(type='response.output_item.done',item=dict(id=mid,type='message',role='assistant',content=[dict(type='output_text',text=reply)])))
   event(dict(type='response.completed',response=dict(id='project_'+str(len(requests)),usage=dict(input_tokens=0,output_tokens=0,total_tokens=0))))
  except (BrokenPipeError,ConnectionResetError):pass
  self.close_connection=True
server=ThreadingHTTPServer(('127.0.0.1',0),Fixture);threading.Thread(target=server.serve_forever,daemon=True).start();port=server.server_port
original={p:read(p) for p in ['shared_prefs/codex_go.xml','files/codex/config.toml','files/workspace/'+filename]}
backup_recovery={p:read(p) for p in subprocess.run(d.adb+['shell','run-as','dev.codex.go','find','files/config-recovery','-type','f'],capture_output=True,text=True).stdout.splitlines()}
animations=d.disable_animations();created=[]
try:
 subprocess.run(d.adb+['reverse',f'tcp:{port}',f'tcp:{port}'],check=True,stdout=subprocess.DEVNULL)
 d.shell('am','force-stop','dev.codex.go');patch({'project':'default','shareInbox':'[]'})
 fixture=f'''model="codex-go-test"
model_provider="codex_go_test"
cli_auth_credentials_store="file"
[analytics]
enabled=false
[model_providers.codex_go_test]
name="Local project fixture"
base_url="http://127.0.0.1:{port}/v1"
wire_api="responses"
requires_openai_auth=false
supports_websockets=false
'''.encode();write('files/codex/config.toml',fixture)
 write('files/workspace/'+filename,b'LEGACY_KEEP')
 legacy_drafts={n.get('name'):n.text for n in prefs() if n.get('name','').startswith(('draft:','queue:','retry:'))}
 old_thread=value('thread')
 d.start();start_engine()
 if old_thread:assert value('thread')==old_thread
 print('Legacy native startup and selected history retained',flush=True)
 ids['A']=create(aname);created.append(ids['A']);text_input('ALPHA_UNSENT')
 ids['B']=create(bname);created.append(ids['B']);assert not d.find(classname='android.widget.EditText').get('text','')
 text_input('BETA_UNSENT');choose(aname)
 print('Independent project new drafts retained',flush=True)
 assert draft('new@'+ids['A'])['text']=='ALPHA_UNSENT';assert d.find(classname='android.widget.EditText').get('text')=='ALPHA_UNSENT'
 projects();scroll_click('重命名项目 '+aname);text_input(renamed);d.click('保存项目名称');d.click('返回对话')
 assert value('project')==ids['A'];assert next(p for p in project_list() if p['id']==ids['A'])['name']==renamed
 send_share('A_SHARED');entry=inbox()[0];staged=entry['draft']['attachments'][0]
 assert read('files/share-staging/'+staged['path'].removeprefix('/workspace/'))==b'independent-source-one'
 d.click('加入当前草稿');a_draft=draft('new@'+ids['A']);assert a_draft['text']=='ALPHA_UNSENT\n\nA_SHARED'
 assert a_draft['attachments'][0]['path'].startswith('/projects/'+ids['A']+'/imports/')
 text_input('PROJECT_ALPHA_'+token);d.click('发送消息');complete('PROJECT_ALPHA_DONE');assert not any('](sandbox:/projects/' in label for label in labels());a_thread=value('thread');assert a_thread
 assert value('draft:new@'+ids['A']) is None
 assert read('files/projects/'+ids['A']+'/'+filename)==('A_CONTENT\n/projects/'+ids['A']).encode()
 print('Alpha actual native cwd/Git/file and project attachment verified',flush=True)
 d.capture(out/'alpha-native.png');d.click(filename);d.find('A_CONTENT\n/projects/'+ids['A']);d.capture(out/'alpha-preview.png')
 d.click('导出');d.click('SAVE');d.find('关闭预览');assert d.shell('cat','/sdcard/Download/'+filename)=='A_CONTENT\n/projects/'+ids['A']
 d.click('分享');d.find('Sharing 1 file');d.capture(out/'alpha-system-share.png');assert 'ChooserActivity' in d.shell('dumpsys','activity','activities');d.shell('input','keyevent','4');d.click('关闭预览')
 text_input('ALPHA_SESSION_DRAFT');choose(bname);assert d.find(classname='android.widget.EditText').get('text')=='BETA_UNSENT'
 text_input('PROJECT_BETA_'+token);d.click('发送消息');complete('PROJECT_BETA_WORKING');b_thread=value('thread');assert b_thread!=a_thread
 assert read('files/projects/'+ids['B']+'/'+filename)==('B_CONTENT\n/projects/'+ids['B']).encode()
 print('Beta actual native cwd/Git/file verified',flush=True)
 projects();scroll_click('进入项目 '+renamed);assert value('project')==ids['B'] and value('thread')==b_thread
 d.find('回到任务');d.capture(out/'busy-project-browse.png');d.click('回到任务')
 before=len(requests);queue=value('queue:'+b_thread,'[]');send_share('B_BUSY_SHARE');d.click('加入当前草稿')
 assert draft(b_thread)['attachments'][0]['path'].startswith('/projects/'+ids['B']+'/imports/')
 assert len(requests)==before and value('queue:'+b_thread,'[]')==queue
 release.set();d.find('发送消息',timeout=35)
 choose(renamed);d.click('打开侧栏')
 until=time.monotonic()+20
 while time.monotonic()<until:
  visible=labels();a_title=next((label for label in visible if label.startswith('PROJECT_ALPHA_'+token)),None)
  if a_title:break
 assert a_title and not any(label.startswith('PROJECT_BETA_'+token) for label in visible),visible
 d.click(a_title);d.find('PROJECT_ALPHA_DONE');assert d.find(classname='android.widget.EditText').get('text')=='ALPHA_SESSION_DRAFT'
 choose(bname);open_history('PROJECT_BETA_'+token);d.find('PROJECT_BETA_WORKING');assert draft(b_thread)['text']=='B_BUSY_SHARE'
 d.start();start_engine();assert value('project')==ids['B'] and value('thread')==b_thread
 assert d.find(classname='android.widget.EditText').get('text')=='B_BUSY_SHARE'
 print('Scoped history, busy share guard and project restart verified',flush=True)
 # Inject the exact durable state after move/before draft commit, then recover via UI.
 send_share('INTERRUPTED_SHARE');entry=inbox()[0];batch=entry['draft']['attachments'][0]['path'].split('/')[3]
 d.shell('am','force-stop','dev.codex.go')
 route=json.dumps(dict(slot='new@'+ids['A'],project=ids['A']))
 patch({'shareRoute:'+entry['id']:route,'project':ids['B'],'thread':None})
 d.shell('run-as','dev.codex.go','mkdir','-p','files/projects/'+ids['A']+'/imports')
 d.shell('run-as','dev.codex.go','mv','files/share-staging/imports/'+batch,'files/projects/'+ids['A']+'/imports/'+batch)
 d.start();d.shell('am','start','-a','OPEN_SHARES','-n','dev.codex.go/.MainActivity');d.find('来自其他 App 的分享')
 d.click('加入当前草稿');d.find('这条分享上次已分配到其他目标，请回到原项目和草稿继续处理');assert len(inbox())==1
 d.click('回到上次分享目标');assert value('project')==ids['A'];d.click('加入当前草稿');assert not inbox()
 assert draft('new@'+ids['A'])['attachments'][0]['path'].startswith('/projects/'+ids['A']+'/imports/')
 assert read('files/workspace/'+filename)==b'LEGACY_KEEP'
 assert all(value(k)==v for k,v in legacy_drafts.items())
 assert any('PROJECT_NATIVE_A' in str(i.get('output','')) for req in requests for i in req.get('input',[]) if i.get('type')=='function_call_output')
 assert any('PROJECT_NATIVE_B' in str(i.get('output','')) for req in requests for i in req.get('input',[]) if i.get('type')=='function_call_output')
 result=dict(version=d.version(),legacyWorkspaceHistoryDraftsPreserved=True,perProjectNewDrafts=True,renameKeepsStableDirectory=True,confirmedSharesUseProjectPaths=True,nativeProjectCwdAndGit=True,sameNamedNativeOutputsStaySeparate=True,projectPreviewExportShare=True,historyFilteredByNativeCwd=True,perThreadDraftsPreserved=True,busyProjectBrowsingKeepsExecutionOwner=True,busyShareDoesNotSendOrQueue=True,selectedProjectThreadAndDraftSurviveRestart=True,injectedInterruptedShareDestinationRecovered=True,externalInference=False,realAccountLogin=False)
 (out/'result.json').write_text(json.dumps(result,indent=2)+'\n');print('PASS: native projects, Git/cwd, scoped files/drafts/history/sharing, restart and interrupted share route recovery',flush=True)
finally:
 release.set();d.capture(out/'last-screen.png');d.shell('am','force-stop','dev.codex.go')
 for p,data in original.items():
  if data is None:d.shell('run-as','dev.codex.go','rm','-f',p)
  else:write(p,data)
 for project in created:d.shell('run-as','dev.codex.go','rm','-rf','files/projects/'+project)
 d.shell('run-as','dev.codex.go','rm','-rf','files/config-recovery','files/share-staging');d.shell('run-as','dev.codex.go','mkdir','-p','files/config-recovery')
 for p,data in backup_recovery.items():write(p,data)
 d.shell('am','force-stop','dev.codex.go.sharefixture');d.restore_animations(animations)
 subprocess.run(d.adb+['reverse','--remove',f'tcp:{port}'],stdout=subprocess.DEVNULL);server.shutdown()
