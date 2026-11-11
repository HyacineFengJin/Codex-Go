#!/usr/bin/env python3
"""Recover an actually unstartable guest on a disposable emulator, without inference."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import subprocess
import threading
import time
import xml.etree.ElementTree as ET
from android_driver import AndroidDriver

parser=argparse.ArgumentParser()
parser.add_argument('--serial',default='emulator-5560')
args=parser.parse_args()
driver=AndroidDriver(args.serial)
out=Path('.gradle/validation/reports/repair-failure'); out.mkdir(parents=True,exist_ok=True)
(out/'result.json').unlink(missing_ok=True)
class Fixture(BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def do_GET(self):
        data=b'{"data":[]}'
        self.send_response(200);self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
server=ThreadingHTTPServer(('127.0.0.1',0),Fixture)
threading.Thread(target=server.serve_forever,daemon=True).start()
port=server.server_port
config_path='files/codex/config.toml'
bindings_path='files/environment/.bindings.json'
config=subprocess.run(driver.adb+['shell','run-as','dev.codex.go','cat',config_path],capture_output=True).stdout
bindings=subprocess.check_output(driver.adb+['shell','run-as','dev.codex.go','cat',bindings_path])
animations=driver.disable_animations()
started=int(time.time()*1000)
try:
    subprocess.run(driver.adb+['reverse',f'tcp:{port}',f'tcp:{port}'],check=True,stdout=subprocess.DEVNULL)
    driver.shell('am','force-stop','dev.codex.go')
    fixture=f'''model = "codex-go-test"
model_provider = "codex_go_test"
cli_auth_credentials_store = "file"
[analytics]
enabled = false
[model_providers.codex_go_test]
name = "Local recovery fixture"
base_url = "http://127.0.0.1:{port}/v1"
wire_api = "responses"
requires_openai_auth = false
supports_websockets = false
'''.encode()
    driver.private_write(config_path,fixture)
    driver.private_write('files/workspace/repair-failure-keep.txt',b'WORK_MUST_SURVIVE_FAILED_START')
    driver.private_write(bindings_path,b'{BROKEN_BY_DISPOSABLE_TEST')
    driver.start();driver.click('打开侧栏');driver.click('本机环境');driver.click('启动本机 Codex')
    driver.find('启动失败',timeout=60);driver.capture(out/'failed-start.png')
    found=False
    for _ in range(7):
        node=next((n for n in driver.xml().iter('node') if n.get('text')=='修复运行环境'),None)
        if node is not None: driver.tap(node);found=True;break
        driver.shell('input','swipe','530','1970','530','700','400')
    assert found,'Repair action unavailable after failed startup'
    driver.click('保留数据并修复')
    for _ in range(4):driver.shell('input','swipe','530','700','530','1970','300')
    driver.find('本机 Codex 已就绪',timeout=100)
    assert subprocess.check_output(driver.adb+['shell','run-as','dev.codex.go','cat',bindings_path])==bindings
    assert driver.shell('run-as','dev.codex.go','cat','files/workspace/repair-failure-keep.txt')=='WORK_MUST_SURVIVE_FAILED_START'
    assert subprocess.check_output(driver.adb+['shell','run-as','dev.codex.go','cat',config_path])==fixture
    events_xml=ET.fromstring(driver.shell('run-as','dev.codex.go','cat','shared_prefs/diagnostic_events.xml'))
    events=json.loads(next(n.text for n in events_xml if n.get('name')=='events'))
    failed=[e for e in events if e['event']=='StartFailed' and e['timeMs']>=started]
    assert failed and failed[-1]['stage']=='launch'
    assert failed[-1]['errorType']=='JSONException'
    driver.capture(out/'recovered.png')
    (out/'result.json').write_text(json.dumps({'version':driver.version(),'actualStartupFailure':True,'failureStageRecorded':True,'repairAvailableAfterFailure':True,'nativeEngineRecovered':True,'workspaceAndConfigPreserved':True,'externalInference':False},indent=2)+'\n')
    print('PASS: actual startup failure, recorded failure stage, UI repair, native engine recovery, and retained workspace/config')
finally:
    driver.capture(out/'last-screen.png')
    driver.shell('am','force-stop','dev.codex.go')
    driver.private_write(bindings_path,bindings)
    if config:driver.private_write(config_path,config)
    else:driver.shell('run-as','dev.codex.go','rm','-f',config_path)
    driver.shell('run-as','dev.codex.go','rm','-f','files/workspace/repair-failure-keep.txt')
    driver.restore_animations(animations)
    subprocess.run(driver.adb+['reverse','--remove',f'tcp:{port}'],stdout=subprocess.DEVNULL)
    server.shutdown()
