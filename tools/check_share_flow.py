#!/usr/bin/env python3
"""Inbound Android sharing from an independent provider, through the native engine."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import subprocess
import threading
import time
import xml.etree.ElementTree as ET
from android_driver import AndroidDriver

parser=argparse.ArgumentParser(); parser.add_argument('--serial',default='emulator-5560'); args=parser.parse_args()
d=AndroidDriver(args.serial); out=Path('.gradle/validation/reports/share-flow'); out.mkdir(parents=True,exist_ok=True); (out/'result.json').unlink(missing_ok=True)
requests=[]; release=threading.Event()
class Fixture(BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def do_GET(self):
        data=b'{"data":[]}'; self.send_response(200); self.send_header('Content-Length',str(len(data))); self.end_headers(); self.wfile.write(data)
    def do_POST(self):
        request=json.loads(self.rfile.read(int(self.headers['Content-Length']))); requests.append(request); number=len(requests)
        self.send_response(200); self.send_header('Content-Type','text/event-stream'); self.end_headers()
        def event(value):
            self.wfile.write(('event: '+value['type']+'\ndata: '+json.dumps(value)+'\n\n').encode()); self.wfile.flush()
        try:
            event(dict(type='response.created',response=dict(id=f'share_{number}')))
            mid=f'share_message_{number}'; reply='SHARE_BUSY' if number==1 else 'SHARED_IMAGE_OK'
            event(dict(type='response.output_item.added',item=dict(id=mid,type='message',role='assistant',content=[])))
            event(dict(type='response.output_text.delta',delta=reply))
            if number==1:release.wait(180)
            event(dict(type='response.output_item.done',item=dict(id=mid,type='message',role='assistant',content=[dict(type='output_text',text=reply)])))
            event(dict(type='response.completed',response=dict(id=f'share_{number}',usage=dict(input_tokens=0,output_tokens=0,total_tokens=0))))
        except (BrokenPipeError,ConnectionResetError):pass
        self.close_connection=True
server=ThreadingHTTPServer(('127.0.0.1',0),Fixture); threading.Thread(target=server.serve_forever,daemon=True).start(); port=server.server_port

def read(path):
    p=subprocess.run(d.adb+['shell','run-as','dev.codex.go','cat',path],capture_output=True); return p.stdout if p.returncode==0 else None
def write(path,data):subprocess.run(d.adb+['shell','run-as','dev.codex.go','sh','-c',"'cat > "+path+"'"],input=data,check=True)
def prefs():return ET.fromstring(read('shared_prefs/codex_go.xml'))
def value(key,default='{}'):return next((n.text for n in prefs() if n.get('name')==key),default)
def inbox():return json.loads(value('shareInbox','[]'))
def draft(owner='new'):return json.loads(value('draft:'+owner))
def launch_shares():d.shell('am','start','-a','OPEN_SHARES','-n','dev.codex.go/.MainActivity'); d.find('分享收件箱')
def dispatch(kind='text',text=None,chooser=False,clip=False):
    argv=['am','start','-n','dev.codex.go.sharefixture/dev.codex.go.testdriver.ShareSourceActivity','--es','kind',kind]
    if text is not None:argv+=['--es','text',text]
    if chooser:argv+=['--es','chooser','true']
    if clip:argv+=['--es','clipOnly','true']
    result=d.shell(*argv);assert 'Error:' not in result,result

def send(kind='text',text=None,chooser=False,clip=False):
    dispatch(kind,text,chooser,clip)
    if chooser:
        d.find('Codex Go')
        target=next(p for p in d.xml().iter('node') if any(n.get('text')=='Codex Go' for n in p))
        d.tap(target)
    d.find('分享收件箱',timeout=25)
def set_pref(entries):
    values=prefs()
    for node in list(values):
        if node.get('name') in entries:values.remove(node)
    for key,item in entries.items():
        if item is not None:ET.SubElement(values,'string',name=key).text=item
    write('shared_prefs/codex_go.xml',ET.tostring(values,encoding='utf-8',xml_declaration=True))
def set_text(text):
    d.set_text(text)
    if 'mInputShown=true' in d.shell('dumpsys','input_method'):d.shell('input','keyevent','4')

original={p:read(p) for p in ['shared_prefs/codex_go.xml','files/codex/config.toml','files/codex/auth.json']}
def imports():return set(subprocess.run(d.adb+['shell','run-as','dev.codex.go','ls','files/workspace/imports'],capture_output=True,text=True).stdout.splitlines())
old_imports=imports()
recovery_paths=subprocess.run(d.adb+['shell','run-as','dev.codex.go','find','files/config-recovery','-type','f'],capture_output=True,text=True).stdout.splitlines()
recovery_backup={p:read(p) for p in recovery_paths}
animations=d.disable_animations()
try:
    d.shell('am','force-stop','dev.codex.go')
    set_pref({'thread':None,'draft:new':json.dumps(dict(text='UNFINISHED',attachments=[])),'retry:new':None,'queue:new':'[]','shareInbox':'[]','shareReceived':None})
    # Cold share enters the system chooser; existing draft stays unchanged until explicit apply.
    send(text='https://example.test/share',chooser=True)
    assert draft()['text']=='UNFINISHED' and len(inbox())==1
    print('PASS: system chooser and cold text/link draft preserved',flush=True)
    batch_id=inbox()[0]['id'];d.capture(out/'text-review.png')
    d.start();launch_shares();assert inbox()[0]['id']==batch_id
    d.click('加入当前草稿');d.find('UNFINISHED\n\nhttps://example.test/share')
    assert draft()['text']=='UNFINISHED\n\nhttps://example.test/share' and not inbox()
    rotation=d.shell('settings','get','system','user_rotation').strip();accelerometer=d.shell('settings','get','system','accelerometer_rotation').strip()
    try:
        d.shell('settings','put','system','accelerometer_rotation','0');d.shell('settings','put','system','user_rotation','1');d.find('更多');d.shell('settings','put','system','user_rotation','0');d.find('更多')
    finally:
        for key,item in [('user_rotation',rotation),('accelerometer_rotation',accelerometer)]:
            if item=='null':d.shell('settings','delete','system',key)
            else:d.shell('settings','put','system',key,item)
    launch_shares();d.find('在其他 App 中分享至 Codex Go')
    # URI grants, duplicate names, mixed image/text files and dedup of EXTRA_STREAM+ClipData.
    send('multiple','FILES_PENDING')
    entries=inbox();assert len(entries)==1 and len(entries[0]['draft']['attachments'])==3
    files=entries[0]['draft']['attachments'];assert files[0]['name']==files[1]['name'] and files[0]['path']!=files[1]['path']
    for index,file in enumerate(files[:2]):assert read('files/share-staging/'+file['path'].removeprefix('/workspace/'))==('independent-source-'+['one','two'][index]).encode()
    assert all(read('files/workspace/'+file['path'].removeprefix('/workspace/')) is None for file in files)
    d.tap(next(n for n in d.xml().iter('node') if 'same-name.txt' in n.get('text','')));d.find('independent-source-one');d.click('关闭预览')
    print('PASS: unconfirmed URI copies private, with duplicate names and mixed image/files',flush=True)
    d.start();launch_shares();assert inbox()[0]['draft']['attachments']==files
    d.click('加入新对话草稿');assert len(draft()['attachments'])==3 and 'UNFINISHED' in draft()['text'] and not inbox()
    send('one','CLIP_ONLY',clip=True);d.click('加入当前草稿');assert len(draft()['attachments'])==4
    # Provider-reported length is only metadata: copy and retain the actual file bytes.
    before=draft();send('oversized');assert draft()==before and len(inbox())==1
    reported=inbox()[0]['draft']['attachments'][0]
    assert read('files/share-staging/'+reported['path'].removeprefix('/workspace/'))==b'independent-source-oversized'
    d.click('加入当前草稿');assert not inbox() and len(draft()['attachments'])==len(before['attachments'])+1
    # A draft with eight existing files accepts three more without truncation or a quota.
    send('multiple','OVERFLOW_PENDING');pending=inbox()[0]
    d.shell('am','force-stop','dev.codex.go')
    full=dict(text='FULL_DRAFT',attachments=[dict(files[0],path='/workspace/share-limit-'+str(i)+'.txt') for i in range(8)])
    for i in range(8):d.private_write('files/workspace/share-limit-'+str(i)+'.txt',b'keep')
    set_pref({'draft:new':json.dumps(full)})
    d.start();launch_shares();d.click('加入当前草稿');d.find('FULL_DRAFT\n\nOVERFLOW_PENDING')
    assert len(draft()['attachments'])==11 and draft()['attachments'][:8]==full['attachments'] and not inbox()
    # Prepare a native task; incoming shares must never steer, queue or start a turn automatically.
    d.shell('am','force-stop','dev.codex.go');set_pref({'draft:new':json.dumps(dict(text='SHARE_WORKING',attachments=[])),'shareInbox':'[]'})
    fixture=f'''model="codex-go-test"
model_provider="codex_go_test"
cli_auth_credentials_store="file"
[analytics]
enabled=false
[model_providers.codex_go_test]
name="Local share fixture"
base_url="http://127.0.0.1:{port}/v1"
wire_api="responses"
requires_openai_auth=false
supports_websockets=false
'''.encode();write('files/codex/config.toml',fixture)
    subprocess.run(d.adb+['reverse',f'tcp:{port}',f'tcp:{port}'],check=True,stdout=subprocess.DEVNULL)
    d.start();d.click('打开侧栏');d.click('本机环境');d.click('启动 Codex');d.find('本机 Codex 已就绪',timeout=70);d.click('返回对话');d.click('发送消息');d.find('SHARE_BUSY',timeout=35)
    owner=value('thread',None);assert owner and len(requests)==1
    queue=value('queue:'+owner,'[]');retry=value('retry:'+owner)
    send('multiple','BUSY_SHARE');d.capture(out/'busy-review.png')
    d.click('加入新对话草稿');assert len(inbox())==1 and value('thread')==owner
    d.click('加入当前草稿');assert len(draft(owner)['attachments'])==3 and draft(owner)['text']=='BUSY_SHARE'
    assert len(requests)==1 and value('queue:'+owner,'[]')==queue and value('retry:'+owner)==retry
    release.set();d.find('发送消息',timeout=30)
    d.capture(out/'busy-draft-preserved.png')
    d.click('发送消息');d.find('SHARED_IMAGE_OK',timeout=30)
    assert len(requests)==2
    assert any(c.get('type')=='input_image' and c.get('image_url','').startswith('data:image/') for item in requests[-1].get('input',[]) for c in item.get('content',[]) if isinstance(c,dict))
    result=dict(version=d.version(),systemChooserTarget=True,appliedShareRotationKeepsConversation=True,unconfirmedFilesOutsideNativeWorkspace=True,coldTextLinkShare=True,existingDraftPreservedUntilConfirmation=True,processRestartKeepsInbox=True,applyRemovesInboxExactlyOnce=True,multipleContentUriGrants=True,privateFilePreview=True,duplicateNamesKeepDistinctBytes=True,streamClipDataDeduplicated=True,clipOnlySupported=True,newDraftMergePreservesExisting=True,providerSizeMetadataDoesNotRejectActualFile=True,moreThanEightAttachmentsMergeWithoutLoss=True,busyShareNeverSteersQueuesOrSends=True,disabledNewChatCannotSwitchBusyTask=True,confirmedImageForwardedByNativeCodex=True,externalInference=False,realAccountLogin=False)
    (out/'result.json').write_text(json.dumps(result,indent=2)+'\n');print('PASS: inbound sharing and native image handoff',flush=True)
finally:
    release.set();d.capture(out/'last-screen.png');d.shell('am','force-stop','dev.codex.go')
    for path,data in original.items():
        if data is None:d.shell('run-as','dev.codex.go','rm','-f',path)
        else:write(path,data)
    current=imports()
    for directory in current-old_imports:d.shell('run-as','dev.codex.go','rm','-rf','files/workspace/imports/'+directory)
    for i in range(8):d.shell('run-as','dev.codex.go','rm','-f','files/workspace/share-limit-'+str(i)+'.txt')
    d.shell('run-as','dev.codex.go','rm','-rf','files/share-staging','files/config-recovery')
    d.shell('run-as','dev.codex.go','mkdir','-p','files/config-recovery')
    for path,data in recovery_backup.items():write(path,data)
    d.shell('am','force-stop','dev.codex.go.sharefixture')
    d.restore_animations(animations);subprocess.run(d.adb+['reverse','--remove',f'tcp:{port}'],stdout=subprocess.DEVNULL);server.shutdown()
