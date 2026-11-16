#!/usr/bin/env python3
"""Owned emulator + real native Codex; localhost model fixtures, no real login/inference."""
import argparse,hashlib,json,subprocess,threading,time
from pathlib import Path
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from android_driver import AndroidDriver

p=argparse.ArgumentParser();p.add_argument('--serial',default='emulator-5560');args=p.parse_args()
d=AndroidDriver(args.serial);assert d.safe_ui
out=Path('.gradle/validation/reports/model-pre-read');out.mkdir(parents=True,exist_ok=True);(out/'result.json').unlink(missing_ok=True)
cases=['SUCCESS','STRUCTURE','BYPASS','INVALID','TOOL','UNAVAILABLE','TIMEOUT','STALE','UNCERTAIN','CANCEL']
arrived={k:threading.Event() for k in cases};gates={k:threading.Event() for k in cases}
stale_ready=threading.Event();stale_release=threading.Event();timeout_release=threading.Event()
cancel_ready=threading.Event();cancel_release=threading.Event()
steps={};facts={};failures=[];helper_cases=[];active='';expected_image='';frame=''
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
def images(value):return [s for s in strings(value) if s.startswith('data:image/')]
def latest(body):return [i for i in body.get('input',[]) if i.get('type')=='function_call_output'][-1]
def report(value):
    for text in strings(value):
        for line in text.splitlines():
            if not line.startswith('{'):continue
            try:r=json.loads(line)
            except ValueError:continue
            if r.get('interpretation') in ['structure_only','assisted_pre_read'] and 'frameId' in r:return r
    raise AssertionError('No screen report: '+str(value)[:700])
def user(body):
    for item in reversed(body.get('input',[])):
        if item.get('role')=='user':return '\n'.join(x.get('text','') for x in item.get('content',[]) if isinstance(x,dict))
    return ''
class Fixture(BaseHTTPRequestHandler):
    def log_message(self,*_):pass
    def do_GET(self):
        b=b'{"data":[]}';self.send_response(200);self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
    def do_POST(self):
        global frame,expected_image
        body=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        helper=body.get('model')=='gpt-6-luna';case=active
        if helper and case=='UNAVAILABLE':
            helper_cases.append(case)
            assert not body.get('tools')
            error=json.dumps(dict(error=dict(message='Fixture authentication failure',type='authentication_error',code='invalid_api_key'))).encode()
            self.send_response(401);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(error)));self.end_headers();self.wfile.write(error);self.close_connection=True;return
        self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
        ident=('helper_' if helper else 'main_')+case+'_'+str(steps.get(case,0))
        def emit(e):self.wfile.write(('event: '+e['type']+'\ndata: '+json.dumps(e)+'\n\n').encode());self.wfile.flush()
        def message(text):
            emit(dict(type='response.output_item.added',item=dict(id='msg_'+ident,type='message',role='assistant',content=[])))
            emit(dict(type='response.output_text.delta',delta=text))
            emit(dict(type='response.output_item.done',item=dict(id='msg_'+ident,type='message',role='assistant',content=[dict(type='output_text',text=text)])))
        def tool(name,args):emit(dict(type='response.output_item.done',item=dict(type='function_call',id='fc_'+ident,call_id='call_'+ident,name=name,arguments=json.dumps(args))))
        try:
            emit(dict(type='response.created',response=dict(id='r_'+ident)))
            if helper:
                helper_cases.append(case);print('Helper',case,flush=True)
                (out/'helper-request.json').write_text(json.dumps(body,ensure_ascii=False))
                assert not body.get('tools'), 'Pre-reader was given tools: '+str([(t.get('name'),t.get('type')) for t in body.get('tools',[])])
                assert not any('MODEL_CASE_' in s for s in strings(body)), 'User task/history leaked into helper request'
                pictures=images(body);assert pictures,'Helper received no original screenshot'
                facts['helperHasNoTools']=True;facts['helperReceivesOnlyCurrentFrame']=True
                if case=='SUCCESS':expected_image=hashlib.sha256(pictures[-1].encode()).hexdigest()
                if case=='STALE':stale_ready.set();assert stale_release.wait(45)
                if case=='TIMEOUT':timeout_release.wait(25)
                if case=='CANCEL':cancel_ready.set();cancel_release.wait(25)
                if case=='TOOL':
                    tool('exec_command',dict(cmd='touch /workspace/PREREAD_MUST_NOT_EXIST'));time.sleep(.2)
                else:
                    value=dict(texts=['界面预读示例'] if case=='PREVIEW' else ['结构预读测试','页面内容 A'],visuals=['本地响应夹具验证'] if case=='PREVIEW' else ['浅蓝区域中的绿色圆形'],uncertainties=['小字无法读清'] if case=='UNCERTAIN' else [])
                    if case=='INVALID':value['actions']=['PRE_READ_MUST_NOT_EXECUTE']
                    message(json.dumps(value,ensure_ascii=False))
            else:
                assert body.get('model')!='gpt-6-luna','Main model was overwritten'
                assert 'MODEL_CASE_'+case in user(body),(case,user(body)[-100:])
                steps[case]=steps.get(case,0)+1;n=steps[case];print('Main',case,n,flush=True)
                if n==1:
                    arrived[case].set();assert gates[case].wait(90)
                    tool('phone_observe',dict(mode='image') if case=='BYPASS' else {})
                else:
                    value=latest(body);pictures=images(value)
                    if case=='SUCCESS' and n==2:
                        r=report(value);assert not pictures and r['modelUsed']=='gpt-6-luna' and r['imageDeferredForCodex']
                        assert r['modelPreRead']['facts']['visuals']==['浅蓝区域中的绿色圆形'] and r['unrepresentedVisuals']>0
                        frame=r['frameId'];facts['modelFactsReachMainCodex']=True;tool('phone_read_image',dict(frameId=frame))
                    else:
                        if case=='SUCCESS':
                            assert pictures and hashlib.sha256(pictures[-1].encode()).hexdigest()==expected_image
                            facts['mainCanReadExactSameOriginal']=True
                        elif case=='BYPASS':
                            assert pictures and report(value)['interpretation']=='structure_only' and case not in helper_cases
                            facts['explicitImageBypassesHelper']=True
                        elif case=='STRUCTURE':
                            assert not pictures and report(value)['interpretation']=='structure_only' and case not in helper_cases
                            facts['completeStructureSkipsModel']=True
                        elif case=='STALE':
                            assert not pictures and '预读期间变化' in json.dumps(value,ensure_ascii=False)
                            assert '浅蓝区域中的绿色圆形' not in json.dumps(value,ensure_ascii=False)
                            facts['staleModelResultDiscarded']=True
                        elif case=='CANCEL':
                            assert not pictures and '手机能力已暂停' in json.dumps(value,ensure_ascii=False)
                            facts['temporaryPauseCancelsHelper']=True
                        else:
                            r=report(value);assert pictures and r['imageIncluded']
                            if case=='UNCERTAIN':
                                assert r['modelPreRead']['status']=='succeeded' and r['modelPreRead']['facts']['uncertainties']
                                facts['uncertainReadingIncludesOriginal']=True
                            else:
                                assert r['modelPreRead']['status']=='failed',r['modelPreRead']
                                if case=='TOOL':assert r['modelPreRead']['failure']=='tool_attempt',r['modelPreRead']
                                if case=='UNAVAILABLE':assert r['modelPreRead']['failure']=='unavailable',r['modelPreRead']
                                if case=='TIMEOUT':assert r['modelPreRead']['failure']=='timeout',r['modelPreRead']
                                assert 'PRE_READ_MUST_NOT_EXECUTE' not in json.dumps(value)
                                facts[case.lower()+'FallsBackToOriginal']=True
                        message('MODEL_'+case+'_OK')
            emit(dict(type='response.completed',response=dict(id='r_'+ident,usage=dict(input_tokens=0,output_tokens=0,total_tokens=0))))
        except (BrokenPipeError,ConnectionResetError):pass
        except Exception as error:
            failures.append(str(error));print('FAIL:',case,error,flush=True)
            try:message('MODEL_FIXTURE_FAILED');emit(dict(type='response.completed',response=dict(id='r_'+ident)))
            except (BrokenPipeError,ConnectionResetError):pass
        self.close_connection=True

server=ThreadingHTTPServer(('127.0.0.1',0),Fixture);server.daemon_threads=True;port=server.server_port
threading.Thread(target=server.serve_forever,daemon=True).start()
animations=d.disable_animations();original={}
for path in ['files/codex/config.toml','shared_prefs/codex_go.xml']:
    r=subprocess.run(d.adb+['shell','run-as','dev.codex.go','cat',path],capture_output=True);original[path]=r.stdout if r.returncode==0 else None
access={k:d.shell('settings','get','secure',k).strip() for k in ['enabled_accessibility_services','accessibility_enabled']}
def write(path,data):
    if path=='shared_prefs/codex_go.xml':
        subprocess.run(d.adb+['shell','run-as','dev.codex.go','sh','-c',"'cat > shared_prefs/codex_go.xml'"],input=data,check=True)
    else:d.private_write(path,data)
def fixture(mode):
    d.shell('am','force-stop','dev.codex.go.sharefixture');d.shell('am','start','-n','dev.codex.go.sharefixture/dev.codex.go.testdriver.ReadingFixtureActivity','--es','mode',mode)
    d.find('结构预读测试');time.sleep(.5)
def start(case):
    global active
    active=case;d.shell('am','start','-n','dev.codex.go/.MainActivity');d.find('更多');d.click('新建对话');d.set_text('MODEL_CASE_'+case)
    if 'mInputShown=true' in d.shell('dumpsys','input_method'):d.shell('input','keyevent','4')
    d.click('发送消息');assert arrived[case].wait(25),'Main model never started'
    fixture('native' if case=='STRUCTURE' else 'visual');gates[case].set()
def completed(case):
    end=time.monotonic()+50
    while time.monotonic()<end:
        assert not failures,failures
        if steps.get(case,0)>=(3 if case=='SUCCESS' else 2):
            d.shell('am','start','-n','dev.codex.go/.MainActivity')
            if any(n.get('text')=='MODEL_'+case+'_OK' for n in d.xml().iter('node')):return
        time.sleep(.2)
    raise AssertionError('Incomplete model pre-read case '+case)
try:
    subprocess.run(d.adb+['reverse',f'tcp:{port}',f'tcp:{port}'],check=True,stdout=subprocess.DEVNULL)
    d.shell('run-as','dev.codex.go','mkdir','-p','files/codex')
    write('files/codex/config.toml',f'''model="codex-go-test"
model_provider="pre_read_fixture"
[analytics]
enabled=false
[model_providers.pre_read_fixture]
name="Local model pre-read fixture"
base_url="http://127.0.0.1:{port}/v1"
wire_api="responses"
requires_openai_auth=false
supports_websockets=false
'''.encode())
    d.shell('pm','grant','dev.codex.go','android.permission.POST_NOTIFICATIONS')
    d.start();d.click('打开侧栏');d.click('本机环境');d.click('启动 Codex');d.find('本机 Codex 已就绪',timeout=100)
    d.shell('settings','put','secure','enabled_accessibility_services','dev.codex.go/.phone.PhoneAccessService');d.shell('settings','put','secure','accessibility_enabled','1')
    d.click('返回对话');d.click('打开侧栏');d.click('手机操作');d.set_switch('预读优先',True)
    d.scroll_click('选择预读模型');d.find('选择预读模型');d.set_text('gpt-6-luna');d.click('使用 gpt-6-luna 预读')
    d.set_switch('轻量模型辅助预读',True);d.capability('phone',True);d.capture(out/'settings.png')
    active='PREVIEW';d.scroll_click('测试辅助预读');d.find('预读取结果');d.find('辅助模型 · gpt-6-luna');d.find('界面预读示例');d.capture(out/'helper-preview.png');d.click('完成')
    facts['helperPreviewShowsFactsAndModel']=True;d.click('返回对话')
    for case in cases:
        start(case)
        if case=='STALE':
            assert stale_ready.wait(25),'Stale helper case did not start';d.click('更新测试内容');d.find('页面内容 B');time.sleep(.4);stale_release.set()
        if case=='CANCEL':
            assert cancel_ready.wait(25),'Cancel helper case did not start'
            d.shell('am','start','-n','dev.codex.go/.MainActivity');d.click('更多');d.click('手机操作');d.capability('phone',False);d.click('返回对话')
        completed(case);d.capture(out/(case.lower()+'.png'))
        if case=='TIMEOUT':timeout_release.set()
        if case=='CANCEL':cancel_release.set()
    assert set(helper_cases)==(set(cases)-{'BYPASS','STRUCTURE'})|{'PREVIEW'},helper_cases
    forbidden=subprocess.run(d.adb+['shell','run-as','dev.codex.go','test','-e','files/workspace/PREREAD_MUST_NOT_EXIST']).returncode
    assert forbidden!=0,'Pre-reader executed a command'
    facts['preReaderNeverExecutesCommands']=True;facts['mainModelSelectionUnchanged']=True;facts['helperCallCount']=len(helper_cases)
    (out/'result.json').write_text(json.dumps(dict(version=d.version(),**facts,externalInference=False,realAccountLogin=False),indent=2)+'\n')
    print('PASS: isolated Codex/Luna pre-read, no tools/history, same-frame original, image bypass, invalid/tool/timeout fallbacks, stale discard and uncertainty',flush=True)
finally:
    for gate in gates.values():gate.set()
    stale_release.set();timeout_release.set();cancel_release.set()
    try:d.capture(out/'last-screen.png')
    except subprocess.CalledProcessError:pass
    subprocess.run(d.adb+['wait-for-device'],timeout=15,check=True)
    d.shell('am','force-stop','dev.codex.go')
    for path,data in original.items():
        if data is None:d.shell('run-as','dev.codex.go','rm','-f',path)
        else:write(path,data)
    for key,value in access.items():
        if value=='null':d.shell('settings','delete','secure',key)
        else:d.shell('settings','put','secure',key,value)
    d.restore_animations(animations);subprocess.run(d.adb+['reverse','--remove',f'tcp:{port}'],stdout=subprocess.DEVNULL);server.shutdown();server.server_close()
