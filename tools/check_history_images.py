#!/usr/bin/env python3
"""Owned emulator only: real Codex/MCP/Android codec; deterministic localhost Responses fixtures."""
import argparse, base64, hashlib, io, json, re, subprocess, threading, time, uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from PIL import Image
from android_driver import AndroidDriver

p=argparse.ArgumentParser(); p.add_argument('--serial',default='emulator-5560'); p.add_argument('--builtin-openai',action='store_true'); args=p.parse_args()
d=AndroidDriver(args.serial); assert d.safe_ui
assert d.shell('getprop','ro.hardware').strip() in ('ranchu','goldfish')
out=Path('.gradle/validation/reports')/('history-images-openai' if args.builtin_openai else 'history-images'); out.mkdir(parents=True,exist_ok=True); (out/'result.json').unlink(missing_ok=True)
counts={}; failures=[]; requests=[]; checks={}; original_id=''; original_bytes=b''; live_frame=''
token=uuid.uuid4().hex[:8]

def texts(value):
    if isinstance(value,dict):
        for k,v in value.items():
            if k in ('text','output') and isinstance(v,str): yield v
            elif isinstance(v,(dict,list)): yield from texts(v)
    elif isinstance(value,list):
        for v in value: yield from texts(v)
def picture_urls(value):
    if isinstance(value,dict):
        if value.get('type')=='input_image' and isinstance(value.get('image_url'),str): yield value['image_url']
        for v in value.values():
            if isinstance(v,(dict,list)): yield from picture_urls(v)
    elif isinstance(value,list):
        for v in value: yield from picture_urls(v)
def picture(url):
    data=base64.b64decode(url.split(',',1)[1]); im=Image.open(io.BytesIO(data)); return data,im.size,im.format
def tools(body):
    found=[]
    def visit(v, namespace=None):
        if isinstance(v,dict):
            if v.get('type')=='namespace': namespace=v.get('name')
            if v.get('type')=='function' and isinstance(v.get('name'),str): found.append((namespace+'.' if namespace else '')+v['name'])
            for x in v.values():
                if isinstance(x,(dict,list)):visit(x,namespace)
        elif isinstance(v,list):
            for x in v:visit(x,namespace)
    visit(body.get('tools',[]));return found
def last_user(body):
    users=[v for v in body.get('input',[]) if isinstance(v,dict) and v.get('role')=='user']
    return '\n'.join(texts(users[-1])) if users else ''
def case_for(body):
    value=last_user(body)
    return next((c for c in ['LEGACY','OLD','LIVE','AFTER','RESTART','SCOPE','BROKEN_CAPTURE','BROKEN_OLD','OFF'] if 'HISTORY_CASE_'+c in value),None)

class Fixture(BaseHTTPRequestHandler):
    def log_message(self,*_):pass
    def do_GET(self):
        if self.headers.get('Upgrade','').lower()=='websocket':
            self.send_response(426);self.send_header('Content-Length','0');self.end_headers();self.close_connection=True;return
        b=b'{"data":[]}';self.send_response(200);self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
    def do_POST(self):
        global live_frame
        body=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        requests.append(body);case=case_for(body);counts[case]=counts.get(case,0)+1;n=counts[case]
        (out/f'request-{case}-{n}.json').write_text(json.dumps(body,ensure_ascii=False))
        print('Request',case,n,'images',len(list(picture_urls(body))),flush=True)
        self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
        ident=f'{case}_{n}'
        def event(v):self.wfile.write(('event: '+v['type']+'\ndata: '+json.dumps(v)+'\n\n').encode());self.wfile.flush()
        def message(text):event(dict(type='response.output_item.done',item=dict(id='msg_'+ident,type='message',role='assistant',content=[dict(type='output_text',text=text)])))
        def tool(name,arguments):
            item=dict(id='fc_'+ident,type='function_call',call_id='call_'+ident,name=name.rsplit('.',1)[-1],arguments=json.dumps(arguments))
            if '.' in name:item['namespace']=name.rsplit('.',1)[0]
            event(dict(type='response.output_item.done',item=item))
        try:
            if args.builtin_openai:
                assert self.headers.get('Authorization')=='Bearer history-fixture-key','Built-in provider lost its native API-key auth'
                checks['builtinOpenaiNativeAuthPreserved']=True
            event(dict(type='response.created',response=dict(id='resp_'+ident)))
            pics=list(picture_urls(body)); names=tools(body)
            read=next((name for name in names if 'codex_go_history' in name and name.endswith('read_image')),None)
            if case=='LEGACY':
                assert read is None,'Feature-off request unexpectedly has history tools'
                if n==1:tool('phone_observe',{'mode':'image'})
                else:
                    assert pics and picture(pics[-1])[2]=='PNG'
                    checks['legacyNativeContextContainsScreenshot']=True;message('HISTORY_LEGACY_OK')
            elif case in ('OLD','RESTART','AFTER'):
                assert read,'Native MCP history read tool missing: '+str(names)
                if n==1:
                    assert pics and all(picture(x)[2]=='JPEG' and max(picture(x)[1])<=768 for x in pics),'Old images were not all reduced'
                    assert original_id in json.dumps(body),'Original reference was lost'
                    checks['oldImagesActuallyReducedInModelRequests']=True
                    tool(read,{'id':original_id})
                else:
                    assert pics and picture(pics[-1])[0]==original_bytes,'Re-read is not the byte-exact full original'
                    assert picture(pics[-1])[1]==(1080,2400),picture(pics[-1])[1]
                    assert all(picture(x)[2]=='JPEG' for x in pics[:-1]),'Earlier images stopped being compact'
                    checks['fullOriginalRestoredAfterNativeImagePreparation']=True
                    if case=='RESTART':checks['originalAndReferenceSurviveRestart']=True
                    if case=='AFTER':checks['reReadImageBecomesCompactOnFollowingTurn']=True
                    if case=='AFTER' and n==2:
                        tool('phone_read_image',{'frameId':live_frame})
                    else:
                        if case=='AFTER':
                            last=[v for v in body.get('input',[]) if v.get('type')=='function_call_output'][-1]
                            assert any('观察' in s for s in texts(last)), 'Historical read retained the live same-frame image cache'
                            checks['historicalReadClearsLiveImageCache']=True
                        message('HISTORY_'+case+'_OK')
            elif case=='LIVE':
                if n==1:tool('phone_observe',{'mode':'image'})
                else:
                    assert pics and picture(pics[-1])[2]=='PNG','Current observation was compressed'
                    assert all(picture(x)[2]=='JPEG' for x in pics[:-1]),'Old history was not compressed'
                    assert any('frameId' in s and 'coordinateSpace' in s for s in texts(body))
                    last=[v for v in body.get('input',[]) if v.get('type')=='function_call_output'][-1]
                    for text in texts(last):
                        for line in text.splitlines():
                            try:report=json.loads(line)
                            except ValueError:continue
                            if isinstance(report,dict) and 'frameId' in report:live_frame=report['frameId']
                    assert live_frame
                    checks['currentObservationKeepsNativeImageAndCoordinates']=True;message('HISTORY_LIVE_OK')
            elif case=='SCOPE':
                assert not pics,'Another conversation image leaked into a new thread'
                if n==1:
                    assert read;tool(read,{'id':original_id})
                else:
                    assert any('历史图片读取失败' in s for s in texts(body))
                    checks['otherThreadCannotReadOriginal']=True;message('HISTORY_SCOPE_OK')
            elif case=='BROKEN_CAPTURE':
                assert read is None,'Deliberately broken history helper unexpectedly connected'
                if n==1:tool('phone_observe',{'mode':'image'})
                else:
                    assert pics and picture(pics[-1])[2]=='PNG';message('HISTORY_BROKEN_CAPTURE_OK')
            elif case=='BROKEN_OLD':
                assert read is None and pics and all(picture(x)[2]=='PNG' for x in pics),'Disconnected history helper must keep full native images'
                checks['missingReaderFallsBackToFullNativeImages']=True;message('HISTORY_BROKEN_OLD_OK')
            elif case=='OFF':
                assert read is None,'Disabled feature retained MCP history tools'
                assert pics and all(picture(x)[2]=='PNG' for x in pics),'Disabled feature still modifies old image input'
                checks['disableRestoresNativeRequestAndTools']=True;message('HISTORY_OFF_OK')
            else:raise AssertionError('Unknown fixture request')
            event(dict(type='response.completed',response=dict(id='resp_'+ident,usage=dict(input_tokens=0,output_tokens=0,total_tokens=0))))
        except (BrokenPipeError,ConnectionResetError):pass
        except Exception as error:
            failures.append(str(error));print('FAIL',case,error,flush=True)
            try:message('HISTORY_FIXTURE_FAILED');event(dict(type='response.completed',response=dict(id='resp_'+ident)))
            except Exception:pass
        self.close_connection=True

server=ThreadingHTTPServer(('127.0.0.1',0),Fixture);server.daemon_threads=True;port=server.server_port
threading.Thread(target=server.serve_forever,daemon=True).start()
original={}
for path in ['files/codex/config.toml','shared_prefs/codex_go.xml']+(['files/codex/auth.json'] if args.builtin_openai else []):
    r=subprocess.run(d.adb+['shell','run-as','dev.codex.go','cat',path],capture_output=True);original[path]=r.stdout if r.returncode==0 else None
animations=d.disable_animations()
access={k:d.shell('settings','get','secure',k).strip() for k in ['enabled_accessibility_services','accessibility_enabled']}
def settings():d.click('打开侧栏');d.click('本机环境')
def enable_phone():
    # am force-stop deliberately disconnects accessibility on this owned emulator.
    d.shell('settings','put','secure','accessibility_enabled','0')
    d.shell('settings','put','secure','enabled_accessibility_services','dev.codex.go/.phone.PhoneAccessService')
    d.shell('settings','put','secure','accessibility_enabled','1')
    if any(n.get('content-desc')=='返回对话' or n.get('text')=='返回对话' for n in d.xml().iter('node')):d.click('返回对话')
    d.click('打开侧栏');d.click('手机操作');d.capability('phone',True);d.click('返回对话')
def hide_keyboard():
    if re.search(r'\bmInputShown=true\b',d.shell('dumpsys','input_method')):d.shell('input','keyevent','4')
def send(case):
    d.set_text('HISTORY_CASE_'+case+'_'+token);d.click('发送消息')
    end=time.monotonic()+100
    while time.monotonic()<end:
        assert not failures,failures
        nodes=list(d.xml().iter('node'))
        if any(x.get('text')=='允许本次' for x in nodes):d.click('允许本次')
        if any(x.get('text')=='HISTORY_'+case+'_OK' for x in nodes):hide_keyboard();time.sleep(.3);return
    raise AssertionError('Case did not finish: '+case)
def prefs():
    import xml.etree.ElementTree as E
    return E.fromstring(d.shell('run-as','dev.codex.go','cat','shared_prefs/codex_go.xml'))
def selected_thread():return next(n.text for n in prefs() if n.get('name')=='thread')
def read_private(path):return subprocess.check_output(d.adb+['exec-out','run-as','dev.codex.go','cat',path])
def read_legacy_original(thread):
    paths=d.shell('run-as','dev.codex.go','find','files/codex/sessions','-type','f').splitlines()
    path=next(x for x in paths if x.endswith('-'+thread+'.jsonl'))
    data=read_private(path)
    for line in data.splitlines():
        row=json.loads(line);payload=row.get('payload',{});item=payload.get('item',{})
        if row.get('type')!='event_msg' or payload.get('type')!='item_completed' or item.get('type')!='DynamicToolCall':continue
        if item.get('id')!='call_LEGACY_1':continue
        pictures=item.get('content_items',item.get('contentItems',[]))
        for v in pictures:
            value=v.get('imageUrl',v.get('image_url',''))
            if value.startswith('data:image/'):
                raw=base64.b64decode(value.split(',',1)[1]);assert Image.open(io.BytesIO(raw)).size==(1080,2400)
                return raw,path,data
    raise AssertionError('No full native legacy screenshot event found')

try:
    subprocess.run(d.adb+['reverse',f'tcp:{port}',f'tcp:{port}'],check=True,stdout=subprocess.DEVNULL)
    d.shell('am','force-stop','dev.codex.go');d.shell('run-as','dev.codex.go','mkdir','-p','files/codex')
    config=f'''model="codex-go-test"
model_provider="history_fixture"
[analytics]
enabled=false
[model_providers.history_fixture]
name="Local history image fixture"
base_url="http://127.0.0.1:{port}/v1"
wire_api="responses"
requires_openai_auth=false
supports_websockets=false
'''.encode()
    if args.builtin_openai:
        config=f'''model="codex-go-test"
model_provider="openai"
openai_base_url="http://127.0.0.1:{port}/v1"
cli_auth_credentials_store="file"
[analytics]
enabled=false
'''.encode()
        d.private_write('files/codex/auth.json',json.dumps({'OPENAI_API_KEY':'history-fixture-key'}).encode())
    d.private_write('files/codex/config.toml',config)
    d.shell('pm','grant','dev.codex.go','android.permission.POST_NOTIFICATIONS')
    d.start();settings();d.click('启动本机 Codex');d.find('本机 Codex 已就绪',timeout=100)
    d.shell('settings','put','secure','enabled_accessibility_services','dev.codex.go/.phone.PhoneAccessService');d.shell('settings','put','secure','accessibility_enabled','1')
    enable_phone();send('LEGACY')
    legacy_thread=selected_thread();original_bytes,legacy_path,legacy_prefix=read_legacy_original(legacy_thread)
    original_id=hashlib.sha256(original_bytes).hexdigest()
    assert subprocess.run(d.adb+['shell','run-as','dev.codex.go','ls','files/history-images/'+legacy_thread],capture_output=True).returncode!=0
    settings();d.set_switch('压缩历史图片',True)
    for _ in range(6):d.shell('input','swipe','530','800','530','1950','200')
    d.find('本机 Codex 已就绪',timeout=100)
    assert read_private('files/codex/config.toml')==config,'User config was rewritten'
    checks['legacyOriginalImportedWithoutChangingConfig']=True
    enable_phone();send('OLD');send('LIVE');send('AFTER')
    d.capture(out/'restored-original.png')
    d.start();settings();d.click('启动本机 Codex');d.find('本机 Codex 已就绪',timeout=100);d.click('返回对话');send('RESTART')
    d.click('新建对话');send('SCOPE')
    helper=read_private('files/runtime-helpers/history_mcp.py')
    d.private_write('files/runtime-helpers/history_mcp.py',b'raise RuntimeError("Owned emulator failure fixture")\n')
    enable_phone();d.click('新建对话');send('BROKEN_CAPTURE');send('BROKEN_OLD')
    d.private_write('files/runtime-helpers/history_mcp.py',helper)
    settings();d.set_switch('压缩历史图片',False)
    for _ in range(6):d.shell('input','swipe','530','800','530','1950','200')
    d.find('本机 Codex 已就绪',timeout=100);d.click('返回对话');d.click('打开侧栏');d.click('HISTORY_CASE_LEGACY_'+token);send('OFF')
    assert read_private('files/codex/config.toml')==config
    assert read_private(legacy_path).startswith(legacy_prefix),'Native legacy transcript was rewritten'
    checks['nativeOriginalTranscriptPrefixPreserved']=True
    checks['noExternalModelOrRealAccountUsed']=True
    if args.builtin_openai:checks['builtinOpenaiStartResumeAndHttpFallback']=True
    result=dict(version=d.version(),**checks,requestCount=len(requests),externalInference=False,realAccountLogin=False)
    (out/'result.json').write_text(json.dumps(result,indent=2)+'\n');print('PASS: legacy history compression, native MCP exact originals, live image preservation, restart, thread scoping and disable',flush=True)
finally:
    try:d.capture(out/'last-screen.png')
    except Exception:pass
    d.shell('am','force-stop','dev.codex.go')
    for path,data in original.items():
        if data is None:d.shell('run-as','dev.codex.go','rm','-f',path)
        else:subprocess.run(d.adb+['shell','run-as','dev.codex.go','sh','-c',"'cat > "+path+"'"],input=data,check=True)
    for key,value in access.items():
        if value=='null':d.shell('settings','delete','secure',key)
        else:d.shell('settings','put','secure',key,value)
    d.restore_animations(animations);subprocess.run(d.adb+['reverse','--remove',f'tcp:{port}'],stdout=subprocess.DEVNULL);server.shutdown()
