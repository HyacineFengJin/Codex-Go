#!/usr/bin/env python3
"""Upgrade an emulator's real 0.17 UI install without clearing its existing data."""
import argparse,hashlib,json,subprocess,uuid,xml.etree.ElementTree as ET
from pathlib import Path
from android_driver import AndroidDriver
p=argparse.ArgumentParser();p.add_argument('--serial',default='emulator-5570');a=p.parse_args();d=AndroidDriver(a.serial)
out=Path('.gradle/validation/reports/upgrade-recovery');out.mkdir(parents=True,exist_ok=True)
def prefs():return ET.fromstring(d.shell('run-as','dev.codex.go','cat','shared_prefs/codex_go.xml'))
def value(key):return next((n.text for n in prefs() if n.get('name')==key),None)
assert d.version()=='0.17.0','Install the old tag UI build before this upgrade check'
d.shell('input','keyevent','KEYCODE_WAKEUP');d.shell('wm','dismiss-keyguard');d.shell('cmd','locale','set-app-locales','dev.codex.go','--locales','zh-Hans');d.start()
title='UPGRADE_'+uuid.uuid4().hex[:8];draft='OLD_UNSENT_'+title;content=('saved before upgrade: '+title).encode()
d.click('更多');d.click('项目');d.scroll_click('创建项目');d.set_text(title);d.click('创建并进入');d.find('发送消息')
d.set_text(draft);d.shell('input','keyevent','KEYCODE_BACK')
project=value('project');assert project and project!='default';folder='files/projects/'+project
d.private_write(folder+'/upgrade-saved.txt',content)
d.shell('input','keyevent','KEYCODE_HOME');d.shell('am','force-stop','dev.codex.go')
apk=Path('.gradle/validation/codex-go-x86_64-debug.apk');subprocess.run(d.adb+['install','-r',str(apk)],check=True)
d.start(auto_prepare=None);d.find('发送消息');assert d.find(classname='android.widget.EditText').get('text')==draft
assert value('project')==project;assert d.shell('run-as','dev.codex.go','cat',folder+'/upgrade-saved.txt').encode()==content
d.shell('am','force-stop','dev.codex.go');d.start(auto_prepare=None);assert d.find(classname='android.widget.EditText').get('text')==draft
result=dict(version=d.version(),sourceVersion='0.17.0',testedApkSha256=hashlib.file_digest(apk.open('rb'),'sha256').hexdigest(),upgradeInstallWithoutClear=True,projectSelectionPreserved=True,unsentDraftPreserved=True,workspaceFilePreserved=True,secondColdStartPreserved=True,externalInference=False,realAccountLogin=False)
(out/'result.json').write_text(json.dumps(result,indent=2)+'\n');print('PASS: 0.17 → current install upgrade and repeated cold open preserve project, draft and file')
