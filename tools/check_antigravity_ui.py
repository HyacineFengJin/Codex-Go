#!/usr/bin/env python3
"""Owned emulator: official native ACP handshake, engine selection and draft isolation."""
import argparse,json,re,subprocess,time,base64
from pathlib import Path
from android_driver import AndroidDriver
p=argparse.ArgumentParser();p.add_argument('--serial',default='emulator-5560');a=p.parse_args();d=AndroidDriver(a.serial)
out=Path('.gradle/validation/reports/antigravity');out.mkdir(parents=True,exist_ok=True);(out/'result.json').unlink(missing_ok=True)
locale=re.search(r'\[(.*?)\]',d.shell('cmd','locale','get-app-locales','dev.codex.go'))[1];font=d.shell('settings','get','system','font_scale').strip();density=d.shell('wm','density');night=d.shell('cmd','uimode','night').strip().split()[-1];animations=d.disable_animations()
def more(label):d.click('More');d.click(label)
def settings():more('Settings');d.find('Agent')
def capture(name):time.sleep(.25);d.capture(out/(name+'.png'))
def back():d.click('Back to chat')
try:
 result=d.shell('am','instrument','-w','dev.codex.go.testdriver/.AntigravityDriver');(out/'native.log').write_text(result);assert 'INSTRUMENTATION_CODE: -1' in result,result
 facts=json.loads(re.search(r'INSTRUMENTATION_RESULT: facts=(.*)',result)[1]);assert facts['googleMethodAdvertised']
 d.shell('cmd','locale','set-app-locales','dev.codex.go','--locales','en');d.shell('settings','put','system','font_scale','1.0');d.shell('wm','density','420');d.shell('cmd','uimode','night','no')
 d.start();settings();d.click('Antigravity');back();d.set_text('');d.shell('input','keyevent','KEYCODE_BACK');settings();d.click('Codex');back();d.set_text('CODEX_DRAFT_KEEP');d.shell('input','keyevent','KEYCODE_BACK');settings();d.click('Antigravity');d.find('Google account');capture('settings-en')
 back();assert d.find(classname='android.widget.EditText').get('text','')=='','Codex draft leaked into Antigravity'
 d.set_text('ANTIGRAVITY_DRAFT_KEEP');d.shell('input','keyevent','KEYCODE_BACK');settings();d.click('Codex');back();assert d.find(classname='android.widget.EditText').get('text')=='CODEX_DRAFT_KEEP'
 settings();d.click('Antigravity');back();assert d.find(classname='android.widget.EditText').get('text')=='ANTIGRAVITY_DRAFT_KEEP'
 d.start();assert d.find(classname='android.widget.EditText').get('text')=='ANTIGRAVITY_DRAFT_KEEP';settings();d.find('Google account')
 d.shell('cmd','locale','set-app-locales','dev.codex.go','--locales','zh-Hans');d.find('Google 账号');capture('settings-zh')
 d.shell('settings','put','system','font_scale','1.6');d.shell('wm','density','480');d.shell('cmd','uimode','night','yes');d.find('Antigravity');capture('dark-large')
 d.shell('settings','put','system','font_scale','1.0');d.shell('wm','density','420');d.shell('cmd','uimode','night','no');d.shell('cmd','locale','set-app-locales','dev.codex.go','--locales','en')
 d.shell('am','force-stop','dev.codex.go');d.shell('run-as','dev.codex.go','rm','-f','files/login-ui-ready','files/login-ui-stop')
 log=(out/'login-ui.log').open('w');driver=subprocess.Popen(d.adb+['shell','am','instrument','-w','dev.codex.go.testdriver/.AgentLoginUiDriver'],stdout=log,stderr=subprocess.STDOUT)
 try:
  end=time.monotonic()+10
  while time.monotonic()<end:
   if subprocess.run(d.adb+['shell','run-as','dev.codex.go','test','-f','files/login-ui-ready'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:break
   time.sleep(.1)
  d.find('Complete sign-in');edit=next(n for n in d.xml().iter('node') if n.get('class')=='android.widget.EditText' and n.get('password')=='true');d.tap(edit)
  fake='NOT_A_REAL_AUTH_CODE';reply=d.shell('am','instrument','-w','-e','setTextBase64',base64.b64encode(fake.encode()).decode(),'dev.codex.go.testdriver/.UiDriver');assert 'textSet=true' in reply
  assert all(fake not in n.get('text','') for n in d.xml().iter('node')),'Auth code is visible in accessibility text'
  d.shell('input','keyevent','KEYCODE_BACK');capture('google-login');d.click('Cancel sign-in')
  for name in ['codex_go','antigravity_conversations']:
   raw=d.shell('run-as','dev.codex.go','cat','shared_prefs/'+name+'.xml');assert fake not in raw,'Auth code persisted'
 finally:
  d.shell('run-as','dev.codex.go','touch','files/login-ui-stop');driver.wait(timeout=8);log.close()
 assert 'INSTRUMENTATION_CODE: -1' in (out/'login-ui.log').read_text(),(out/'login-ui.log').read_text()
 result=dict(version=d.version(),**facts,googleAuthFormMaskedAndNotPersisted=True,engineSelectionUI=True,separateDraftsAndColdRestart=True,googleAccountUI=True,englishChineseAndDarkLarge=True,externalInference=False,realAccountLogin=False)
 (out/'result.json').write_text(json.dumps(result,indent=2)+'\n');print('PASS: official native ACP, Google auth method, engine UI, isolated drafts and cold restart')
finally:
 d.shell('am','force-stop','dev.codex.go');d.shell('cmd','locale','set-app-locales','dev.codex.go',*(['--locales',locale] if locale else []));d.shell('settings','put','system','font_scale',font);d.shell('wm','density',re.search(r'Override density: (\d+)',density)[1] if 'Override density:' in density else 'reset');d.shell('cmd','uimode','night',night);d.restore_animations(animations)
