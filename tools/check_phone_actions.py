#!/usr/bin/env python3
"""Owned emulator: actual native tool calls on a changing test-owned screen, no external inference."""
import argparse,json,re,subprocess,threading,time
from pathlib import Path
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from android_driver import AndroidDriver
p=argparse.ArgumentParser();p.add_argument('--serial',default='emulator-5560');p.add_argument('--adb',action='store_true');args=p.parse_args()
d=AndroidDriver(args.serial);assert d.safe_ui
out=Path('.gradle/validation/reports')/('phone-actions-adb' if args.adb else 'phone-actions');out.mkdir(parents=True,exist_ok=True);(out/'result.json').unlink(missing_ok=True)
ready=threading.Event();captured=threading.Event();changed=threading.Event();navigated=threading.Event();returned=threading.Event();expiry=threading.Event();expired=threading.Event();done=threading.Event()
requests=[];failures=[];facts={};points={};frame='';capture_time=0
gesture_ok='操作已提交' if args.adb else '手势已完成'
type_ok='操作已提交' if args.adb else '文字已输入'
key_ok='操作已提交' if args.adb else '导航操作已提交'

def texts(v):
    if isinstance(v,dict):
        for k,x in v.items():
            if k in ('text','output') and isinstance(x,str):yield x
            elif isinstance(x,(dict,list)):yield from texts(x)
    elif isinstance(v,list):
        for x in v:yield from texts(x)
def last(body):return next(v for v in reversed(body['input']) if v.get('type')=='function_call_output')
class Fixture(BaseHTTPRequestHandler):
    def log_message(self,*_):pass
    def do_GET(self):
        b=b'{"data":[]}';self.send_response(200);self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
    def do_POST(self):
        global frame,capture_time
        body=json.loads(self.rfile.read(int(self.headers['Content-Length'])));requests.append(body);n=len(requests)
        self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
        def emit(v):self.wfile.write(('event: '+v['type']+'\ndata: '+json.dumps(v)+'\n\n').encode());self.wfile.flush()
        def tool(name,a):emit(dict(type='response.output_item.done',item=dict(type='function_call',id='fc_'+str(n),call_id='call_'+str(n),name=name,arguments=json.dumps(a))))
        def tap(point,token=True):tool('phone_tap',dict(zip(('x','y'),points[point]))|({'frameId':frame} if token else {}))
        try:
            emit(dict(type='response.created',response=dict(id='r_'+str(n))))
            value='\n'.join(texts(last(body))) if n>1 else ''
            print('Action request',n,flush=True)
            if n==1:
                assert ready.wait(45);tap('target',False)
            elif n==2:
                assert gesture_ok in value,value;facts['actionWithoutObservation']=True;tool('phone_observe',{'mode':'image'})
            elif n==3:
                for text in texts(last(body)):
                    match=re.search(r'frameId=([a-zA-Z0-9-]+)',text)
                    if match:frame=match[1]
                    for line in text.splitlines():
                        try:r=json.loads(line)
                        except ValueError:continue
                        if isinstance(r,dict) and 'frameId' in r:frame=r['frameId']
                assert frame,'Observation omitted frameId';capture_time=time.monotonic();captured.set();assert changed.wait(35);tap('target')
            elif n==4:
                assert gesture_ok in value,value;facts['tapAfterContinuousContentChanges']=True;tap('target')
            elif n==5:
                assert gesture_ok in value,value;facts['sameTokenCanPerformMultipleActions']=True;tap('input')
            elif n==6:
                assert gesture_ok in value,value;tool('phone_type',{'frameId':frame,'text':'typed_after_change'})
            elif n==7:
                assert type_ok in value,value;facts['typingAfterFocusChange']=True;tool('phone_key',{'frameId':frame,'key':'home'})
            elif n==8:
                assert key_ok in value,value;facts['navigationWithOldToken']=True;navigated.set();assert returned.wait(30)
                tool('phone_swipe',{'frameId':frame,'x':500,'y':1800,'endX':500,'endY':1500})
            elif n==9:
                assert gesture_ok in value,value;facts['swipeAfterWindowChange']=True;tool('phone_tap',{'frameId':frame,'x':-1,'y':20})
            elif n==10:
                assert '坐标超出当前屏幕范围' in value,value;facts['outOfBoundsStillRejected']=True;expiry.set();assert expired.wait(45)
                tool('phone_read_image',{'frameId':frame})
            elif n==11:
                assert any(x in value for x in ('原图不属于','屏幕已变化','观察过期')),value
                facts['staleSameFrameImageStillRejected']=True;tap('target')
            else:
                assert n==12 and gesture_ok in value,value;facts['tapAfterThirtySecondExpiry']=True
                emit(dict(type='response.output_item.done',item=dict(type='message',id='m_done',role='assistant',content=[dict(type='output_text',text='PHONE_ACTIONS_OK')])));done.set()
            emit(dict(type='response.completed',response=dict(id='r_'+str(n),usage=dict(input_tokens=0,output_tokens=0,total_tokens=0))))
        except Exception as e:failures.append(str(e));done.set();print('FAIL:',str(e)[:700],flush=True)
        self.close_connection=True
server=ThreadingHTTPServer(('127.0.0.1',0),Fixture);server.daemon_threads=True;port=server.server_port
threading.Thread(target=server.serve_forever,daemon=True).start();original={}
for path in ('files/codex/config.toml','shared_prefs/codex_go.xml'):
    exists=subprocess.run(d.adb+['shell','run-as','dev.codex.go','test','-f',path]).returncode==0
    original[path]=subprocess.check_output(d.adb+['exec-out','run-as','dev.codex.go','cat',path]) if exists else None
access={k:d.shell('settings','get','secure',k).strip() for k in ('enabled_accessibility_services','accessibility_enabled')};animations=d.disable_animations()
def await_gate(gate,timeout):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        assert not failures,failures
        if gate.wait(.2):return
    raise AssertionError('Fixture gate timed out')
def center(node):
    a,b,c,e=map(int,re.findall(r'\d+',node.get('bounds')));return (a+c)//2,(b+e)//2
try:
    if args.adb:
        assert d.shell('getprop','ro.hardware').strip() in ('ranchu','goldfish') and d.shell('getprop','ro.adb.secure').strip()=='0','Plaintext ADB fixture requires an owned non-secure emulator'
        if d.shell('getprop','service.adb.tcp.port').strip()!='5555':
            subprocess.run(d.adb+['tcpip','5555'],check=True,stdout=subprocess.DEVNULL)
            # tcpip acknowledges before adbd exits. Let that disconnect happen
            # before waiting, rather than accepting the soon-to-exit transport.
            time.sleep(1)
        subprocess.run(d.adb+['wait-for-device'],check=True,timeout=20)
    subprocess.run(d.adb+['reverse',f'tcp:{port}',f'tcp:{port}'],check=True,stdout=subprocess.DEVNULL)
    d.shell('am','force-stop','dev.codex.go');d.shell('run-as','dev.codex.go','mkdir','-p','files/codex')
    d.private_write('files/codex/config.toml',f'''model="codex-go-test"
model_provider="actions_fixture"
[analytics]
enabled=false
[model_providers.actions_fixture]
name="Local phone action fixture"
base_url="http://127.0.0.1:{port}/v1"
wire_api="responses"
requires_openai_auth=false
supports_websockets=false
'''.encode())
    d.shell('pm','grant','dev.codex.go','android.permission.POST_NOTIFICATIONS')
    d.start();d.click('打开侧栏');d.click('本机环境');d.click('启动本机 Codex');d.find('本机 Codex 已就绪',timeout=100)
    d.shell('settings','put','secure','accessibility_enabled','0')
    if not args.adb:
        d.shell('settings','put','secure','enabled_accessibility_services','dev.codex.go/.phone.PhoneAccessService');d.shell('settings','put','secure','accessibility_enabled','1')
    d.click('返回对话');d.click('打开侧栏');d.click('手机操作');d.set_switch('预读优先',False)
    if args.adb:
        d.scroll_click('手动端口');d.set_text('5555');d.click('连接');d.find('无线调试已连接',timeout=30)
    d.capability('phone',True);d.click('返回对话');d.click('新建对话')
    d.set_text('PHONE_ACTION_FIXTURE')
    if 'mInputShown=true' in d.shell('dumpsys','input_method'):d.shell('input','keyevent','4')
    d.click('发送消息');d.shell('am','start','-n','dev.codex.go.sharefixture/dev.codex.go.testdriver.ReadingFixtureActivity','--es','mode','actions')
    points['target']=center(d.find('点击计数按钮'));points['input']=center(d.find('动态输入测试'));ready.set()
    await_gate(captured,40);d.find('点击次数 1');d.click('开始持续变化');time.sleep(.8);changed.set()
    await_gate(navigated,40);d.shell('am','start','-f','0x20000','-n','dev.codex.go.sharefixture/dev.codex.go.testdriver.ReadingFixtureActivity');d.find('typed_after_change');returned.set()
    await_gate(expiry,30)
    while time.monotonic()-capture_time<31.5:assert not failures,failures;time.sleep(.2)
    expired.set();await_gate(done,30);assert not failures,failures
    d.find('点击次数 4');facts['actualUiClickCountAndInputVerified']=True;d.capture(out/'dynamic-actions.png')
    d.shell('am','start','-n','dev.codex.go/.MainActivity');d.find('PHONE_ACTIONS_OK',timeout=30)
    result=dict(version=d.version(),backend='adb' if args.adb else 'accessibility',**facts,requestCount=len(requests),externalInference=False,realAccountLogin=False)
    (out/'result.json').write_text(json.dumps(result,indent=2)+'\n');print('PASS: dynamic/repeated/expired/frameless actions, actual UI results, bounds and cached-image checks',flush=True)
finally:
    for gate in (ready,changed,returned,expired):gate.set()
    subprocess.run(d.adb+['wait-for-device'],check=True,timeout=20)
    d.shell('am','force-stop','dev.codex.go');d.shell('am','force-stop','dev.codex.go.sharefixture')
    for path,data in original.items():
        if data is None:d.shell('run-as','dev.codex.go','rm','-f',path)
        else:subprocess.run(d.adb+['shell','run-as','dev.codex.go','sh','-c',"'cat > "+path+"'"],input=data,check=True)
    for k,v in access.items():
        if v=='null':d.shell('settings','delete','secure',k)
        else:d.shell('settings','put','secure',k,v)
    d.restore_animations(animations);subprocess.run(d.adb+['reverse','--remove',f'tcp:{port}'],stdout=subprocess.DEVNULL);server.shutdown();server.server_close()
