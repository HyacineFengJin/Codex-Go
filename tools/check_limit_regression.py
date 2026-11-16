#!/usr/bin/env python3
"""Exercise large-content rendering/export on a disposable emulator, without a model."""
import argparse,hashlib,json,subprocess,time
from pathlib import Path
from android_driver import AndroidDriver

parser=argparse.ArgumentParser();parser.add_argument('--serial',default='emulator-5570');args=parser.parse_args()
d=AndroidDriver(args.serial);out=Path('.gradle/validation/reports/selfcheck');out.mkdir(parents=True,exist_ok=True)
expected=('BEGIN_LONG_REPLY\n'+'Regression line: all text must remain readable.\n'*30000+'END_LONG_REPLY').encode()
d.shell('am','force-stop','dev.codex.go');d.shell('cmd','locale','set-app-locales','dev.codex.go','--locales','en');d.shell('logcat','-c')
d.shell('rm','-f','/sdcard/Download/codex-limit-regression.txt')
d.shell('run-as','dev.codex.go','rm','-f','files/limit-ui-ready','files/limit-ui-stop')
with (out/'ui-driver.log').open('w') as log:
 driver=subprocess.Popen(d.adb+['shell','am','instrument','-w','dev.codex.go.testdriver/.LimitUiDriver'],stdout=log,stderr=subprocess.STDOUT)
 try:
  end=time.monotonic()+20
  while time.monotonic()<end:
   if subprocess.run(d.adb+['shell','run-as','dev.codex.go','test','-f','files/limit-ui-ready'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:break
   if driver.poll() is not None:raise AssertionError((out/'ui-driver.log').read_text())
   time.sleep(.1)
  else:raise AssertionError('Large UI fixture not ready')
  d.click('Read full text');d.find('Full text')
  assert any('BEGIN_LONG_REPLY' in n.get('text','') for n in d.xml().iter('node'))
  d.set_text('END_LONG_REPLY');d.click('Find next')
  assert any('END_LONG_REPLY' in n.get('text','') and n.get('class')!='android.widget.EditText' for n in d.xml().iter('node'))
  d.click('First page')
  assert any('BEGIN_LONG_REPLY' in n.get('text','') for n in d.xml().iter('node')),'Page controls hidden by keyboard'
  d.click('Find next')
  assert any('END_LONG_REPLY' in n.get('text','') and n.get('class')!='android.widget.EditText' for n in d.xml().iter('node'))
  d.shell('input','keyevent','KEYCODE_BACK');d.capture(out/'full-text-end.png')
  d.click('Export')
  d.find(classname='android.widget.EditText');d.set_text('codex-limit-regression.txt')
  # Android's document provider owns this dialog and file name.
  save=next((n for n in d.xml().iter('node') if n.get('text','').lower()=='save'),None)
  if save is None:raise AssertionError('System document Save button missing')
  d.tap(save)
  end=time.monotonic()+10
  exported=None
  while time.monotonic()<end:
   reply=subprocess.run(d.adb+['exec-out','cat','/sdcard/Download/codex-limit-regression.txt'],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
   if reply.returncode==0 and len(reply.stdout)==len(expected):exported=reply.stdout;break
   time.sleep(.15)
  assert exported==expected,'Full text export differs from original'
  d.click('Close');assert any('500 queued' in n.get('text','') for n in d.xml().iter('node'))
  crash=d.shell('logcat','-d','-s','AndroidRuntime')
  assert 'FATAL EXCEPTION' not in crash and 'OutOfMemoryError' not in crash,crash[-2000:]
  apk=Path('.gradle/validation/codex-go-x86_64-debug.apk')
  result=dict(version=d.version(),testedApkSha256=hashlib.file_digest(apk.open('rb'),'sha256').hexdigest(),messageCharacters=len(expected.decode()),queueItems=500,fullTextReader=True,endSearch=True,pageControlsAboveIme=True,fullSafExportSha256=hashlib.sha256(exported).hexdigest(),fullSafExportBytes=len(exported),noFatalOrOom=True,externalInference=False,realAccountLogin=False)
  (out/'result.json').write_text(json.dumps(result,indent=2)+'\n');print('PASS: large reply, 500-item queue, full-text search and byte-exact SAF export')
 finally:
  subprocess.run(d.adb+['shell','run-as','dev.codex.go','touch','files/limit-ui-stop'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
  try:driver.wait(timeout=8)
  except subprocess.TimeoutExpired:driver.terminate();driver.wait(timeout=3)
 d.shell('am','force-stop','dev.codex.go')
 assert 'INSTRUMENTATION_CODE: -1' in (out/'ui-driver.log').read_text(),(out/'ui-driver.log').read_text()
