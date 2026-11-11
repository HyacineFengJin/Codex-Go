#!/usr/bin/env python3
"""Owned-emulator language selection, real drafts, native resource formatting and app content."""
import argparse,json,re,subprocess,time,xml.etree.ElementTree as ET
from pathlib import Path
from PIL import Image
from android_driver import AndroidDriver
p=argparse.ArgumentParser();p.add_argument('--serial',default='emulator-5560');a=p.parse_args();d=AndroidDriver(a.serial);assert d.safe_ui
out=Path('.gradle/validation/reports/localization');out.mkdir(parents=True,exist_ok=True);(out/'result.json').unlink(missing_ok=True)
def read(path):
 if subprocess.run(d.adb+['shell','run-as','dev.codex.go','test','-f',path],stdout=subprocess.DEVNULL).returncode:return None
 return subprocess.check_output(d.adb+['exec-out','run-as','dev.codex.go','cat',path])
def write(path,data):subprocess.run(d.adb+['shell','run-as','dev.codex.go','sh','-c',"'cat > "+path+"'"],input=data,check=True)
def locales():return re.search(r'\[(.*?)\]',d.shell('cmd','locale','get-app-locales','dev.codex.go'))[1]
def set_locale(tag):d.shell('cmd','locale','set-app-locales','dev.codex.go',*(['--locales',tag] if tag else []))
def capture(name):d.capture(out/(name+'.png'))
def labels():return [n.get('text','') or n.get('content-desc','') for n in d.xml().iter('node')]
def no_chinese_chrome():assert not any(re.search(r'[\u3400-\u9fff]',x) for x in labels()),labels()
def more(label,zh=False):d.click('更多' if zh else 'More');d.click(label)
def settings(zh=False):more('设置' if zh else 'Settings',zh);d.find('语言' if zh else 'Language')
def select_language(label,zh=False):
 d.click('语言' if zh else 'Language');d.find('应用语言' if zh else 'App language');capture('language-picker-zh' if zh else 'language-picker-en');d.click(label)
def back(zh=False):d.click('返回对话' if zh else 'Back to chat')
def hide_keyboard():
 if 'mInputShown=true' in d.shell('dumpsys','input_method'):d.shell('input','keyevent','4')
def draft():return d.find(classname='android.widget.EditText').get('text','')
def visible(label):
 n=d.find(label);x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
 assert x2>x1 and y2>y1,(label,n.get('bounds'))
original={p:read(p) for p in ['shared_prefs/codex_go.xml','shared_prefs/app_language.xml']};language=locales();font=d.shell('settings','get','system','font_scale').strip();density=d.shell('wm','density');night=d.shell('cmd','uimode','night').strip();animations=d.disable_animations();created=[]
try:
 d.shell('am','force-stop','dev.codex.go');d.shell('run-as','dev.codex.go','mkdir','-p','shared_prefs');write('shared_prefs/codex_go.xml',b'<map><boolean name="engineUsed" value="false"/><boolean name="autoPrepare" value="false"/></map>')
 set_locale('');d.shell('settings','put','system','font_scale','1.0');d.shell('wm','density','420');d.shell('cmd','uimode','night','no');d.shell('wm','user-rotation','lock','0')
 d.start();d.find('What shall we make?');no_chinese_chrome();capture('home-en')
 d.click('Choose model');d.find('Start Codex to choose a model');capture('models-en');d.click('Done')
 d.set_text('默认项目 / Write code / 原文 %1$s');hide_keyboard();raw=draft();assert raw=='默认项目 / Write code / 原文 %1$s'
 settings();select_language('简体中文');d.find('本机引擎');assert locales()=='zh-Hans';capture('settings-zh');back(True);assert draft()==raw
 d.shell('am','force-stop','dev.codex.go');d.start();d.find('更多');assert draft()==raw and locales()=='zh-Hans'
 settings(True);select_language('English',True);d.find('Local engine');capture('settings-en');back();assert draft()==raw
 d.set_text('');hide_keyboard();no_chinese_chrome()
 more('Phone control');d.find('Connection status');d.find('Accessibility');capture('device-en');d.scroll_click('Screen pre-read · Experimental');d.find('Pre-read first');capture('pre-read-en');back()
 d.click('Quick controls');d.find('Close quick controls');capture('controls-en');d.click('Close quick controls')
 more('Projects');d.find('Your projects');d.scroll_click('Create project');d.set_text('原文 Project');d.click('Create & enter');d.find('More')
 projects=json.loads(next(n.text for n in ET.fromstring(read('shared_prefs/codex_go.xml')) if n.get('name')=='projects'));created=[p['id'] for p in projects if p['id']!='default']
 settings();select_language('简体中文');d.find('本机引擎');back(True);d.find('原文 Project');settings(True);select_language('English',True);d.find('Local engine');back();d.find('原文 Project')
 # Long translated labels and the language picker remain reachable at 360dp / 160% text.
 d.shell('wm','density','480');d.shell('settings','put','system','font_scale','1.6');d.shell('cmd','uimode','night','yes');d.find('More');capture('home-en-dark-large')
 d.click('Choose model');visible('Done');capture('models-en-dark-large');d.click('Done');settings();visible('Language');capture('settings-en-dark-large');select_language('Follow system');d.find('Local engine');assert locales()=='';capture('follow-system-dark-large')
 d.shell('am','force-stop','dev.codex.go');d.start();d.find('More');assert locales()==''
 # System App languages use the same persisted framework setting as the in-app picker.
 set_locale('zh-Hans');settings(True);d.find('简体中文');back(True);set_locale('en');d.find('More');settings();d.find('English');back()
 d.shell('settings','put','system','font_scale','1.0');d.shell('wm','density','420');d.shell('cmd','uimode','night','no');d.find('More')
 result=d.shell('am','instrument','-w','dev.codex.go.testdriver/.LocalizationDriver');assert 'INSTRUMENTATION_CODE: -1' in result,result
 facts=json.loads(re.search(r'INSTRUMENTATION_RESULT: facts=(.*)',result)[1])
 result=dict(version=d.version(),**facts,followSystemDefault=True,inAppChineseAndEnglish=True,selectionPersistsColdStart=True,systemAppLanguageSettingSync=True,draftPreservedAcrossLanguageAndRestart=True,userProjectNamePreserved=True,englishDeviceAndQuickControls=True,englishLargeFontAndDarkTheme=True,externalInference=False,realAccountLogin=False)
 (out/'result.json').write_text(json.dumps(result,indent=2)+'\n');print('PASS: English/Chinese/follow system, framework sync, cold restart, drafts/projects, 360dp/160% font and native resource formatting',flush=True)
finally:
 capture('last-screen');d.shell('am','force-stop','dev.codex.go');set_locale(language)
 for path,data in original.items():
  if data is None:d.shell('run-as','dev.codex.go','rm','-f',path)
  else:write(path,data)
 for project in created:d.shell('run-as','dev.codex.go','rm','-rf','files/projects/'+project)
 d.shell('wm','density',re.search(r'Override density: (\d+)',density)[1] if 'Override density:' in density else 'reset')
 d.shell('settings','put','system','font_scale',font)
 mode=night.rsplit(' ',1)[-1];d.shell('cmd','uimode','night',mode if mode in ['yes','no','auto','custom'] else 'no');d.restore_animations(animations)
