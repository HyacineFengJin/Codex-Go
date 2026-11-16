#!/usr/bin/env python3
"""Editor rotation, Home resume, atomic save, external-write conflict and cold reopen."""
import argparse,json,time,uuid,xml.etree.ElementTree as ET
from pathlib import Path
from android_driver import AndroidDriver
p=argparse.ArgumentParser();p.add_argument('--serial',default='emulator-5570');a=p.parse_args();d=AndroidDriver(a.serial)
out=Path('.gradle/validation/reports/editor-recovery');out.mkdir(parents=True,exist_ok=True);(out/'result.json').unlink(missing_ok=True)
name='editor-'+uuid.uuid4().hex[:8]+'.txt';body='UNSAVED_ROTATION_'+name;external='EXTERNAL_CHANGE_'+name
old_rotation=d.shell('settings','get','system','user_rotation').strip();old_accel=d.shell('settings','get','system','accelerometer_rotation').strip()
def open_editor():
 d.scroll_click(name)
 for _ in range(5):
  target=next((n for n in d.xml().iter('node') if n.get('text')=='编辑'),None)
  if target is not None:d.tap(target);d.find('关闭文件');return
  d.shell('input','swipe','950','2210','160','2210','250')
 raise AssertionError('Editor action unavailable')
def hide_keyboard():
 if 'mInputShown=true' in d.shell('dumpsys','input_method'):d.shell('input','keyevent','KEYCODE_BACK')
def prefs():return ET.fromstring(d.shell('run-as','dev.codex.go','cat','shared_prefs/codex_go.xml'))
def value(key):return next((n.text for n in prefs() if n.get('name')==key),None)
try:
 d.start();d.click('更多');d.click('工作区');d.click('新建文件');d.set_text(name);d.click('创建');d.find('关闭文件');d.set_text(body);hide_keyboard()
 d.shell('settings','put','system','accelerometer_rotation','0');d.shell('settings','put','system','user_rotation','1');time.sleep(1)
 assert d.find(classname='android.widget.EditText').get('text')==body,'Activity rotation lost the unsaved editor buffer'
 d.shell('settings','put','system','user_rotation','0');time.sleep(.5);assert d.find(classname='android.widget.EditText').get('text')==body
 d.shell('input','keyevent','KEYCODE_HOME');d.shell('am','start','-f','0x10000000','-n','dev.codex.go/.MainActivity');assert d.find(classname='android.widget.EditText').get('text')==body
 d.click('保存');d.find(name);project=value('project') or 'default';folder='files/workspace' if project=='default' else 'files/projects/'+project;path=folder+'/'+name
 assert d.shell('run-as','dev.codex.go','cat',path)==body
 open_editor();d.set_text('CANNOT_OVERWRITE_'+name);hide_keyboard();d.private_write(path,external.encode());d.click('保存');d.find('文件已被 Codex 或其他操作修改，请关闭后重新打开。')
 assert d.shell('run-as','dev.codex.go','cat',path)==external
 d.click('关闭文件');d.find('放弃未保存的修改？');d.click('放弃修改');open_editor();assert d.find(classname='android.widget.EditText').get('text')==external;d.click('关闭文件')
 d.start();d.click('更多');d.click('工作区');open_editor();assert d.find(classname='android.widget.EditText').get('text')==external;d.click('关闭文件')
 result=dict(version=d.version(),unsavedEditorSurvivesRotation=True,homeResume=True,atomicSave=True,externalWriteConflictPreserved=True,explicitDiscard=True,coldOpenSavedFile=True)
 (out/'result.json').write_text(json.dumps(result,indent=2)+'\n');print('PASS: unsaved edit rotation/Home, atomic save, conflict, discard and cold reopen',flush=True)
finally:
 d.capture(out/'last-screen.png');d.shell('settings','put','system','user_rotation',old_rotation);d.shell('settings','put','system','accelerometer_rotation',old_accel);d.shell('am','force-stop','dev.codex.go')
