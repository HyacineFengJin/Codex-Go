#!/usr/bin/env python3
"""Native Android Codex: reasoning options, failures/retry, offline drafts and real patch approvals.
Only disposable emulators, local Responses events and a deliberately fake API key are used.
"""
import argparse
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import subprocess
import threading
import time
import uuid
import xml.etree.ElementTree as ET
from android_driver import AndroidDriver

parser=argparse.ArgumentParser();parser.add_argument('--serial',default='emulator-5560');args=parser.parse_args()
driver=AndroidDriver(args.serial)
out=Path('.gradle/validation/reports/recovery-flow');out.mkdir(parents=True,exist_ok=True);(out/'result.json').unlink(missing_ok=True)
token=uuid.uuid4().hex[:8]
requests=[]; counts=Counter(); auth_release=threading.Event();auth_fixed=threading.Event();network_fixed=threading.Event()
patch_name='approval-'+token+'.txt';large_name='approval-large-'+token+'.txt'

def latest_user(request):
    for item in reversed(request.get('input',[])):
        if item.get('role')=='user':
            content=item.get('content',[])
            return content if isinstance(content,str) else '\n'.join(v.get('text','') for v in content if isinstance(v,dict))
    return ''
class Fixture(BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def do_GET(self):
        body=b'{"data":[]}'
        self.send_response(200);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
    def do_POST(self):
        request=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        text=latest_user(request)
        kind=next((k for k in ['REC_AUTH','REC_NET','REC_PATCH_REJECT','REC_PATCH_ACCEPT','REC_PATCH_LARGE','REC_OFFLINE','REC_MODEL','REC_QUEUED'] if k in text),'UNKNOWN')
        requests.append({'kind':kind,'model':request.get('model'),'reasoning':request.get('reasoning'),'input':request.get('input',[])})
        counts[kind]+=1
        if kind=='REC_AUTH' and not auth_fixed.is_set():
            auth_release.wait(90)
            body=b'{"error":{"message":"fixture authentication failed","type":"invalid_request_error","code":"invalid_api_key"}}'
            self.send_response(401);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body);return
        response_id='resp_recovery_'+str(len(requests))
        events=[{'type':'response.created','response':{'id':response_id}}]
        if kind=='REC_NET' and not network_fixed.is_set():
            self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
            self.wfile.write(('event: response.created\ndata: '+json.dumps(events[0])+'\n\n').encode());self.wfile.flush();self.close_connection=True;return
        call_id='call_'+kind+'_'+token
        has_output=any(item.get('type') in ['custom_tool_call_output','function_call_output'] and item.get('call_id')==call_id for item in request.get('input',[]))
        if kind.startswith('REC_PATCH') and not has_output:
            content=('OVERSIZED_LINE_'+token+'\n')*4000 if kind=='REC_PATCH_LARGE' else ('APPROVED_'+token+'\n' if kind=='REC_PATCH_ACCEPT' else 'MUST_BE_REJECTED_'+token+'\n')
            path=large_name if kind=='REC_PATCH_LARGE' else patch_name
            patch='*** Begin Patch\n*** Add File: /workspace/'+path+'\n'+''.join('+'+line+'\n' for line in content.splitlines())+'*** End Patch'
            events.append({'type':'response.output_item.done','item':{'type':'custom_tool_call','id':'fc_'+kind,'call_id':call_id,'name':'apply_patch','input':patch}})
        else:
            reply=kind+'_OK'
            events.append({'type':'response.output_item.done','item':{'type':'message','id':'msg_'+str(len(requests)),'role':'assistant','content':[{'type':'output_text','text':reply}]}})
        events.append({'type':'response.completed','response':{'id':response_id,'usage':{'input_tokens':0,'output_tokens':0,'total_tokens':0}}})
        self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
        try:
            for event in events:self.wfile.write(('event: '+event['type']+'\ndata: '+json.dumps(event)+'\n\n').encode());self.wfile.flush()
        except (BrokenPipeError,ConnectionResetError):pass
        self.close_connection=True

server=ThreadingHTTPServer(('127.0.0.1',0),Fixture);port=server.server_port
threading.Thread(target=server.serve_forever,daemon=True).start()
original_config=subprocess.run(driver.adb+['shell','run-as','dev.codex.go','cat','files/codex/config.toml'],capture_output=True).stdout
original_auth=subprocess.run(driver.adb+['shell','run-as','dev.codex.go','cat','files/codex/auth.json'],capture_output=True).stdout
animations=driver.disable_animations()
airplane=driver.shell('settings','get','global','airplane_mode_on').strip()
wifi=driver.shell('settings','get','global','wifi_on').strip()
mobile=driver.shell('settings','get','global','mobile_data').strip()

def labels():return [n.get('text','') for n in driver.xml().iter('node')]
def replace_text(value):
    driver.set_text(value)
    if 'mInputShown=true' in driver.shell('dumpsys','input_method'):driver.shell('input','keyevent','4')

def settings():driver.click('打开侧栏');driver.click('本机环境')
def complete(label):driver.find(label,timeout=75)
def wait_not_busy():
    until=time.monotonic()+20
    while time.monotonic()<until:
        if not any(n.get('content-desc') in ['停止任务','插话'] for n in driver.xml().iter('node')):return
    raise AssertionError('Native turn did not finish')
def send(kind):replace_text(kind+'_'+token);driver.click('发送消息')
def scroll_click(label):driver.scroll_click(label)

try:
    subprocess.run(driver.adb+['reverse',f'tcp:{port}',f'tcp:{port}'],check=True,stdout=subprocess.DEVNULL)
    config=f'''model = "codex-go-test"
model_provider = "codex_go_test"
cli_auth_credentials_store = "file"
[analytics]
enabled = false
[model_providers.codex_go_test]
name = "Local recovery fixture"
base_url = "http://127.0.0.1:{port}/v1"
wire_api = "responses"
requires_openai_auth = true
supports_websockets = false
request_max_retries = 0
stream_max_retries = 0
stream_idle_timeout_ms = 3000
'''
    driver.shell('am','force-stop','dev.codex.go');driver.private_write('files/codex/config.toml',config.encode());driver.private_write('files/codex/auth.json',json.dumps({'OPENAI_API_KEY':'sk-local-recovery-fixture-'+token}).encode())
    driver.start();settings();driver.click('启动 Codex');driver.find('本机 Codex 已就绪',timeout=90);driver.click('返回对话');driver.click('新建对话')
    driver.click('选择模型');driver.find('选择模型')
    model_ids=[n.get('text','') for n in driver.xml().iter('node') if re.fullmatch(r'[a-z][a-z0-9._-]{2,}',n.get('text','')) and '-' in n.get('text','')]
    assert model_ids,'Native model IDs are not discoverable in the model sheet'
    searched_model=model_ids[0];driver.set_text(searched_model)
    if 'mInputShown=true' in driver.shell('dumpsys','input_method'):driver.shell('input','keyevent','4')
    radios=[n for n in driver.xml().iter('node') if n.get('class')=='android.widget.RadioButton']
    assert len(radios)>=2,'No model catalog options in picker'
    driver.tap(radios[1]);scroll_click('高');driver.capture(out/'model-options.png');scroll_click('完成')
    send('REC_MODEL');complete('REC_MODEL_OK');wait_not_busy()
    assert requests[-1]['reasoning']['effort']=='high', 'Chosen effort never reached actual native Responses request'
    assert requests[-1]['model']==searched_model,'Searched model selection never reached the native request'
    selected_model=requests[-1]['model'];print('Native model and reasoning verified:',selected_model,flush=True)
    send('REC_AUTH');driver.find('停止任务');driver.click('选择模型');driver.find('选择模型')
    driver.find('任务结束后可调整');driver.click('引擎默认')
    saved_preferences=ET.fromstring(driver.shell('run-as','dev.codex.go','cat','shared_prefs/codex_go.xml'))
    assert next(n.text for n in saved_preferences if n.get('name')=='model')==selected_model,'Running task allowed a model change'
    driver.click('完成');replace_text('REC_QUEUED_'+token);driver.click('发送方式 ▾');driver.click('排队，本轮结束后发送')
    auth_release.set();complete('账户验证未通过');wait_not_busy();driver.capture(out/'authentication.png')
    assert counts['REC_QUEUED']==0,'Failed turn silently continued its queue'
    replace_text('KEEP_NEW_DRAFT_'+token);driver.click('前往账户')
    for _ in range(3):
        scroll_click('刷新账户状态')
        try:driver.find('已刷新账户与模型信息，可以检查后重试。',timeout=6);break
        except AssertionError:continue
    else:raise AssertionError('Account refresh did not complete')
    driver.click('返回对话')
    auth_fixed.set();driver.click('重试上一条消息');complete('REC_AUTH_OK');wait_not_busy()
    assert driver.find(classname='android.widget.EditText').get('text')=='KEEP_NEW_DRAFT_'+token
    assert counts['REC_QUEUED']==0
    print('401 recovery and retained next draft/paused queue verified',flush=True)
    send('REC_NET');complete('连接暂时中断');wait_not_busy();driver.capture(out/'network-failure.png')
    replace_text('DRAFT_AFTER_RESTART_'+token);driver.start();settings();driver.click('启动 Codex');driver.find('本机 Codex 已就绪',timeout=90);driver.click('返回对话');driver.find('上次任务状态待确认')
    assert driver.find(classname='android.widget.EditText').get('text')=='DRAFT_AFTER_RESTART_'+token
    network_fixed.set();driver.click('重试上一条消息');complete('REC_NET_OK');wait_not_busy()
    assert driver.find(classname='android.widget.EditText').get('text')=='DRAFT_AFTER_RESTART_'+token
    assert requests[-1]['model']==selected_model and requests[-1]['reasoning']['effort']=='high'
    print('Stream disconnect, durable retry and restored model options verified',flush=True)
    # No automatic submission when network comes back; all network changes are emulator-only.
    for cycle in range(2):
        driver.shell('svc','wifi','disable');driver.shell('svc','data','disable');driver.shell('cmd','connectivity','airplane-mode','enable')
        complete('设备未联网 · 草稿已保留，联网后可手动继续');before=len(requests);replace_text('REC_OFFLINE_'+token+'_'+str(cycle));driver.click('发送消息')
        assert driver.find(classname='android.widget.EditText').get('text')=='REC_OFFLINE_'+token+'_'+str(cycle)
        assert len(requests)==before
        driver.shell('cmd','connectivity','airplane-mode','disable');driver.shell('svc','wifi','enable');driver.shell('svc','data','enable')
        until=time.monotonic()+30
        while time.monotonic()<until:
            if '设备未联网 · 草稿已保留，联网后可手动继续' not in labels():break
        assert len(requests)==before,'Network return automatically submitted a draft'
        driver.click('发送消息');complete('REC_OFFLINE_OK');wait_not_busy();assert len(requests)==before+1;driver.capture(out/'network-restored.png')
    # Actual native apply_patch generates item/fileChange/requestApproval.
    send('REC_PATCH_REJECT');driver.find('允许修改文件？',timeout=40)
    tree=driver.xml();text='\n'.join(n.get('text','') for n in tree.iter('node'))
    assert patch_name in text and 'MUST_BE_REJECTED_'+token in text
    driver.capture(out/'patch-reject.png');driver.click('拒绝');complete('REC_PATCH_REJECT_OK');wait_not_busy()
    assert subprocess.run(driver.adb+['shell','run-as','dev.codex.go','test','-e','files/workspace/'+patch_name]).returncode!=0
    send('REC_PATCH_ACCEPT');driver.find('允许修改文件？',timeout=40);driver.capture(out/'patch-accept.png');driver.click('允许本次');complete('REC_PATCH_ACCEPT_OK');wait_not_busy()
    assert driver.shell('run-as','dev.codex.go','cat','files/workspace/'+patch_name)=='APPROVED_'+token+'\n'
    send('REC_PATCH_LARGE');driver.find('允许修改文件？',timeout=40)
    for _ in range(12):
        full=next((n for n in driver.xml().iter('node') if n.get('text')=='查看全文'),None)
        if full is not None:driver.tap(full);break
        driver.shell('input','swipe','540','1650','540','850','250')
    else:raise AssertionError('Complete diff reader unavailable')
    driver.find('全文');driver.click('末页');driver.capture(out/'patch-large-full.png');driver.click('关闭')
    driver.click('允许本次');complete('REC_PATCH_LARGE_OK');wait_not_busy()
    assert driver.shell('run-as','dev.codex.go','cat','files/workspace/'+large_name)==('OVERSIZED_LINE_'+token+'\n')*4000
    assert counts['REC_QUEUED']==0
    driver.shell('input','swipe','540','800','540','1500','500');driver.find('回到最新回复');driver.click('回到最新回复');driver.find('REC_PATCH_LARGE_OK')
    result={'version':driver.version(),'nativeModelSearch':True,'busyModelSelectionLocked':True,'nativeModelAndEffort':True,'restoredModelAndEffort':True,'nativeUnauthorizedRecovery':True,'failedQueuePaused':True,'retryPreservesDifferentDraft':True,'nativeStreamDisconnectRecovery':True,'retrySurvivesAppRestart':True,'offlineDraftPreserved':True,'networkReturnNeedsExplicitSend':True,'nativeFileChangeApproval':True,'fileChangeDeclinePreventsWrite':True,'fileChangeAcceptWritesExpectedBytes':True,'largeCompleteDiffReviewAndApproval':True,'lateFileCardKeepsLatestReplyVisible':True,'manualHistoryScrollPreserved':True,'externalInference':False,'realAccountLogin':False}
    (out/'result.json').write_text(json.dumps(result,indent=2)+'\n');print('PASS: native reasoning, authentication/network recovery, offline draft retention and actual file-change approvals',flush=True)
finally:
    auth_release.set();driver.capture(out/'last-screen.png');driver.shell('am','force-stop','dev.codex.go')
    if original_config:driver.private_write('files/codex/config.toml',original_config)
    else:driver.shell('run-as','dev.codex.go','rm','-f','files/codex/config.toml')
    if original_auth:driver.private_write('files/codex/auth.json',original_auth)
    else:driver.shell('run-as','dev.codex.go','rm','-f','files/codex/auth.json')
    driver.shell('cmd','connectivity','airplane-mode','enable' if airplane=='1' else 'disable')
    driver.shell('svc','wifi','enable' if wifi=='1' else 'disable');driver.shell('svc','data','enable' if mobile=='1' else 'disable')
    driver.restore_animations(animations)
    subprocess.run(driver.adb+['reverse','--remove',f'tcp:{port}'],stdout=subprocess.DEVNULL)
    summary=[{k:v for k,v in r.items() if k!='input'} for r in requests]
    (out/'requests.json').write_text(json.dumps(summary,indent=2)+'\n')
    server.shutdown()
