#!/usr/bin/env python3
"""Run the APK's real USB shell daemon as Android shell UID on an owned emulator."""
import argparse,json,re,secrets,socket,struct,subprocess,time
from pathlib import Path

p=argparse.ArgumentParser();p.add_argument('--serial',default='emulator-5570');args=p.parse_args()
assert args.serial.startswith('emulator-'),'Disposable emulator required'
adb=['adb','-s',args.serial];out=Path('.gradle/validation/reports/selfcheck');out.mkdir(parents=True,exist_ok=True)
def shell(*parts):return subprocess.check_output(adb+['shell',*parts],text=True)
marker='/data/local/tmp/codex-go-selfcheck-started'
def exact(stream,n):
 data=bytearray()
 while len(data)<n:
  part=stream.recv(n-len(data))
  if not part:raise EOFError('USB daemon disconnected')
  data.extend(part)
 return bytes(data)
def utf(stream,text):
 data=text.encode();stream.sendall(struct.pack('>H',len(data))+data)
def action(stream,kind,key):utf(stream,kind);utf(stream,key)
def execute(stream,key,command):
 action(stream,'execute',key);data=command.encode();stream.sendall(struct.pack('>i',len(data))+data+struct.pack('>q',0))
def reply(stream):
 key=exact(stream,struct.unpack('>H',exact(stream,2))[0]).decode();data=exact(stream,struct.unpack('>i',exact(stream,4))[0]);return key,json.loads(data) if data else None

with socket.socket() as listener:
 listener.bind(('127.0.0.1',0));listener.listen(1);listener.settimeout(12)
 port=subprocess.check_output(adb+['reverse','tcp:0',f'tcp:{listener.getsockname()[1]}'],text=True).strip();assert re.fullmatch(r'\d+',port)
 token=secrets.token_hex(32);process=None
 try:
  command='CLASSPATH="$(pm path dev.codex.go | cut -d: -f2)" app_process /system/bin dev.codex.go.shell.WiredShellDaemon '+port+' '+token
  process=subprocess.Popen(adb+['shell',command],stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
  stream,_=listener.accept()
  with stream:
   stream.settimeout(8);size=struct.unpack('>H',exact(stream,2))[0];assert secrets.compare_digest(exact(stream,size).decode(),token);utf(stream,'CODEX_GO_SHELL_V2')
   execute(stream,'identity','id');key,value=reply(stream);assert key=='identity' and 'uid=2000' in value['stdout'],value
   execute(stream,'unicode',"printf '你好' #"+'界'*22000);key,value=reply(stream);assert key=='unicode' and value['stdout']=='你好',value
   for i in range(20):execute(stream,str(i),'printf '+str(i))
   for i in range(20):key,value=reply(stream);assert key==str(i) and value['stdout']==str(i),(key,value)
   shell('rm','-f',marker);execute(stream,'long',f'echo started > {marker}; exec sleep 30')
   deadline=time.monotonic()+5
   while time.monotonic()<deadline:
    if subprocess.run(adb+['shell','test','-f',marker],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:break
    time.sleep(.05)
   else:raise AssertionError('Native shell did not start')
   action(stream,'cancel','long');execute(stream,'retry','printf recovered')
   replies=dict(reply(stream) for _ in range(2));assert replies['long']['exitCode']!=0 and replies['retry']['stdout']=='recovered',replies
   action(stream,'ping','alive');assert reply(stream)==('alive',None)
  result=dict(actualAndroidShellUid2000=True,apkAppProcessDaemon=True,v2Utf8CommandBytes=len(("printf '你好' #"+'界'*22000).encode()),rapidCommands=20,cancelAndImmediateRetry=True,pingAfterRetry=True)
  (out/'wired-result.json').write_text(json.dumps(result,indent=2)+'\n');print('PASS: Android APK daemon UID 2000, V2 Unicode frame, rapid commands, cancel/retry and ping')
 finally:
  subprocess.run(adb+['reverse','--remove','tcp:'+port],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
  shell('rm','-f',marker)
  if process:
   try:process.wait(timeout=5)
   except subprocess.TimeoutExpired:process.terminate();process.wait(timeout=3)
