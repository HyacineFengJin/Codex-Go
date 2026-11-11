#!/usr/bin/env python3
"""Run real on-device Codex against a deterministic localhost Responses fixture.
No account, credentials, external model or paid inference is used.
"""
import argparse
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import subprocess
import threading
import time
import uuid
from android_driver import AndroidDriver

parser = argparse.ArgumentParser()
parser.add_argument('--serial', default='emulator-5560')
parser.add_argument('--with-android-shell', action='store_true')
parser.add_argument('--shell-backend', choices=['wireless','wired','shizuku'], default='wireless')
parser.add_argument('--device-code', action='store_true')
args = parser.parse_args()
driver = AndroidDriver(args.serial)
artifacts = Path('.gradle/validation/reports')/('android-engine' if args.shell_backend=='wireless' else 'android-engine-'+args.shell_backend); artifacts.mkdir(parents=True, exist_ok=True)
(artifacts/'result.json').unlink(missing_ok=True)
requests = []
fixture = 'native-e2e-' + uuid.uuid4().hex[:8] + '.txt'
prompt = 'Integration_' + uuid.uuid4().hex[:8]
def redacted(value):
    if isinstance(value, str) and value.startswith('data:image/'):
        return '<image data omitted: ' + str(len(value)) + ' characters>'
    if isinstance(value, dict): return {key: redacted(item) for key, item in value.items()}
    if isinstance(value, list): return [redacted(item) for item in value]
    return value
class ResponsesFixture(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_GET(self):
        body = json.dumps({'data': []}).encode()
        self.send_response(200); self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)
    def do_POST(self):
        payload = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        try: request = json.loads(payload)
        except Exception: request = {'unparsedBytes': len(payload)}
        requests.append({'path': self.path, 'body': request})
        number = len(requests)
        response_id = f'resp_android_test_{number}'
        message_id = f'msg_android_test_{number}'
        events = [{'type': 'response.created', 'response': {'id': response_id}}]
        if args.with_android_shell and number in (4, 6):
            name = 'android_shell' if number == 4 else 'phone_observe'
            arguments = {'command': 'printf android_shell_execution', 'timeoutMs': 10000} if number == 4 else {}
            events.append({'type': 'response.output_item.done', 'item': {
                'type': 'function_call', 'id': f'fc_phone_{number}', 'call_id': f'call_phone_{number}', 'name': name,
                'arguments': json.dumps(arguments)}})
        elif number == 2:
            events.append({'type': 'response.output_item.done', 'item': {
                'type': 'function_call', 'id': 'fc_workspace', 'call_id': 'call_workspace', 'name': 'exec_command',
                'arguments': json.dumps({'cmd': 'printf codex_native_execution > /workspace/' + fixture, 'max_output_tokens': 1000})}})
        else:
            reply = {1:'ANDROID_ENGINE_OK', 3:'WORKSPACE_TOOL_OK', 5:'ANDROID_SHELL_OK', 7:'PHONE_OBSERVE_OK'}.get(number, 'UNEXPECTED_REQUEST')
            events += [
                {'type': 'response.output_item.added', 'item': {'id': message_id, 'type': 'message', 'role': 'assistant', 'content': []}},
                {'type': 'response.output_text.delta', 'delta': reply[:8]},
                {'type': 'response.output_text.delta', 'delta': reply[8:]},
                {'type': 'response.output_item.done', 'item': {'id': message_id, 'type': 'message', 'role': 'assistant', 'content': [{'type': 'output_text', 'text': reply}]}},
            ]
        events.append({'type': 'response.completed', 'response': {'id': response_id, 'usage': {'input_tokens': 0, 'output_tokens': 0, 'total_tokens': 0}}})
        self.send_response(200); self.send_header('Content-Type', 'text/event-stream'); self.end_headers()
        for event in events:
            self.wfile.write(('event: '+event['type']+'\ndata: '+json.dumps(event)+'\n\n').encode()); self.wfile.flush()
            time.sleep(.05)
        self.close_connection = True

server = ThreadingHTTPServer(('127.0.0.1', 0), ResponsesFixture)
port = server.server_port
threading.Thread(target=server.serve_forever, daemon=True).start()
old_access = driver.shell('settings', 'get', 'secure', 'enabled_accessibility_services').strip()
old_access_enabled = driver.shell('settings', 'get', 'secure', 'accessibility_enabled').strip()
original = subprocess.run(driver.adb + ['shell', 'run-as', 'dev.codex.go', 'cat', 'files/codex/config.toml'], capture_output=True).stdout
animations = driver.disable_animations()
heads_up = driver.shell('settings', 'get', 'global', 'heads_up_notifications_enabled').strip()
driver.shell('settings', 'put', 'global', 'heads_up_notifications_enabled', '0')
try:
    subprocess.run(driver.adb + ['reverse', f'tcp:{port}', f'tcp:{port}'], check=True, stdout=subprocess.DEVNULL)
    driver.shell('run-as', 'dev.codex.go', 'mkdir', '-p', 'files/codex')
    config = f'''model = "codex-go-test"
model_provider = "codex_go_test"
cli_auth_credentials_store = "file"
[analytics]
enabled = false
[model_providers.codex_go_test]
name = "Local integration fixture"
base_url = "http://127.0.0.1:{port}/v1"
wire_api = "responses"
requires_openai_auth = false
supports_websockets = false
'''
    driver.private_write('files/codex/config.toml', config.encode())
    driver.shell('pm', 'grant', 'dev.codex.go', 'android.permission.POST_NOTIFICATIONS')
    driver.start()
    driver.click('打开侧栏'); driver.click('本机环境'); driver.click('启动本机 Codex')
    until = time.monotonic() + 80
    ready = False
    while time.monotonic() < until:
        text = '\n'.join(n.get('text', '') for n in driver.xml().iter('node'))
        if '本机 Codex 已就绪' in text: ready = True; break
        if '启动失败' in text or '本机环境已断开' in text: break
    driver.capture(artifacts/'startup.png')
    if not ready:
        driver.click('查看运行日志')
        logs = '\n'.join(n.get('text', '') for n in driver.xml().iter('node'))
        (artifacts/'startup-error.txt').write_text(logs)
        raise AssertionError('Native engine startup failed; see .gradle/validation/reports/android-engine/startup-error.txt')
    driver.click('返回对话'); driver.click('新建对话')
    driver.tap(driver.find(classname='android.widget.EditText')); driver.shell('input', 'text', prompt)
    assert 'mInputShown=true' in driver.shell('dumpsys','input_method'),'Composer was not focused before first send'
    driver.click('发送消息')
    driver.find('ANDROID_ENGINE_OK', timeout=60)
    assert 'mInputShown=true' in driver.shell('dumpsys','input_method'),'Assigning the first native thread ID dismissed the focused composer'
    if 'mInputShown=true' in driver.shell('dumpsys','input_method'):driver.shell('input','keyevent','4')
    driver.find('打开侧栏')
    driver.capture(artifacts/'conversation.png')
    assert requests, 'No Responses request reached the fixture'
    texts = [n.get('text') for n in driver.xml().iter('node')]
    assert texts.count('ANDROID_ENGINE_OK') == 1, 'Streaming reply was duplicated'
    driver.click('打开侧栏'); driver.find(prompt); driver.capture(artifacts/'history.png')
    driver.click(prompt); driver.find('ANDROID_ENGINE_OK')
    driver.tap(driver.find(classname='android.widget.EditText')); driver.shell('input', 'text', 'Write%sa%sworkspace%sfixture')
    driver.click('发送消息')
    until = time.monotonic() + 90
    completed = False
    while time.monotonic() < until:
        texts = [n.get('text', '') for n in driver.xml().iter('node')]
        if '允许本次' in texts: driver.click('允许本次')
        if 'WORKSPACE_TOOL_OK' in texts: completed = True; break
    assert completed, 'Workspace tool turn did not complete'
    actual = driver.shell('run-as', 'dev.codex.go', 'cat', 'files/workspace/' + fixture)
    assert actual == 'codex_native_execution', repr(actual)
    if 'mInputShown=true' in driver.shell('dumpsys','input_method'):driver.shell('input','keyevent','4')
    driver.find('打开侧栏'); driver.capture(artifacts/'workspace-tool.png')
    if args.with_android_shell:
        def device_page(): driver.click('打开侧栏'); driver.click('手机操作')
        def set_switch(index, enabled):
            driver.capability('phone' if index==0 else 'shell',enabled)
        def complete_turn(reply):
            driver.click('发送消息')
            until = time.monotonic() + 60
            while time.monotonic() < until:
                labels = [n.get('text','') for n in driver.xml().iter('node')]
                if '允许本次' in labels: driver.click('允许本次')
                if reply in labels: return
            raise AssertionError('Phone tool turn did not complete: '+reply)
        device_page(); driver.click('已配对，重新连接'); driver.find('无线调试已连接', timeout=30)
        click_visible = driver.scroll_click
        if args.shell_backend=='wired':
            click_visible('选择 有线 ADB');click_visible('电脑 ADB 启动指南');driver.find('从电脑启动 Shell')
            command=next(n.get('text') for n in driver.xml().iter('node') if n.get('text','').startswith('adb shell '))
            assert command.startswith("adb shell '") and command.endswith("'")
            subprocess.run(driver.adb+['shell',command[len("adb shell '"):-1]],check=True)
            driver.find('已连接 · ADB Shell',timeout=20);driver.click('完成')
        elif args.shell_backend=='shizuku':
            click_visible('选择 Shizuku');click_visible('授权并连接 Shizuku')
            driver.find('已连接 · ADB Shell',timeout=20)
        set_switch(0, True); set_switch(1, True)
        driver.shell('settings', 'put', 'secure', 'enabled_accessibility_services', 'dev.codex.go/.phone.PhoneAccessService')
        driver.shell('settings', 'put', 'secure', 'accessibility_enabled', '1')
        driver.click('返回对话')
        driver.tap(driver.find(classname='android.widget.EditText')); driver.shell('input', 'text', 'Run%sAndroid%sshell%sfixture')
        complete_turn('ANDROID_SHELL_OK')
        assert 'android_shell_execution' in json.dumps(requests[-1]['body']), 'Android Shell output never reached Codex'
        if 'mInputShown=true' in driver.shell('dumpsys','input_method'):driver.shell('input','keyevent','4')
        driver.find('打开侧栏'); driver.capture(artifacts/'android-shell.png')
        # Turning Shell off must leave Computer Use available.
        device_page(); set_switch(1, False)
        driver.click('返回对话')
        driver.tap(driver.find(classname='android.widget.EditText')); driver.shell('input', 'text', 'Observe%sphone%sfixture')
        complete_turn('PHONE_OBSERVE_OK')
        assert 'data:image/png;base64,' in json.dumps(requests[-1]['body']), 'Phone screenshot never reached Codex'
        if 'mInputShown=true' in driver.shell('dumpsys','input_method'):driver.shell('input','keyevent','4')
        driver.find('打开侧栏'); driver.capture(artifacts/'phone-observe.png')
    driver.click('打开侧栏'); driver.click('本机环境'); driver.click('停止本机环境'); driver.click('停止')
    driver.find('本机环境已停止')
    names = driver.shell('ps', '-A', '-o', 'NAME').splitlines()
    assert 'codex' not in [name.strip() for name in names], 'Codex process survived Stop'
    driver.click('启动本机 Codex'); driver.find('本机 Codex 已就绪', timeout=60)
    driver.click('返回对话'); driver.find('WORKSPACE_TOOL_OK', timeout=30)
    device_result = None
    if args.device_code:
        driver.click('打开侧栏'); driver.click('本机环境'); driver.click('使用设备码登录')
        try:
            driver.find('登录你的 ChatGPT', timeout=60)
            driver.find('https://auth.openai.com/codex/device')
            driver.click('取消登录')
            device_result = True
        except Exception:
            device_result = False
            driver.capture(artifacts/'device-code-failure.png')
    (artifacts/'requests.json').write_text(json.dumps(redacted(requests), ensure_ascii=False, indent=2))
    (artifacts/'result.json').write_text(json.dumps({'nativeEngine': True, 'firstNativeIdKeepsComposerFocused': True, 'streamedReply': True, 'replyCount': 1, 'history': True, 'resumedHistory': True, 'nativeWorkspaceCommand': True, 'stopAndRestart': True, 'androidShell': args.with_android_shell, 'computerUseAfterShellOff': args.with_android_shell, 'deviceCodeIssuedAndCancelled': device_result, 'externalInference': False}, indent=2))
    print('PASS: Android native Codex startup, streamed model fixture reply, deduplicated completion and saved/resumed history and actual Linux workspace command')
finally:
    driver.capture(artifacts/'last-screen.png')
    if args.with_android_shell:
        if old_access == 'null': driver.shell('settings', 'delete', 'secure', 'enabled_accessibility_services')
        else: driver.shell('settings', 'put', 'secure', 'enabled_accessibility_services', old_access)
        if old_access_enabled != 'null': driver.shell('settings', 'put', 'secure', 'accessibility_enabled', old_access_enabled)
    (artifacts/'requests.json').write_text(json.dumps(redacted(requests), ensure_ascii=False, indent=2))
    driver.shell('am', 'force-stop', 'dev.codex.go')
    driver.shell('run-as', 'dev.codex.go', 'rm', '-f', 'files/workspace/' + fixture)
    if original: driver.private_write('files/codex/config.toml', original)
    else: driver.shell('run-as', 'dev.codex.go', 'rm', '-f', 'files/codex/config.toml')
    subprocess.run(driver.adb + ['reverse', '--remove', f'tcp:{port}'], stdout=subprocess.DEVNULL)
    driver.restore_animations(animations)
    if heads_up == 'null': driver.shell('settings', 'delete', 'global', 'heads_up_notifications_enabled')
    else: driver.shell('settings', 'put', 'global', 'heads_up_notifications_enabled', heads_up)
    server.shutdown(); server.server_close()
