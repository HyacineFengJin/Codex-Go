#!/usr/bin/env python3
"""Owned-emulator native Codex tests: WebView semantics, deferred originals, stale-frame rejection.
Uses deterministic localhost model responses; no account or external inference.
"""
import argparse, hashlib, json, re, subprocess, threading, time
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from android_driver import AndroidDriver

p=argparse.ArgumentParser();p.add_argument('--serial',default='emulator-5560');args=p.parse_args()
d=AndroidDriver(args.serial);assert d.safe_ui,'Install the safe UI driver first'
out=Path('.gradle/validation/reports/pre-read');out.mkdir(parents=True,exist_ok=True);(out/'result.json').unlink(missing_ok=True)
requests=[];failures=[];facts={};expected={};gates={};arrived={};measurements=[]
for n in (1,5,9,11,13,15):gates[n]=threading.Event();arrived[n]=threading.Event()
change_ready=threading.Event();changed=threading.Event()
keyboard_ready=threading.Event();keyboard_hidden=threading.Event()
def strings(value):
    if isinstance(value,str):
        yield value
        if value.lstrip().startswith(('{','[')):
            try:yield from strings(json.loads(value))
            except (ValueError,RecursionError):pass
    elif isinstance(value,dict):
        for v in value.values():yield from strings(v)
    elif isinstance(value,list):
        for v in value:yield from strings(v)
def latest_output(body):
    outputs=[i for i in body.get('input',[]) if isinstance(i,dict) and i.get('type')=='function_call_output']
    assert outputs,'No dynamic-tool result reached native Codex'
    return outputs[-1]
def images(value):return [s for s in strings(value) if s.startswith('data:image/')]
def report(value):
    for text in strings(value):
        if 'structure_only' not in text:continue
        for match in re.finditer(r'\{',text):
            try:data,_=json.JSONDecoder().raw_decode(text[match.start():])
            except ValueError:continue
            if isinstance(data,dict) and data.get('interpretation')=='structure_only' and 'frameId' in data:
                m=data['metrics'];assert all(m[k]>=0 for k in ['captureMs','structureMs','encodeMs'])
                assert m['attemptMs']==sum(m[k] for k in ['captureMs','structureMs','encodeMs'])
                assert m['totalMs']>=m['attemptMs'] and 1<=m['attempts']<=3
                assert m['nodeCount']==len(data['elements']) and m['structureBytes']>0 and m['originalPngBytes']>0
                measurements.append(m);facts['localObservationMetricsPresent']=True
                return data
    raise AssertionError('No structural report in tool output')
class Fixture(BaseHTTPRequestHandler):
    def log_message(self,*_):pass
    def do_GET(self):
        body=b'{"data":[]}'
        self.send_response(200);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
    def do_POST(self):
        body=json.loads(self.rfile.read(int(self.headers['Content-Length'])));requests.append(body);n=len(requests)
        print('Fixture request', n, flush=True)
        self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
        def emit(e):self.wfile.write(('event: '+e['type']+'\ndata: '+json.dumps(e)+'\n\n').encode());self.wfile.flush()
        def tool(name,arguments):emit(dict(type='response.output_item.done',item=dict(type='function_call',id=f'fc_pre_{n}',call_id=f'call_pre_{n}',name=name,arguments=json.dumps(arguments))))
        def message(text):
            mid=f'msg_pre_{n}'
            emit(dict(type='response.output_item.added',item=dict(id=mid,type='message',role='assistant',content=[])))
            emit(dict(type='response.output_text.delta',delta=text))
            emit(dict(type='response.output_item.done',item=dict(id=mid,type='message',role='assistant',content=[dict(type='output_text',text=text)])))
        try:
            emit(dict(type='response.created',response=dict(id=f'resp_pre_{n}')))
            if n in gates:
                arrived[n].set();assert gates[n].wait(90),'Host did not open the fixture'
                tool('phone_observe',{})
            elif n==2:
                value=latest_output(body);data=report(value);assert not images(value), 'Simple WebView was unnecessarily sent as an image'
                assert data['source']=='web_accessibility' and not data['rawDomAvailable'] and data['modelUsed'] is None
                assert '网页预读测试' in json.dumps(data,ensure_ascii=False)
                assert any(not e['enabled'] and '不可用按钮' in e['text']+e['description'] for e in data['elements'])
                expected['web']=data['frameId'];facts['webSemanticPreReadWithoutImage']=True;facts['rawFactsAndDisabledStatePreserved']=True
                tool('phone_read_image',dict(frameId=data['frameId']))
            elif n in (3,4):
                value=latest_output(body);pictures=images(value);assert pictures,'Requested original image did not reach Codex: '+str(value)[:1000]
                digest=hashlib.sha256(pictures[-1].encode()).hexdigest()
                if n==3:expected['image']=digest;tool('phone_read_image',dict(frameId=expected['web']))
                else:
                    assert digest==expected['image'];facts['onDemandOriginalReachesCodex']=True;facts['repeatedReadPreservesOriginal']=True;facts['ownOverlayRestorationKeepsEvidence']=True;message('PRE_READ_WEB_OK')
            elif n==6:
                value=latest_output(body);data=report(value);assert not images(value)
                expected['stale']=data['frameId'];change_ready.set();assert changed.wait(60),'Host did not mutate fixture content'
                tool('phone_read_image',dict(frameId=data['frameId']))
            elif n==7:
                value=latest_output(body);text=json.dumps(value,ensure_ascii=False)
                assert not images(value) and any(s in text for s in ['原图不属于','屏幕已变化','观察过期']),text[:1000]
                facts['changedPageRejectsOldOriginal']=True;tool('phone_observe',dict(mode='image'))
            elif n==8:
                value=latest_output(body);data=report(value);assert images(value) and data['frameId']!=expected['stale']
                facts['explicitImageModeCapturesFreshFrame']=True;message('PRE_READ_STALE_OK')
            elif n==10:
                value=latest_output(body);data=report(value);assert images(value) and data['imageIncluded'] and data['unrepresentedVisuals']>0,data
                facts['visualContentAutomaticallyIncludesImage']=True;message('PRE_READ_VISUAL_OK')
            elif n==12:
                value=latest_output(body);data=report(value);nodes=data['elements'];assert not images(value),data['imageReasons']
                field=next(e for e in nodes if e.get('hint')=='请输入搜索词')
                assert field['editable'] and field['showingHint'] and field['error']=='至少两个字符'
                assert any(e.get('heading') and e['text']=='结构预读测试' for e in nodes)
                assert any(e.get('stateDescription')=='尚未选择' for e in nodes)
                assert any(e.get('range',{}).get('current')==37 and e['description']=='音量' for e in nodes)
                facts['placeholderErrorHeadingStateAndRangePreserved']=True;message('PRE_READ_METADATA_OK')
            elif n==14:
                value=latest_output(body);data=report(value)
                assert images(value) and data['unrepresentedVisuals']>0
                assert any(e['description']=='趋势图：本月概览' for e in data['elements'])
                facts['labelledImageStillIncludesPixels']=True;message('PRE_READ_LABELLED_OK')
            elif n==16:
                value=latest_output(body);data=report(value)
                assert images(value) and any(w['type']=='input_method' for w in data['windowCoverage']['windows'])
                facts['keyboardWindowForcesImage']=True;keyboard_ready.set();assert keyboard_hidden.wait(60)
                tool('phone_read_image',dict(frameId=data['frameId']))
            elif n==17:
                value=latest_output(body);text=json.dumps(value,ensure_ascii=False)
                assert not images(value) and any(s in text for s in ['原图不属于','屏幕已变化','观察过期']),text[:1000]
                facts['windowLayoutChangeInvalidatesOriginal']=True;message('PRE_READ_KEYBOARD_OK')
            else:raise AssertionError('Unexpected model request '+str(n))
            emit(dict(type='response.completed',response=dict(id=f'resp_pre_{n}',usage=dict(input_tokens=0,output_tokens=0,total_tokens=0))))
        except Exception as error:
            print('Fixture failure:', error, flush=True)
            failures.append(str(error));message('PRE_READ_FIXTURE_FAILED')
            emit(dict(type='response.completed',response=dict(id=f'resp_pre_{n}',usage=dict(input_tokens=0,output_tokens=0,total_tokens=0))))
        self.close_connection=True

server=ThreadingHTTPServer(('127.0.0.1',0),Fixture);port=server.server_port;threading.Thread(target=server.serve_forever,daemon=True).start()
animations=d.disable_animations();original={}
for path in ['files/codex/config.toml','shared_prefs/codex_go.xml']:
    result=subprocess.run(d.adb+['shell','run-as','dev.codex.go','cat',path],capture_output=True);original[path]=result.stdout if result.returncode==0 else None
access={k:d.shell('settings','get','secure',k).strip() for k in ['enabled_accessibility_services','accessibility_enabled','show_ime_with_hard_keyboard']}
def write(path,data):subprocess.run(d.adb+['shell','run-as','dev.codex.go','sh','-c',"'cat > "+path+"'"],input=data,check=True)
def fixture(mode):
    d.shell('am','force-stop','dev.codex.go.sharefixture')
    d.shell('am','start','-f','0x10008000','-n','dev.codex.go.sharefixture/dev.codex.go.testdriver.ReadingFixtureActivity','--es','mode',mode)
    d.find('网页预读测试' if mode=='web' else '结构预读测试');time.sleep(.5)
    if mode=='keyboard':
        until=time.monotonic()+10
        while 'mInputShown=true' not in d.shell('dumpsys','input_method') and time.monotonic()<until:time.sleep(.2)
        assert 'mInputShown=true' in d.shell('dumpsys','input_method'),'Fixture keyboard did not open'
        time.sleep(.5)
def turn(number,mode,prompt):
    d.shell('am','start','-n','dev.codex.go/.MainActivity');d.find('更多');d.click('新建对话');d.set_text(prompt)
    if 'mInputShown=true' in d.shell('dumpsys','input_method'):d.shell('input','keyevent','4')
    d.click('发送消息');assert arrived[number].wait(20),'No model request';fixture(mode);gates[number].set()
def completed(label):
    end=time.monotonic()+45
    while time.monotonic()<end:
        assert not failures,failures
        if len(requests) in (4,8,10,12,14,17):
            # Native Codex has to finish processing the response before returning to its UI.
            time.sleep(.5);d.shell('am','start','-n','dev.codex.go/.MainActivity')
            if any(n.get('text')==label for n in d.xml().iter('node')):return
        time.sleep(.2)
    raise AssertionError('Turn did not complete: '+label)
try:
    subprocess.run(d.adb+['reverse',f'tcp:{port}',f'tcp:{port}'],check=True,stdout=subprocess.DEVNULL)
    d.shell('run-as','dev.codex.go','mkdir','-p','files/codex')
    write('files/codex/config.toml',f'''model="codex-go-test"
model_provider="pre_fixture"
[analytics]
enabled=false
[model_providers.pre_fixture]
name="Local pre-read fixture"
base_url="http://127.0.0.1:{port}/v1"
wire_api="responses"
requires_openai_auth=false
supports_websockets=false
'''.encode())
    d.shell('pm','grant','dev.codex.go','android.permission.POST_NOTIFICATIONS')
    d.shell('appops','set','dev.codex.go','SYSTEM_ALERT_WINDOW','allow')
    d.start();d.click('打开侧栏');d.click('本机环境');d.click('启动 Codex');d.find('本机 Codex 已就绪',timeout=90)
    # start() force-stops the app, so bind accessibility only after it has restarted.
    d.shell('settings','put','secure','enabled_accessibility_services','dev.codex.go/.phone.PhoneAccessService');d.shell('settings','put','secure','accessibility_enabled','1')
    d.shell('settings','put','secure','show_ime_with_hard_keyboard','1')
    d.click('返回对话')
    d.click('打开侧栏');d.click('手机操作');d.set_switch('预读优先',True);d.capability('phone',True);d.capture(out/'setting.png');d.click('返回对话')
    turn(1,'web','PRE_READ_WEB');completed('PRE_READ_WEB_OK');d.capture(out/'web-result.png')
    turn(5,'native','PRE_READ_CHANGE');assert change_ready.wait(30),'Native pre-read missing';d.click('更新测试内容');d.find('页面内容 B');time.sleep(.4);changed.set();completed('PRE_READ_STALE_OK');d.capture(out/'stale-result.png')
    turn(9,'visual','PRE_READ_VISUAL');completed('PRE_READ_VISUAL_OK');d.capture(out/'visual-result.png')
    turn(11,'metadata','PRE_READ_METADATA');completed('PRE_READ_METADATA_OK');d.capture(out/'metadata-result.png')
    turn(13,'labelled','PRE_READ_LABELLED');completed('PRE_READ_LABELLED_OK');d.capture(out/'labelled-result.png')
    turn(15,'keyboard','PRE_READ_KEYBOARD');assert keyboard_ready.wait(30),'Keyboard observation missing'
    d.shell('input','keyevent','4');time.sleep(.5);keyboard_hidden.set();completed('PRE_READ_KEYBOARD_OK');d.capture(out/'keyboard-result.png')
    (out/'result.json').write_text(json.dumps(dict(version=d.version(),**facts,measurements=measurements,preReadOnlyNeverPlansOrActs=True,externalInference=False,realAccountLogin=False),indent=2)+'\n')
    print('PASS: native pre-read, original-image routing, rich control facts, labelled-image fallback, keyboard coverage, layout invalidation and local metrics',flush=True)
finally:
    for gate in gates.values():gate.set()
    changed.set();keyboard_hidden.set();d.capture(out/'last-screen.png');d.shell('am','force-stop','dev.codex.go')
    for path,data in original.items():
        if data is None:d.shell('run-as','dev.codex.go','rm','-f',path)
        else:write(path,data)
    for key,value in access.items():
        if value=='null':d.shell('settings','delete','secure',key)
        else:d.shell('settings','put','secure',key,value)
    d.restore_animations(animations);subprocess.run(d.adb+['reverse','--remove',f'tcp:{port}'],stdout=subprocess.DEVNULL);server.shutdown()
