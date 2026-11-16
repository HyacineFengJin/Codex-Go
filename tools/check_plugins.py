#!/usr/bin/env python3
"""Real plugin APK IPC, production dispatch and manager UI on an owned emulator."""
import argparse,json,re,subprocess,time
from pathlib import Path
from android_driver import AndroidDriver
p=argparse.ArgumentParser();p.add_argument('--serial',default='emulator-5560');a=p.parse_args();d=AndroidDriver(a.serial)
out=Path('.gradle/validation/reports/plugin-system');out.mkdir(parents=True,exist_ok=True);(out/'result.json').unlink(missing_ok=True)
sample=Path('examples/text-tools/build/outputs/apk/debug/text-tools-plugin-debug.apk')
assert sample.is_file(),'Build :text-tools-plugin:assembleDebug first'
settings={k:d.shell('settings','get','system',k).strip() for k in ['font_scale','user_rotation','accelerometer_rotation']}
density=d.shell('wm','density');night=d.shell('cmd','uimode','night').strip().split()[-1];locale=re.search(r'\[(.*?)\]',d.shell('cmd','locale','get-app-locales','dev.codex.go'))[1];animations=d.disable_animations()
def open_plugins():
 d.start();d.click('More');d.click('Plugins');d.find('Text Tools')
def toggle():return d.find('Enable Text Tools')
def capture(name):time.sleep(.2);d.capture(out/(name+'.png'))
try:
 result=d.shell('am','instrument','-w','dev.codex.go.testdriver/.PluginDriver');(out/'instrumentation.log').write_text(result)
 assert 'INSTRUMENTATION_CODE: -1' in result,result
 facts=json.loads(re.search(r'INSTRUMENTATION_RESULT: facts=(.*)',result)[1]);assert all(facts.values()),facts
 # The fault service is test-only; keep the regular UI focused on the real sample.
 d.shell('am','instrument','-w','-e','faultService','disabled','dev.codex.go.testdriver/.UiDriver')
 d.shell('cmd','locale','set-app-locales','dev.codex.go','--locales','en');d.shell('cmd','uimode','night','no');d.shell('wm','density','420');d.shell('settings','put','system','font_scale','1.0');d.shell('wm','user-rotation','lock','0')
 open_plugins();assert toggle().get('checked')=='false'
 d.tap(toggle());d.click('Test connection');d.find('Connected · API 1');capture('light')
 open_plugins();assert toggle().get('checked')=='true','Enable did not survive restart'
 d.shell('cmd','locale','set-app-locales','dev.codex.go','--locales','zh-Hans');d.find('启用 Text Tools');capture('chinese')
 d.shell('settings','put','system','font_scale','1.6');d.shell('wm','density','480');d.shell('cmd','uimode','night','yes')
 node=d.find('启用 Text Tools');b=list(map(int,re.findall(r'\d+',node.get('bounds'))));assert b[2]>b[0] and b[3]>b[1];capture('dark-large')
 d.shell('settings','put','system','font_scale','1.0');d.shell('wm','density','420');d.shell('cmd','uimode','night','no')
 # Reinstall while the host is stopped: firstInstallTime must revoke stale persisted approval.
 d.shell('am','force-stop','dev.codex.go');d.shell('pm','uninstall','dev.codex.go.example.texttools')
 subprocess.run(d.adb+['install','-r',str(sample)],check=True,stdout=subprocess.DEVNULL)
 d.shell('cmd','locale','set-app-locales','dev.codex.go','--locales','en');open_plugins()
 assert toggle().get('checked')=='false','Reinstalled plugin inherited old trust';capture('reinstalled')
 result=dict(version=d.version(),**facts,managerEnableAndPingUI=True,englishChineseAndDarkLarge=True,coldRestartEnabledState=True,reinstallRequiresOptIn=True,externalInference=False,realAccountLogin=False)
 (out/'result.json').write_text(json.dumps(result,indent=2)+'\n');print('PASS: plugin IPC, routing, faults, revocation, reinstall and localized manager UI')
finally:
 d.shell('am','force-stop','dev.codex.go');d.shell('am','instrument','-w','-e','faultService','enabled','dev.codex.go.testdriver/.UiDriver')
 d.shell('cmd','locale','set-app-locales','dev.codex.go',*(['--locales',locale] if locale else []));d.shell('cmd','uimode','night',night)
 for k,v in settings.items():d.shell('settings','delete','system',k) if v=='null' else d.shell('settings','put','system',k,v)
 d.shell('wm','density',re.search(r'Override density: (\d+)',density)[1] if 'Override density:' in density else 'reset');d.restore_animations(animations)
