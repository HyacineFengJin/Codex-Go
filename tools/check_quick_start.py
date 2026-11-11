#!/usr/bin/env python3
"""Owned emulator: one system grant, automatic AI access, reconnect, overlay and startup UI."""
import argparse,json,re,subprocess,threading,time,xml.etree.ElementTree as E
from pathlib import Path
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from android_driver import AndroidDriver
p=argparse.ArgumentParser();p.add_argument('--serial',default='emulator-5560');args=p.parse_args();d=AndroidDriver(args.serial);assert d.safe_ui
assert d.shell('getprop','ro.adb.secure').strip()=='0' and d.shell('getprop','ro.hardware').strip() in ('ranchu','goldfish')
out=Path('.gradle/validation/reports/quick-start');out.mkdir(parents=True,exist_ok=True);(out/'result.json').unlink(missing_ok=True)
for file in out.glob('*.png'):file.unlink()
counts={};facts={};failures=[]
def strings(v):
    if isinstance(v,str):yield v
    elif isinstance(v,dict):
        for x in v.values():yield from strings(x)
    elif isinstance(v,list):
        for x in v:yield from strings(x)
class Fixture(BaseHTTPRequestHandler):
    def log_message(self,*_):pass
    def do_GET(self):
        b=b'{"data":[]}';self.send_response(200);self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
    def do_POST(self):
        body=json.loads(self.rfile.read(int(self.headers['Content-Length'])));alltext='\n'.join(strings(body));case='RESTORED' if 'QUICKSTART_RESTORED' in alltext else 'INITIAL';counts[case]=counts.get(case,0)+1;n=counts[case]
        print('Quick-start request',case,n,flush=True);self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
        def emit(v):self.wfile.write(('event: '+v['type']+'\ndata: '+json.dumps(v)+'\n\n').encode());self.wfile.flush()
        def tool(name,a):emit(dict(type='response.output_item.done',item=dict(type='function_call',id=f'f_{case}_{n}',call_id=f'c_{case}_{n}',name=name,arguments=json.dumps(a))))
        try:
            emit(dict(type='response.created',response=dict(id=f'r_{case}_{n}')))
            if case=='INITIAL' and n==1:tool('phone_observe',{})
            elif case=='RESTORED' and n==1:tool('android_shell',{'command':'printf automatic_shell_ready','timeoutMs':5000})
            elif case=='RESTORED' and n==2:
                assert 'automatic_shell_ready' in alltext,'System-granted Shell was not usable by AI';facts['nativeShellWithoutSecondGrant']=True;tool('phone_observe',{})
            else:
                assert any(s.startswith('data:image/') for s in strings(body)),'System-granted screen was not usable by AI'
                facts['nativePhoneWithoutSecondGrant' if case=='INITIAL' else 'nativePhoneAfterReconnect']=True
                emit(dict(type='response.output_item.done',item=dict(type='message',id=f'm_{case}',role='assistant',content=[dict(type='output_text',text=f'QUICKSTART_{case}_OK')])))
            emit(dict(type='response.completed',response=dict(id=f'r_{case}_{n}',usage=dict(input_tokens=0,output_tokens=0,total_tokens=0))))
        except Exception as error:failures.append(str(error));print('FAIL:',str(error),flush=True)
        self.close_connection=True
server=ThreadingHTTPServer(('127.0.0.1',0),Fixture);server.daemon_threads=True;port=server.server_port
threading.Thread(target=server.serve_forever,daemon=True).start()
def read(path):
    if subprocess.run(d.adb+['shell','run-as','dev.codex.go','test','-f',path]).returncode!=0:return None
    r=subprocess.run(d.adb+['exec-out','run-as','dev.codex.go','cat',path],capture_output=True);return r.stdout if r.returncode==0 else None
def write(path,data):
    if path.startswith('files/'):d.private_write(path,data)
    else:subprocess.run(d.adb+['shell','run-as','dev.codex.go','sh','-c',"'cat > shared_prefs/codex_go.xml'"],input=data,check=True)
def prefs():return {n.get('name'):n.get('value',n.text or '') for n in E.fromstring(read('shared_prefs/codex_go.xml') or b'<map/>')}
def wait_pref(key,value):
    end=time.monotonic()+5
    while time.monotonic()<end:
        if prefs().get(key)==value:return
        time.sleep(.15)
    raise AssertionError('Preference not saved: '+key+'='+repr(prefs().get(key)))
def labels():return [n.get('text','') or n.get('content-desc','') for n in d.xml().iter('node')]
def wait(label,seconds=40):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        assert not failures,failures
        if label in labels():return
        time.sleep(.15)
    raise AssertionError('Missing '+label)
def controls():d.click('快捷控制');wait('关闭快捷控制')
def close():d.click('关闭快捷控制')
def send(case):
    d.set_text('QUICKSTART_'+case)
    if 'mInputShown=true' in d.shell('dumpsys','input_method'):d.shell('input','keyevent','4')
    d.click('发送消息');end=time.monotonic()+45
    while time.monotonic()<end:
        assert not failures,failures
        shown=labels()
        if '允许本次' in shown:d.click('允许本次')
        if 'QUICKSTART_'+case+'_OK' in shown:return
        time.sleep(.15)
    raise AssertionError('Native turn incomplete '+case)
original={p:read(p) for p in ['files/codex/config.toml','shared_prefs/codex_go.xml']}
access={k:d.shell('settings','get','secure',k).strip() for k in ('enabled_accessibility_services','accessibility_enabled')};animations=d.disable_animations()
try:
    if d.shell('getprop','service.adb.tcp.port').strip()!='5555':
        subprocess.run(d.adb+['tcpip','5555'],check=True,stdout=subprocess.DEVNULL);time.sleep(1);subprocess.run(d.adb+['wait-for-device'],timeout=20,check=True)
    subprocess.run(d.adb+['reverse',f'tcp:{port}',f'tcp:{port}'],check=True,stdout=subprocess.DEVNULL)
    d.shell('am','force-stop','dev.codex.go');d.shell('run-as','dev.codex.go','mkdir','-p','files/codex','shared_prefs');write('shared_prefs/codex_go.xml',b'<map><boolean name="engineUsed" value="false"/></map>')
    write('files/codex/config.toml',f'''model="codex-go-test"
model_provider="quickstart_fixture"
[analytics]
enabled=false
[model_providers.quickstart_fixture]
name="Local automatic readiness fixture"
base_url="http://127.0.0.1:{port}/v1"
wire_api="responses"
requires_openai_auth=false
supports_websockets=false
'''.encode())
    d.shell('pm','grant','dev.codex.go','android.permission.POST_NOTIFICATIONS');d.shell('appops','set','dev.codex.go','SYSTEM_ALERT_WINDOW','allow')
    d.shell('settings','put','secure','accessibility_enabled','0')
    d.start(auto_prepare=None);controls();d.click('启动本机 Codex');wait('已就绪',100);facts['startWithoutSettingsPage']=True
    d.shell('settings','put','secure','enabled_accessibility_services','dev.codex.go/.phone.PhoneAccessService');d.shell('settings','put','secure','accessibility_enabled','1')
    wait('已就绪 · 无障碍');assert '允许 Codex 操作手机' not in labels();facts['systemGrantImmediatelyEnablesAI']=True
    d.capture(out/'controls-light.png');close();send('INITIAL')
    d.shell('input','keyevent','3');wait('打开悬浮对话');facts['systemOverlayGrantNeedsNoExtraSwitch']=True
    d.shell('am','start','-n','dev.codex.go/.MainActivity');controls();d.click('连接详情');d.scroll_click('手动端口');d.set_text('5555');d.click('连接');wait('无线调试已连接',35)
    d.capability('shell');assert prefs().get('lastWirelessPort')=='5555';facts['wirelessConnectionImmediatelyEnablesShell']=True
    d.scroll_click('选择 有线 ADB');wait_pref('shellBackend','Wired');d.click('返回对话')
    # OS force-stop interrupts accessibility; the already granted ADB connection must recover instead.
    d.start(auto_prepare=None);controls();wait('已就绪',100);wait('已就绪 · ADB',45)
    assert prefs().get('shellBackend')=='Wired';facts['selectedShellBackendSurvivesRestart']=True;facts['engineAndWirelessReconnectWithoutSettings']=True
    close();d.click('打开侧栏');d.click('手机操作');d.scroll_click('选择 无线调试');d.capability('shell');d.click('返回对话');send('RESTORED')
    # A temporary capability pause is optional and must survive a connection refresh.
    d.click('打开侧栏');d.click('手机操作');d.capability('phone',False);d.click('返回对话');controls();d.click('恢复已有连接');wait('已就绪 · ADB');close();facts['explicitPrepareResumesTemporaryPause']=True
    d.shell('input','keyevent','3');wait('打开悬浮对话');d.shell('am','start','-n','dev.codex.go/.MainActivity');controls()
    d.set_switch('快捷悬浮对话',False);close();d.start(auto_prepare=None);controls();wait('已就绪',100)
    assert prefs().get('chatOverlayEnabled')=='false';facts['explicitOverlayOffIsRemembered']=True
    d.set_switch('快捷悬浮对话',True);close();d.shell('appops','set','dev.codex.go','SYSTEM_ALERT_WINDOW','deny');d.shell('input','keyevent','3');d.shell('am','start','-n','dev.codex.go/.MainActivity');controls()
    wait('允许显示悬浮窗');assert prefs().get('chatOverlayEnabled')=='true';facts['missingOverlayPermissionKeepsChoice']=True
    close();d.shell('appops','set','dev.codex.go','SYSTEM_ALERT_WINDOW','allow');d.shell('input','keyevent','3');d.shell('am','start','-n','dev.codex.go/.MainActivity');d.shell('input','keyevent','3');wait('打开悬浮对话');facts['overlayReturnsAfterPermissionRestored']=True
    d.shell('am','start','-n','dev.codex.go/.MainActivity');controls();d.set_switch('下次打开自动准备',False);close();d.start(auto_prepare=None);controls();wait('未启动')
    assert '已就绪' not in labels();facts['autoPrepareCanBeDisabled']=True
    d.set_switch('下次打开自动准备',True);d.click('启动本机 Codex');wait('已就绪',100);wait('已就绪 · ADB',45);d.capture(out/'controls-ready.png')
    d.shell('cmd','uimode','night','yes');d.shell('wm','density','480');d.shell('settings','put','system','font_scale','1.3');time.sleep(.6);wait('关闭快捷控制');d.capture(out/'controls-dark-360-large.png');facts['darkAndLargeFontControlsRemainAccessible']=True
    close();d.shell('cmd','uimode','night','no');d.shell('wm','density','reset');d.shell('settings','put','system','font_scale','1.0')
    # The direct OS settings link is an optional first-grant path, never a daily gate.
    d.shell('settings','put','secure','accessibility_enabled','0');controls();d.scroll_click('连接无障碍');wait('Codex Go 手机操作');d.capture(out/'accessibility-details.png');facts['accessibilitySettingsEntryWithFallback']=True
    (out/'result.json').write_text(json.dumps(dict(version=d.version(),**facts,requestCount=sum(counts.values()),externalInference=False,realAccountLogin=False),indent=2)+'\n')
    print('PASS: system-grant AI access, automatic startup/ADB reconnect, persistent backend, overlay recovery and direct controls',flush=True)
except Exception:
    d.capture(out/'failure-screen.png');raise
finally:
    subprocess.run(d.adb+['wait-for-device'],check=True,timeout=20);d.shell('am','force-stop','dev.codex.go')
    for path,data in original.items():
        if data is None:d.shell('run-as','dev.codex.go','rm','-f',path)
        else:write(path,data)
    for k,v in access.items():
        if v=='null':d.shell('settings','delete','secure',k)
        else:d.shell('settings','put','secure',k,v)
    d.shell('cmd','uimode','night','no');d.shell('wm','density','reset');d.shell('settings','put','system','font_scale','1.0');d.restore_animations(animations)
    subprocess.run(d.adb+['reverse','--remove',f'tcp:{port}'],stdout=subprocess.DEVNULL);server.shutdown();server.server_close()
