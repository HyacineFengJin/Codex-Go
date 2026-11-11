#!/usr/bin/env python3
"""Emulator-only native Codex handoff, lock, pause, overlay and notification check."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import subprocess
import threading
import time
from android_driver import AndroidDriver

parser=argparse.ArgumentParser();parser.add_argument('--serial',default='emulator-5560');args=parser.parse_args()
driver=AndroidDriver(args.serial)
assert driver.safe_ui, 'Run bash tools/build_ui_driver.sh first; accessibility must not be suppressed'
artifacts=Path('.gradle/validation/reports/phone-flow');artifacts.mkdir(parents=True,exist_ok=True)
(artifacts/'result.json').unlink(missing_ok=True)
requests=[];third_waiting=threading.Event();release=threading.Event()
class Fixture(BaseHTTPRequestHandler):
    def log_message(self,*_): pass
    def do_GET(self):
        body=b'{"data":[]}'
        self.send_response(200);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
    def do_POST(self):
        body=json.loads(self.rfile.read(int(self.headers['Content-Length'])));requests.append(body);number=len(requests)
        if number == 2:
            # Fixture data only, retained locally to diagnose observation regressions.
            (artifacts/'first-observation.json').write_text(json.dumps(body, ensure_ascii=False)+'\n')
        rid=f'resp_phone_flow_{number}';mid=f'msg_phone_flow_{number}'
        self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
        def emit(event): self.wfile.write(('event: '+event['type']+'\ndata: '+json.dumps(event)+'\n\n').encode());self.wfile.flush()
        try:
            emit(dict(type='response.created',response=dict(id=rid)))
            if number==3: third_waiting.set();release.wait(120)
            if number in (1,2,3,5,7):
                name='phone_handoff' if number==2 else 'exec_command' if number in (5,7) else 'phone_observe'
                arguments={'reason':'请完成测试人工接管步骤，再点继续。'} if number==2 else {'cmd':'python3 -c "print(123456)"','max_output_tokens':1000} if number==5 else {'cmd':'python3 -c "print(654321)" #'+('x'*2100),'max_output_tokens':1000} if number==7 else {}
                emit(dict(type='response.output_item.done',item=dict(type='function_call',id=f'fc_phone_flow_{number}',call_id=f'call_phone_flow_{number}',name=name,arguments=json.dumps(arguments))))
            else:
                reply='PHONE_TASK_OK' if number==4 else 'LONG_APPROVAL_DONE' if number==8 else 'APPROVAL_DONE'
                emit(dict(type='response.output_item.added',item=dict(id=mid,type='message',role='assistant',content=[])))
                emit(dict(type='response.output_text.delta',delta=reply))
                emit(dict(type='response.output_item.done',item=dict(id=mid,type='message',role='assistant',content=[dict(type='output_text',text=reply)])))
            emit(dict(type='response.completed',response=dict(id=rid,usage=dict(input_tokens=0,output_tokens=0,total_tokens=0))))
        except (BrokenPipeError,ConnectionResetError): pass
        self.close_connection=True

server=ThreadingHTTPServer(('127.0.0.1',0),Fixture);port=server.server_port
threading.Thread(target=server.serve_forever,daemon=True).start()
old_access=driver.shell('settings','get','secure','enabled_accessibility_services').strip()
old_enabled=driver.shell('settings','get','secure','accessibility_enabled').strip()
original=subprocess.run(driver.adb+['shell','run-as','dev.codex.go','cat','files/codex/config.toml'],capture_output=True).stdout
animations=driver.disable_animations()
def type_message(text):
    driver.set_text(text)
    if 'mInputShown=true' in driver.shell('dumpsys','input_method'):driver.shell('input','keyevent','4')
    driver.click('发送消息')
try:
    subprocess.run(driver.adb+['reverse',f'tcp:{port}',f'tcp:{port}'],check=True,stdout=subprocess.DEVNULL)
    driver.shell('run-as','dev.codex.go','mkdir','-p','files/codex')
    driver.private_write('files/codex/config.toml',f'''model="codex-go-test"
model_provider="codex_go_test"
[analytics]
enabled=false
[model_providers.codex_go_test]
name="Local phone flow fixture"
base_url="http://127.0.0.1:{port}/v1"
wire_api="responses"
requires_openai_auth=false
supports_websockets=false
'''.encode())
    driver.shell('pm','grant','dev.codex.go','android.permission.POST_NOTIFICATIONS')
    driver.start();driver.click('打开侧栏');driver.click('本机环境');driver.click('启动本机 Codex');driver.find('本机 Codex 已就绪',timeout=80)
    driver.shell('settings','put','secure','enabled_accessibility_services','dev.codex.go/.phone.PhoneAccessService')
    driver.shell('settings','put','secure','accessibility_enabled','1')
    driver.click('返回对话');driver.click('打开侧栏');driver.click('手机操作');driver.find('已连接')
    driver.set_switch('预读优先',False)
    driver.capability('phone',True)
    driver.click('返回对话');driver.click('新建对话');type_message('FLOW_PHONE')
    driver.find('需要你来操作',timeout=30);driver.capture(artifacts/'handoff.png')
    assert len(requests)>=2 and 'data:image/' in json.dumps(requests[1])
    driver.shell('input','keyevent','3');driver.find('继续手机操作');driver.capture(artifacts/'handoff-overlay.png')
    driver.click('继续手机操作');assert third_waiting.wait(20), 'User continuation never reached native Codex'
    driver.shell('input','keyevent','223');driver.shell('input','keyevent','224');driver.shell('wm','dismiss-keyguard')
    driver.shell('am','start','-n','dev.codex.go/.MainActivity')
    driver.find('需要你来操作')
    assert any('屏幕已熄灭或锁定' in n.get('text','') for n in driver.xml().iter('node'))
    driver.capture(artifacts/'locked.png');driver.click('继续手机操作')
    driver.shell('input','keyevent','3');driver.click('暂停手机操作');driver.find('继续手机操作');driver.capture(artifacts/'paused-overlay.png')
    driver.shell('settings','put','secure','accessibility_enabled','0');time.sleep(.3)
    driver.shell('settings','put','secure','accessibility_enabled','1');driver.find('继续手机操作',timeout=20)
    release.set();time.sleep(.5)
    assert len(requests)==3, 'A phone observation passed the paused task gate'
    driver.click('继续手机操作')
    until=time.monotonic()+30
    while len(requests)<4 and time.monotonic()<until: time.sleep(.1)
    assert len(requests)>=4 and 'data:image/' in json.dumps(requests[3]), 'No fresh observation after continuation'
    driver.shell('cmd','statusbar','expand-notifications');driver.find('Codex 任务完成',timeout=20);driver.capture(artifacts/'completion.png')
    driver.click('Codex 任务完成');driver.find('PHONE_TASK_OK')
    type_message('FLOW_APPROVAL');driver.shell('input','keyevent','3')
    driver.shell('cmd','statusbar','expand-notifications');driver.find('Codex 需要你的确认',timeout=20);driver.find('允许本次');driver.capture(artifacts/'approval.png')
    driver.click('允许本次')
    until=time.monotonic()+30
    while len(requests)<6 and time.monotonic()<until: time.sleep(.1)
    assert len(requests)>=6
    outputs='\n'.join(str(m.get('output','')) for m in requests[5].get('input',[]) if m.get('type')=='function_call_output')
    assert '123456' in outputs, 'Background approval never executed the command'
    driver.shell('cmd','statusbar','collapse');driver.shell('am','start','-n','dev.codex.go/.MainActivity');driver.find('APPROVAL_DONE')
    type_message('FLOW_LONG_APPROVAL');driver.shell('input','keyevent','3');driver.shell('cmd','statusbar','expand-notifications')
    driver.find('Codex 需要你的确认',timeout=20)
    assert not any(n.get('text')=='允许本次' for n in driver.xml().iter('node')), 'A truncated command was allowed from its notification'
    driver.capture(artifacts/'long-command-notification.png');driver.click('Codex 需要你的确认');driver.click('允许本次');driver.find('LONG_APPROVAL_DONE',timeout=30)
    (artifacts/'result.json').write_text(json.dumps(dict(nativePhoneObserve=True,explicitHumanHandoff=True,overlay=True,screenOffPauses=True,pauseGatesTools=True,reconnectPreservesExplicitTaskPause=True,explicitResume=True,freshObservationAfterResume=True,completionNotification=True,backgroundCommandApproval=True,longCommandRequiresFullReview=True,externalInference=False),indent=2)+'\n')
    print('PASS: native observation, human handoff, overlay pause/resume, screen-off gate, completion and background approval')
finally:
    release.set();driver.capture(artifacts/'last-screen.png');driver.shell('am','force-stop','dev.codex.go')
    for key,value in [('enabled_accessibility_services',old_access),('accessibility_enabled',old_enabled)]:
        if value=='null': driver.shell('settings','delete','secure',key)
        else: driver.shell('settings','put','secure',key,value)
    if original: driver.private_write('files/codex/config.toml',original)
    else: driver.shell('run-as','dev.codex.go','rm','-f','files/codex/config.toml')
    driver.restore_animations(animations)
    subprocess.run(driver.adb+['reverse','--remove',f'tcp:{port}'],stdout=subprocess.DEVNULL);server.shutdown();server.server_close()
