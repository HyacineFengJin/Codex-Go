#!/usr/bin/env python3
"""Break and recover the real native engine config on an owned disposable emulator."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import re
from pathlib import Path
import subprocess
import threading
import time
import xml.etree.ElementTree as ET
from android_driver import AndroidDriver

parser=argparse.ArgumentParser(); parser.add_argument('--serial',default='emulator-5560'); args=parser.parse_args()
d=AndroidDriver(args.serial); out=Path('.gradle/validation/reports/configuration'); out.mkdir(parents=True,exist_ok=True)
(out/'result.json').unlink(missing_ok=True)
class Fixture(BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def do_GET(self):
        data=b'{"data":[]}'
        self.send_response(200); self.send_header('Content-Length',str(len(data))); self.end_headers(); self.wfile.write(data)
    def do_POST(self):
        self.rfile.read(int(self.headers['Content-Length']))
        self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
        for event in [dict(type='response.created',response=dict(id='config_history')),
                      dict(type='response.output_item.done',item=dict(type='message',id='config_keep',role='assistant',content=[dict(type='output_text',text='CONFIG_HISTORY_SAVED')])),
                      dict(type='response.completed',response=dict(id='config_history',usage=dict(input_tokens=0,output_tokens=0,total_tokens=0)))]:
            self.wfile.write(('event: '+event['type']+'\ndata: '+json.dumps(event)+'\n\n').encode());self.wfile.flush()
        self.close_connection=True
server=ThreadingHTTPServer(('127.0.0.1',0),Fixture); threading.Thread(target=server.serve_forever,daemon=True).start(); port=server.server_port

def read(path):
    result=subprocess.run(d.adb+['shell','run-as','dev.codex.go','cat',path],capture_output=True)
    return result.stdout if result.returncode==0 else None

def write(path, data):
    subprocess.run(d.adb+['shell','run-as','dev.codex.go','sh','-c',"'cat > "+path+"'"],input=data,check=True)

def scroll(top=False):
    d.shell('input','swipe','530','750' if top else '1970','530','1970' if top else '650','350');time.sleep(.2)
def button(label):d.scroll_click(label)
def top():
    for _ in range(9): scroll(True)
def ready():
    top(); d.find('本机 Codex 已就绪',timeout=70)
def start():
    d.start(); d.click('打开侧栏'); d.click('本机环境'); d.click('启动 Codex')
def recover(label, succeeds=True):
    for _ in range(3):
        button(label)
        try: confirm=d.find('备份并恢复',timeout=3);break
        except AssertionError:continue
    else:raise AssertionError('Configuration confirmation unavailable: '+label)
    d.tap(confirm)
    if succeeds: ready()
    else: top(); d.find('启动失败',timeout=50)
    print(label+': '+('ready' if succeeds else 'failed as expected'),flush=True)

config='files/codex/config.toml'; auth='files/codex/auth.json'; prefs='shared_prefs/codex_go.xml'
original={p:read(p) for p in [config,auth,prefs]}
existing=subprocess.run(d.adb+['shell','run-as','dev.codex.go','find','files/config-recovery','-type','f'],capture_output=True,text=True).stdout.splitlines()
recovery_backup={p:read(p) for p in existing}
animations=d.disable_animations()
try:
    subprocess.run(d.adb+['reverse',f'tcp:{port}',f'tcp:{port}'],check=True,stdout=subprocess.DEVNULL)
    d.shell('am','force-stop','dev.codex.go')
    values=ET.fromstring(original[prefs] or b'<map/>')
    for node in list(values):
        if node.get('name') in ['thread','model','draft:new','queue:new','retry:new']: values.remove(node)
    draft=json.dumps({'text':'CONFIG_DRAFT_MUST_SURVIVE','attachments':[]})
    ET.SubElement(values,'string',name='draft:new').text=draft
    write(prefs,ET.tostring(values,encoding='utf-8',xml_declaration=True))
    fixture=f'''model="codex-go-test"
model_provider="codex_go_test"
cli_auth_credentials_store="file"
[analytics]
enabled=false
[model_providers.codex_go_test]
name="Local configuration fixture"
base_url="http://127.0.0.1:{port}/v1"
wire_api="responses"
requires_openai_auth=false
supports_websockets=false
'''.encode()
    credential=b'{"OPENAI_API_KEY":"sk-local-configuration-fixture-not-real"}'
    write(config,fixture); write(auth,credential)
    d.private_write('files/workspace/configuration-keep.txt',b'KEEP_WORKSPACE')
    # Create a real native transcript even when this check starts from clean app data.
    start();ready();d.click('返回对话');d.click('新建对话');d.set_text('CONFIG_HISTORY_SENTINEL');d.click('发送消息');d.find('CONFIG_HISTORY_SAVED',timeout=35)
    d.click('新建对话');d.shell('am','force-stop','dev.codex.go')
    values=ET.fromstring(read(prefs))
    for node in list(values):
        if node.get('name') in ['thread','draft:new','queue:new','retry:new']:values.remove(node)
    ET.SubElement(values,'string',name='draft:new').text=draft
    write(prefs,ET.tostring(values,encoding='utf-8',xml_declaration=True))
    # Existing native history must survive every recovery unchanged.
    history=d.shell('run-as','dev.codex.go','sh','-c',"'find files/codex/sessions -type f | head -5'").splitlines()
    history_bytes={p:read(p) for p in history}
    start(); ready(); assert read('files/config-recovery/good.toml')==fixture
    d.shell('am','force-stop','dev.codex.go')
    broken=b'# original malformed config\r\nmodel = [\n'
    write(config,broken)
    start(); d.find('启动失败',timeout=50); d.capture(out/'invalid-config.png')
    recover('恢复上次启动配置')
    assert read(config)==fixture
    pointer=read('files/config-recovery/previous').decode()
    assert read('files/config-recovery/'+pointer)==broken
    d.capture(out/'last-good.png')
    recover('恢复前一份配置',False); assert read(config)==broken
    recover('恢复默认配置'); defaults=read(config); assert b'cli_auth_credentials_store = "file"' in defaults
    recover('恢复前一份配置',False); assert read(config)==broken
    recover('恢复上次启动配置'); assert read(config)==defaults
    assert read(auth)==credential
    assert read('files/workspace/configuration-keep.txt')==b'KEEP_WORKSPACE'
    assert history and all(read(p)==data for p,data in history_bytes.items())
    values=ET.fromstring(read(prefs)); assert next(n.text for n in values if n.get('name')=='draft:new')==draft
    d.capture(out/'defaults-ready.png')
    result=dict(version=d.version(),malformedConfigPreventsNativeStartup=True,lastSuccessfulConfigRestartsNativeEngine=True,exactOriginalBytesBackedUp=True,previousConfigUndoRestoresInvalidBytes=True,defaultsRecoverNativeEngine=True,secondFailureRecoverable=True,credentialHistoryDraftWorkspacePreserved=True,externalInference=False,realAccountLogin=False)
    (out/'result.json').write_text(json.dumps(result,indent=2)+'\n'); print('PASS: configuration recovery',flush=True)
finally:
    d.capture(out/'last-screen.png'); d.shell('am','force-stop','dev.codex.go')
    for path,data in original.items():
        if data is None:d.shell('run-as','dev.codex.go','rm','-f',path)
        else:write(path,data)
    d.shell('run-as','dev.codex.go','rm','-rf','files/config-recovery')
    d.shell('run-as','dev.codex.go','mkdir','-p','files/config-recovery')
    for path,data in recovery_backup.items():write(path,data)
    d.shell('run-as','dev.codex.go','rm','-f','files/workspace/configuration-keep.txt')
    d.restore_animations(animations)
    subprocess.run(d.adb+['reverse','--remove',f'tcp:{port}'],stdout=subprocess.DEVNULL); server.shutdown()
