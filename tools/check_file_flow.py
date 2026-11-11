#!/usr/bin/env python3
"""Emulator-only file round trip and native Codex steering/queue integration.
Uses a local deterministic Responses fixture, never external inference.
"""
import argparse
import base64
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import threading
import time
import struct
import zlib
from android_driver import AndroidDriver

parser = argparse.ArgumentParser()
parser.add_argument('--serial', default='emulator-5560')
args = parser.parse_args()
driver = AndroidDriver(args.serial)
artifacts = Path('.gradle/validation/reports/file-flow'); artifacts.mkdir(parents=True, exist_ok=True)
(artifacts/'result.json').unlink(missing_ok=True)
requests = []
release = threading.Event()
interrupted = threading.Event()
def chunk(kind, data):
    return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data))
png = b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',320,180,8,2,0,0,0))+chunk(b'IDAT',zlib.compress((b'\0'+b'\x40\x89\xcf'*320)*180))+chunk(b'IEND',b'')

def user_text(request):
    messages = [m for m in request.get('input', []) if m.get('role') == 'user']
    return '\n'.join(c.get('text','') for c in messages[-1].get('content', []) if isinstance(c,dict)) if messages else ''

def redact(value):
    if isinstance(value,str) and value.startswith('data:image/'): return '<image omitted>'
    if isinstance(value,dict): return {k:redact(v) for k,v in value.items()}
    if isinstance(value,list): return [redact(v) for v in value]
    return value

class Fixture(BaseHTTPRequestHandler):
    def log_message(self, *_): pass
    def do_GET(self):
        body=b'{"data":[]}'
        self.send_response(200); self.send_header('Content-Length',str(len(body))); self.end_headers(); self.wfile.write(body)
    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        requests.append(request)
        number = len(requests)
        text = user_text(request)
        events=[{'type':'response.created','response':{'id':f'resp_flow_{number}'}}]
        function = None
        wait = None
        if 'FLOW_FILES' in text:
            if not any(m.get('type')=='function_call_output' for m in request.get('input', [])):
                csv_path = next(path for path in re.findall(r'\]\(sandbox:([^)]+)\)', text) if path.endswith('.csv'))
                code = f'''import csv, pathlib, subprocess, ssl, sqlite3
rows=list(csv.DictReader(open({csv_path!r})))
assert len(rows)==2
total=sum(int(row['amount']) for row in rows)
pathlib.Path('/workspace/codex-flow-report.csv').write_text('total\\n'+str(total)+'\\n')
pathlib.Path('/workspace/codex-flow-preview.png').write_bytes({png!r})
assert subprocess.check_output(['node','-p','20+22'],text=True).strip()=='42'
print('NODE_OK')
print('NPM_OK',subprocess.check_output(['npm','--version'],text=True).strip())
print('PIP_OK',subprocess.check_output(['python3','-m','pip','--version'],text=True).strip())
repo=pathlib.Path('/workspace/codex-flow-git');repo.mkdir(exist_ok=True)
git_init=subprocess.run(['git','init',str(repo)],capture_output=True,text=True)
print(git_init.stdout,git_init.stderr,flush=True)
git_init.check_returncode()
(repo/'hello.txt').write_text('hello')
subprocess.run(['git','-C',str(repo),'add','hello.txt'],check=True)
assert 'hello.txt' in subprocess.check_output(['git','-C',str(repo),'diff','--cached','--stat'],text=True)
print('PYTHON_GIT_OK',total)
'''
                function = dict(type='function_call',id=f'fc_flow_{number}',call_id=f'call_flow_{number}',name='exec_command',arguments=json.dumps(dict(cmd='python3 -c '+shlex.quote(code),yield_time_ms=10000,max_output_tokens=2000)))
            reply='FILE_FLOW_OK\n\n[报告](sandbox:/workspace/codex-flow-report.csv)\n\n[图片](sandbox:/workspace/codex-flow-preview.png)'
        elif 'FLOW_NEW' in text: reply='INTERRUPT_OK'
        elif 'FLOW_INTERRUPT' in text: reply='WORKING_INTERRUPT'; wait=interrupted
        elif 'FLOW_QUEUE' in text: reply='QUEUE_OK'
        elif 'ADD_STEER' in text: reply='STEER_OK'; wait=release
        elif 'FLOW_STEER' in text: reply='WORKING_STEER'; wait=release
        else: reply='UNEXPECTED_FLOW'
        if function: events.append(dict(type='response.output_item.done',item=function))
        else:
            mid=f'msg_flow_{number}'
            events += [dict(type='response.output_item.added',item=dict(id=mid,type='message',role='assistant',content=[])),dict(type='response.output_text.delta',delta=reply)]
        self.send_response(200); self.send_header('Content-Type','text/event-stream'); self.end_headers()
        try:
            for event in events:
                self.wfile.write(('event: '+event['type']+'\ndata: '+json.dumps(event)+'\n\n').encode()); self.wfile.flush()
            if wait: wait.wait(180)
            if not function:
                event=dict(type='response.output_item.done',item=dict(id=mid,type='message',role='assistant',content=[dict(type='output_text',text=reply)]))
                self.wfile.write(('event: '+event['type']+'\ndata: '+json.dumps(event)+'\n\n').encode())
            event=dict(type='response.completed',response=dict(id=f'resp_flow_{number}',usage=dict(input_tokens=0,output_tokens=0,total_tokens=0)))
            self.wfile.write(('event: '+event['type']+'\ndata: '+json.dumps(event)+'\n\n').encode()); self.wfile.flush()
        except (BrokenPipeError,ConnectionResetError): pass
        self.close_connection=True

def set_text(text):
    driver.set_text(text)
    if 'mInputShown=true' in driver.shell('dumpsys','input_method'):driver.shell('input','keyevent','4')

def import_file(name):
    driver.click('添加与工具'); driver.click('添加文件'); driver.find('Show roots')
    until=time.monotonic()+25
    while time.monotonic()<until:
        tree=driver.xml()
        roots=next((n for n in tree.iter('node') if n.get('resource-id','').endswith('/roots_list')),None)
        if roots is not None:
            download=next(n for n in roots.iter('node') if n.get('text')=='Downloads')
            driver.tap(download)
            continue
        breadcrumb=next((n.get('text') for n in tree.iter('node') if n.get('resource-id','').endswith('/breadcrumb_text')),None)
        if breadcrumb!='Downloads':
            driver.click('Show roots')
            continue
        file=next((n for n in tree.iter('node') if n.get('resource-id')=='android:id/title' and n.get('text')==name),None)
        if file is None: continue
        print('picker selects',name,file.get('bounds'),flush=True)
        driver.tap(file)
        try: driver.find('移除附件 '+name,timeout=3);return
        except AssertionError:
            current=driver.xml()
            if not any(n.get('content-desc')=='Show roots' for n in current.iter('node')): raise
    driver.capture(artifacts/'picker-failed.png')
    raise AssertionError('Document picker did not select '+name)

def complete(label):
    until=time.monotonic()+90
    while time.monotonic()<until:
        labels=[n.get('text','') for n in driver.xml().iter('node')]
        if '允许本次' in labels: driver.click('允许本次')
        if any(label in item for item in labels): return
    raise AssertionError('No result: '+label)

server=ThreadingHTTPServer(('127.0.0.1',0),Fixture)
port=server.server_port
threading.Thread(target=server.serve_forever,daemon=True).start()
original=subprocess.run(driver.adb+['shell','run-as','dev.codex.go','cat','files/codex/config.toml'],capture_output=True).stdout
animations=driver.disable_animations()
try:
    subprocess.run(driver.adb+['reverse',f'tcp:{port}',f'tcp:{port}'],check=True,stdout=subprocess.DEVNULL)
    driver.shell('run-as','dev.codex.go','mkdir','-p','files/codex')
    driver.private_write('files/codex/config.toml',f'''model="codex-go-test"
model_provider="codex_go_test"
[analytics]
enabled=false
[model_providers.codex_go_test]
name="Local file flow fixture"
base_url="http://127.0.0.1:{port}/v1"
wire_api="responses"
requires_openai_auth=false
supports_websockets=false
'''.encode())
    driver.shell('pm','grant','dev.codex.go','android.permission.POST_NOTIFICATIONS')
    with tempfile.TemporaryDirectory() as temporary:
        csv=Path(temporary)/'codex-go-flow.csv';csv.write_text('item,amount\na,10\nb,32\n')
        picture=Path(temporary)/'codex-go-flow.png';picture.write_bytes(png)
        for file in [csv,picture]: subprocess.run(driver.adb+['push',str(file),'/sdcard/Download/'+file.name],check=True,stdout=subprocess.DEVNULL)
    driver.start();driver.click('打开侧栏');driver.click('本机环境');driver.click('启动本机 Codex');driver.find('本机 Codex 已就绪',timeout=80)
    driver.click('返回对话');driver.click('新建对话')
    for node in list(driver.xml().iter('node')):
        if node.get('content-desc','').startswith('移除附件 '): driver.click(node.get('content-desc'))
    driver.shell('rm','-f','/sdcard/Download/codex-flow-report.csv')
    import_file('codex-go-flow.csv');import_file('codex-go-flow.png')
    driver.capture(artifacts/'attachments.png')
    driver.click('移除附件 codex-go-flow.png');import_file('codex-go-flow.png')
    set_text('FLOW_FILES');driver.click('发送消息');complete('FILE_FLOW_OK')
    assert 'data:image/' in json.dumps(requests[0]), 'Native Codex never forwarded the image'
    output='\n'.join(str(m.get('output','')) for m in requests[1].get('input',[]) if m.get('type')=='function_call_output')
    for marker in ['NODE_OK','NPM_OK','PIP_OK','PYTHON_GIT_OK']: assert marker in output, marker
    driver.capture(artifacts/'deliverables.png')
    driver.click('codex-flow-report.csv');driver.find('total\n42');driver.capture(artifacts/'preview.png')
    driver.click('导出');driver.click('SAVE');driver.find('关闭预览')
    assert driver.shell('cat','/sdcard/Download/codex-flow-report.csv').strip()=='total\n42'
    driver.click('分享')
    assert 'ChooserActivity' in driver.shell('dumpsys','activity','activities')
    driver.capture(artifacts/'share.png');driver.shell('input','keyevent','4');driver.click('关闭预览')
    set_text('FLOW_STEER');driver.click('发送消息');driver.find('WORKING_STEER')
    set_text('ADD_STEER');driver.click('插话')
    set_text('FLOW_WITHDRAW');driver.click('插话 · 选择发送方式 ▾');driver.click('排队，本轮结束后发送');driver.click('撤回')
    assert 'FLOW_WITHDRAW' in driver.find(classname='android.widget.EditText').get('text','')
    set_text('FLOW_QUEUE');driver.click('插话 · 选择发送方式 ▾');driver.click('排队，本轮结束后发送')
    driver.capture(artifacts/'queue.png');release.set();complete('QUEUE_OK')
    assert any('ADD_STEER' in user_text(request) for request in requests), 'Steering never reached the model fixture'
    set_text('FLOW_INTERRUPT');driver.click('发送消息');driver.find('WORKING_INTERRUPT')
    set_text('FLOW_NEW');driver.click('插话 · 选择发送方式 ▾');driver.click('中断并发送');complete('INTERRUPT_OK');interrupted.set()
    driver.capture(artifacts/'interrupt.png')
    result=dict(importViaSystemPicker=True,imageForwardedByNativeCodex=True,attachmentRemoval=True,python=True,node=True,npm=True,pip=True,gitDiff=True,filePreview=True,exportedBytesMatch=True,systemShare=True,steering=True,queueAndWithdraw=True,interruptAndSend=True,externalInference=False)
    (artifacts/'result.json').write_text(json.dumps(result,indent=2)+'\n')
    print('PASS: file import, image input, Python/Node/npm/pip/Git, preview/export/share, steering, queue, withdrawal and interrupt-and-send')
finally:
    release.set();interrupted.set()
    (artifacts/'requests.json').write_text(json.dumps(redact(requests),ensure_ascii=False,indent=2))
    driver.capture(artifacts/'last-screen.png')
    driver.shell('am','force-stop','dev.codex.go')
    if original: driver.private_write('files/codex/config.toml',original)
    else: driver.shell('run-as','dev.codex.go','rm','-f','files/codex/config.toml')
    subprocess.run(driver.adb+['reverse','--remove',f'tcp:{port}'],stdout=subprocess.DEVNULL)
    driver.restore_animations(animations)
    server.shutdown();server.server_close()
