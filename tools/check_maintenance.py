#!/usr/bin/env python3
"""Exercise recovery and native thread management on a disposable emulator.
Uses only a localhost model fixture and a deliberately fake API key.
"""
import argparse
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import subprocess
import threading
import time
import uuid
from android_driver import AndroidDriver

parser = argparse.ArgumentParser()
parser.add_argument('--serial', default='emulator-5560')
args = parser.parse_args()
driver = AndroidDriver(args.serial)
artifacts = Path('.gradle/validation/reports/maintenance'); artifacts.mkdir(parents=True, exist_ok=True)
(artifacts / 'result.json').unlink(missing_ok=True)
token = uuid.uuid4().hex[:8]
prompt = 'SeedHistory_' + token
far_title = 'FarNeedle_' + token
managed_title = 'Managed_' + token
draft = 'SavedDraft_' + token
fake_key = 'sk-local-fixture-not-a-real-key-' + token
requests = []
# Clone the native rollout only inside the disposable emulator. No desktop account is read.
seed = '''import json, os, pathlib, uuid, datetime
home = pathlib.Path('/root/.codex')
sources = [p for p in (home/'sessions').rglob('rollout-*.jsonl') if PROMPT in p.read_text()]
source = max(sources, key=lambda p:p.stat().st_mtime)
original = source.read_text()
original_id = json.loads(original.splitlines()[0])['payload']['id']
ids = []
for index in range(55):
    thread_id = str(uuid.uuid4())
    date = datetime.datetime.now(datetime.timezone.utc)-datetime.timedelta(days=7, minutes=index)
    stamp = date.isoformat(timespec='milliseconds').replace('+00:00','Z')
    title = FAR if index == 54 else 'Seed_%02d_TOKEN' % index
    lines=[]
    for line in original.replace(original_id, thread_id).replace(PROMPT, title).splitlines():
        value=json.loads(line); value['timestamp']=stamp
        if value.get('type')=='session_meta': value['payload']['timestamp']=stamp
        lines.append(json.dumps(value))
    directory=home/'sessions'/date.strftime('%Y/%m/%d'); directory.mkdir(parents=True,exist_ok=True)
    target=directory/('rollout-'+date.strftime('%Y-%m-%dT%H-%M-%S')+'-'+thread_id+'.jsonl')
    target.write_text('\\n'.join(lines)+'\\n'); os.utime(target,(date.timestamp(),date.timestamp()))
    ids.append(thread_id)
pathlib.Path('/workspace/maintenance-seeds.json').write_text(json.dumps({'ids':ids,'original':original_id}))
pathlib.Path('/workspace/maintenance-keep.txt').write_text('WORKSPACE_MUST_SURVIVE_TOKEN')
print('SEEDED_55_THREADS_TOKEN')
'''.replace('PROMPT', repr(prompt)).replace('FAR', repr(far_title)).replace('TOKEN', token)
seed_command = "python3 - <<'CODEX_GO_MAINTENANCE'\n" + seed + '\nCODEX_GO_MAINTENANCE'

class Fixture(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_GET(self):
        data=b'{"data":[]}'
        self.send_response(200); self.send_header('Content-Length',str(len(data))); self.end_headers(); self.wfile.write(data)
    def do_POST(self):
        payload=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        requests.append(payload)
        number=len(requests)
        response_id='resp_maintenance_'+str(number)
        events=[{'type':'response.created','response':{'id':response_id}}]
        if number==1:
            events.append({'type':'response.output_item.done','item':{'type':'function_call','id':'fc_seed','call_id':'call_seed','name':'exec_command','arguments':json.dumps({'cmd':seed_command,'max_output_tokens':1000})}})
        else:
            item={'id':'msg_'+str(number),'type':'message','role':'assistant','content':[{'type':'output_text','text':'MAINTENANCE_OK_'+token}]}
            events.append({'type':'response.output_item.done','item':item})
        events.append({'type':'response.completed','response':{'id':response_id,'usage':{'input_tokens':0,'output_tokens':0,'total_tokens':0}}})
        self.send_response(200); self.send_header('Content-Type','text/event-stream'); self.end_headers()
        for event in events:
            self.wfile.write(('event: '+event['type']+'\ndata: '+json.dumps(event)+'\n\n').encode()); self.wfile.flush()
        self.close_connection=True

server=ThreadingHTTPServer(('127.0.0.1',0),Fixture)
threading.Thread(target=server.serve_forever,daemon=True).start()
port=server.server_port
original_config=subprocess.run(driver.adb+['shell','run-as','dev.codex.go','cat','files/codex/config.toml'],capture_output=True).stdout
original_auth=subprocess.run(driver.adb+['shell','run-as','dev.codex.go','cat','files/codex/auth.json'],capture_output=True).stdout
animations=driver.disable_animations()
original_os=None

def labels(): return [n.get('text','') for n in driver.xml().iter('node')]
def click_scroll(label):driver.scroll_click(label)
def text_input(value, select=False):
    driver.set_text(value)
def settings():
    driver.click('打开侧栏'); driver.click('本机环境')
def hide_keyboard():
    if re.search(r'\bmInputShown=true\b',driver.shell('dumpsys','input_method')):driver.shell('input','keyevent','4')
def wait_search(title, minimum=56):
    until=time.monotonic()+60
    while time.monotonic()<until:
        nodes=list(driver.xml().iter('node'))
        # The search field itself contains the query; it is not a result row.
        result=any(n.get('text')==title and n.get('class')!='android.widget.EditText' for n in nodes)
        counts=[re.search(r'个结果 · 已检查 (\d+) 条',n.get('text','')) for n in nodes]
        scanned=next((int(m[1]) for m in counts if m),0)
        if result and scanned>=minimum: return scanned
    raise AssertionError('Search did not finish with a matching result row')
def wait_reply():
    until=time.monotonic()+100
    while time.monotonic()<until:
        visible=labels()
        if '允许本次' in visible: driver.click('允许本次')
        if 'MAINTENANCE_OK_'+token in visible: return
    raise AssertionError('Native fixture task did not finish')

try:
    subprocess.run(driver.adb+['reverse',f'tcp:{port}',f'tcp:{port}'],check=True,stdout=subprocess.DEVNULL)
    config=f'''model = "codex-go-test"
model_provider = "codex_go_test"
cli_auth_credentials_store = "file"
[analytics]
enabled = false
[model_providers.codex_go_test]
name = "Local maintenance fixture"
base_url = "http://127.0.0.1:{port}/v1"
wire_api = "responses"
requires_openai_auth = true
supports_websockets = false
'''
    driver.shell('am','force-stop','dev.codex.go')
    driver.shell('run-as','dev.codex.go','mkdir','-p','files/codex')
    driver.private_write('files/codex/config.toml',config.encode())
    driver.private_write('files/codex/auth.json',json.dumps({'OPENAI_API_KEY':fake_key}).encode())
    driver.shell('pm','grant','dev.codex.go','android.permission.POST_NOTIFICATIONS')
    driver.start(); settings(); driver.click('启动 Codex'); driver.find('本机 Codex 已就绪',timeout=90)
    driver.find('已登录 apiKey')
    driver.capture(artifacts/'ready.png'); print('Ready with isolated fake account',flush=True)
    driver.click('返回对话'); driver.click('新建对话'); text_input(prompt); driver.click('发送消息'); wait_reply(); hide_keyboard()
    saved=json.loads(driver.shell('run-as','dev.codex.go','cat','files/workspace/maintenance-seeds.json'))
    assert len(saved['ids'])==55
    assert any('SEEDED_55_THREADS_'+token in str(item.get('output','')) for item in requests[-1].get('input',[]) if item.get('type')=='function_call_output')
    driver.click('打开侧栏'); text_input(far_title)
    scanned=wait_search(far_title)
    assert 'mInputShown=true' in driver.shell('dumpsys','input_method')
    assert '模型与思考' not in labels(),'Navigation crowds out search results with the keyboard open'
    row=next(n for n in driver.xml().iter('node') if n.get('text')==far_title and n.get('class')!='android.widget.EditText')
    height=int(re.findall(r'(\d+)x(\d+)',driver.shell('wm','size'))[-1][1])
    assert int(re.findall(r'\d+',row.get('bounds'))[-1])<height*.6,'Search result is covered by the keyboard'
    driver.capture(artifacts/'search-with-keyboard.png'); hide_keyboard()
    driver.capture(artifacts/'search.png'); print('Search scanned '+str(scanned)+' threads',flush=True)
    # Replace a query before its debounce completes; an older result must not overwrite it.
    text_input('NoSuchHistory_'+token,select=True)
    text_input(far_title,select=True)
    wait_search(far_title); hide_keyboard()
    driver.click('对话选项：'+far_title); driver.click('重命名')
    renamed='RenamedFar_'+token
    text_input(renamed,select=True); driver.click('保存名称')
    driver.find(renamed,timeout=30); print('Native rename passed',flush=True)
    driver.click('清除搜索')
    driver.find(prompt,timeout=30); driver.click(prompt)
    driver.find('MAINTENANCE_OK_'+token,timeout=30)
    text_input(draft); hide_keyboard()
    driver.click('更多'); driver.click('整理当前对话'); driver.click('重命名')
    text_input(managed_title,select=True); driver.click('保存名称'); driver.find(managed_title)
    driver.click('更多'); driver.click('整理当前对话'); driver.click('归档对话'); driver.click('确认归档')
    driver.find('想做什么？',timeout=30)
    driver.click('打开侧栏'); driver.click('已归档'); driver.find(managed_title,timeout=30)
    driver.capture(artifacts/'archived.png')
    driver.click(managed_title); driver.click('恢复并打开')
    driver.find('MAINTENANCE_OK_'+token,timeout=45)
    assert driver.find(classname='android.widget.EditText').get('text')==draft
    driver.capture(artifacts/'restored.png'); print('Native archive/restore and draft passed',flush=True)
    # Damage a replaceable guest file, then verify a forced reinstall restores it and all durable data survives.
    original_os=driver.shell('run-as','dev.codex.go','cat','files/environment/etc/os-release')
    driver.private_write('files/environment/etc/os-release',b'DAMAGED_FOR_MAINTENANCE_TEST\n')
    settings()
    for attempt in range(3):
        click_scroll('修复运行环境')
        if '保留数据并修复' in labels(): break
    else: raise AssertionError('Repair confirmation unavailable after stable clicks')
    driver.capture(artifacts/'repair-confirm.png'); driver.click('保留数据并修复')
    # The page scroll remains where the user left it; return to the top to observe readiness.
    for _ in range(4): driver.shell('input','swipe','530','700','530','1970','300')
    driver.find('本机 Codex 已就绪',timeout=100)
    assert driver.shell('run-as','dev.codex.go','cat','files/environment/etc/os-release')==original_os
    assert driver.shell('run-as','dev.codex.go','cat','files/workspace/maintenance-keep.txt')=='WORKSPACE_MUST_SURVIVE_'+token
    assert fake_key in driver.shell('run-as','dev.codex.go','cat','files/codex/auth.json')
    driver.find('已登录 apiKey')
    driver.click('返回对话'); driver.find('MAINTENANCE_OK_'+token,timeout=30)
    assert driver.find(classname='android.widget.EditText').get('text')==draft
    print('Forced repair and data preservation passed',flush=True)
    # Use the system document picker, then inspect the actual exported bytes.
    settings(); click_scroll('导出诊断'); driver.find('SAVE',timeout=20)
    export_name='maintenance-diagnostics-'+token+'.json'
    editors=[n for n in driver.xml().iter('node') if n.get('class')=='android.widget.EditText']
    assert editors, 'Save dialog has no filename input'
    driver.tap(editors[0]); driver.shell('input','keyevent','KEYCODE_MOVE_END',*(['KEYCODE_DEL']*(len(editors[0].get('text',''))+2))); driver.shell('input','text',export_name)
    driver.click('SAVE'); driver.find('诊断已导出',timeout=20)
    report=json.loads(driver.shell('cat','/sdcard/Download/'+export_name))
    serialized=json.dumps(report)
    for secret in [fake_key,prompt,managed_title,far_title,draft,'/workspace','127.0.0.1','base_url']:
        assert secret not in serialized, 'Private data in diagnostic export'
    assert report['environment']['matchesBundle'] is True
    assert any(event['event']=='Repair' for event in report['events'])
    (artifacts/'diagnostic.json').write_text(json.dumps(report,indent=2,ensure_ascii=False))
    # The only account here is the test's deliberately fake API key; no real sign-in occurs.
    for _ in range(4): driver.shell('input','swipe','530','700','530','1970','300')
    for _ in range(3):
        click_scroll('退出 ChatGPT')
        try:confirm=driver.find('退出账户',timeout=3);break
        except AssertionError:continue
    else:raise AssertionError('Logout confirmation unavailable')
    driver.tap(confirm);driver.find('使用设备码登录',timeout=30)
    auth=subprocess.run(driver.adb+['shell','run-as','dev.codex.go','cat','files/codex/auth.json'],capture_output=True).stdout
    assert fake_key.encode() not in auth
    driver.capture(artifacts/'logged-out.png')
    driver.click('返回对话'); driver.find('MAINTENANCE_OK_'+token,timeout=30)
    assert driver.find(classname='android.widget.EditText').get('text')==draft
    assert driver.shell('run-as','dev.codex.go','cat','files/workspace/maintenance-keep.txt')=='WORKSPACE_MUST_SURVIVE_'+token
    result={'version':driver.version(),'nativeEngine':True,'historySearchAcrossPages':True,'searchResultsVisibleWithKeyboard':True,'drawerNavigationAfterSearch':True,'scannedThreads':scanned,'staleSearchIgnored':True,'nativeRename':True,'nativeArchiveAndRestore':True,'perThreadDraftSurvivesArchive':True,'forcedEnvironmentRepair':True,'repairPreservesAccountHistoryDraftWorkspace':True,'systemDiagnosticExport':True,'diagnosticsExcludePrivateData':True,'nativeLogoutRemovesCredential':True,'logoutPreservesHistoryDraftWorkspace':True,'externalInference':False,'realAccountLogin':False}
    (artifacts/'result.json').write_text(json.dumps(result,indent=2)+'\n')
    print('PASS: native history pagination/search, rename/archive/restore, forced repair with preserved data, private diagnostic export, and logout with fake credentials')
finally:
    driver.capture(artifacts/'last-screen.png')
    driver.shell('am','force-stop','dev.codex.go')
    if original_config: driver.private_write('files/codex/config.toml',original_config)
    else: driver.shell('run-as','dev.codex.go','rm','-f','files/codex/config.toml')
    if original_auth: driver.private_write('files/codex/auth.json',original_auth)
    else: driver.shell('run-as','dev.codex.go','rm','-f','files/codex/auth.json')
    if original_os is not None:driver.private_write('files/environment/etc/os-release',original_os.encode())
    driver.restore_animations(animations)
    subprocess.run(driver.adb+['reverse','--remove',f'tcp:{port}'],stdout=subprocess.DEVNULL)
    server.shutdown()
