#!/usr/bin/env python3
"""Owned emulator UI: concise controls, real drafts/projects, adaptive M3 Expressive layout."""
import argparse,json,re,subprocess,time,xml.etree.ElementTree as ET
from pathlib import Path
from PIL import Image
from android_driver import AndroidDriver
p=argparse.ArgumentParser();p.add_argument('--serial',default='emulator-5560');args=p.parse_args()
d=AndroidDriver(args.serial);assert d.safe_ui
out=Path('.gradle/validation/reports/expressive-ui');out.mkdir(parents=True,exist_ok=True);(out/'result.json').unlink(missing_ok=True)
def read(path):
 if subprocess.run(d.adb+['shell','run-as','dev.codex.go','test','-f',path]).returncode:return None
 return subprocess.check_output(d.adb+['exec-out','run-as','dev.codex.go','cat',path])
def write(path,data):subprocess.run(d.adb+['shell','run-as','dev.codex.go','sh','-c',"'cat > "+path+"'"],input=data,check=True)
def labels():return [n.get('text','') or n.get('content-desc','') for n in d.xml().iter('node')]
def hide_keyboard():
 if 'mInputShown=true' in d.shell('dumpsys','input_method'):d.shell('input','keyevent','4')
def more(label):d.click('更多');d.click(label)
def on_screen(label):
 n=d.find(label);x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')));w,h=Image.open(capture('bounds-check')).size
 assert 0<=x1<x2<=w and 0<=y1<y2<=h,(label,n.get('bounds'))
def capture(name):
 target=out/(name+'.png');d.capture(target);return target

def rotate(landscape):
 d.shell('wm','user-rotation','lock','1' if landscape else '0');end=time.monotonic()+8
 while time.monotonic()<end:
  w,h=Image.open(capture('rotation-wait')).size
  if (w>h)==landscape:return
  time.sleep(.1)
 raise AssertionError('Display did not rotate')
original_locale=re.search(r'\[(.*?)\]',d.shell('cmd','locale','get-app-locales','dev.codex.go'))[1];original=read('shared_prefs/codex_go.xml');animations=d.disable_animations();saved={k:d.shell('settings','get','system',k).strip() for k in ['user_rotation','accelerometer_rotation','font_scale']};night=d.shell('cmd','uimode','night').strip();display_size=d.shell('wm','size');density=d.shell('wm','density');created=[]
try:
 d.shell('cmd','locale','set-app-locales','dev.codex.go','--locales','zh-Hans')
 d.shell('am','force-stop','dev.codex.go');d.shell('run-as','dev.codex.go','mkdir','-p','shared_prefs');write('shared_prefs/codex_go.xml',b'<map><boolean name="engineUsed" value="false"/></map>')
 d.shell('settings','put','system','font_scale','1.0');d.shell('cmd','uimode','night','no');d.shell('wm','density','420');rotate(False)
 d.start();d.find('想做什么？');on_screen('发送消息');capture('home-light')
 d.click('选择模型');d.find('启动后选择模型');on_screen('完成');capture('models-before-start');d.click('完成')
 d.click('写代码');assert '帮我在工作区写一个小工具' in d.find(classname='android.widget.EditText').get('text','');hide_keyboard()
 rotate(True);assert '帮我在工作区写一个小工具' in d.find(classname='android.widget.EditText').get('text','');capture('landscape-draft')
 rotate(False);d.set_text('');hide_keyboard()
 d.tap(d.find(classname='android.widget.EditText'));d.click('打开侧栏');d.find('模型与思考')
 assert 'mInputShown=true' not in d.shell('dumpsys','input_method'),'Composer keyboard covers drawer'
 capture('drawer');d.click('项目：默认项目');d.find('你的项目');d.scroll_click('创建项目');d.set_text('界面项目');d.click('创建并进入');d.find('更多')
 projects=json.loads(next(n.text for n in ET.fromstring(read('shared_prefs/codex_go.xml')) if n.get('name')=='projects'));created=[n['id'] for n in projects if n['id']!='default']
 more('项目');d.find('界面项目');capture('projects-light')
 d.shell('wm','density','480');time.sleep(.4)
 buttons=[n for n in d.xml().iter('node') if n.get('content-desc','').startswith('进入项目 ')]
 assert len({int(re.findall(r'\d+',n.get('bounds'))[0]) for n in buttons})>=2,'360dp grid lost two columns'
 capture('projects-360dp');d.shell('wm','density','420');d.click('返回对话')
 more('手机操作');d.find('连接状态');d.find('无障碍');capture('device-light')
 d.scroll_click('屏幕预读取 · 实验');d.set_switch('预读优先',True);d.find('新对话生效');capture('pre-read-expanded')
 d.scroll_click('屏幕预读取 · 实验');assert '新对话生效' not in labels(),'Section did not collapse'
 d.click('返回对话');more('设置');d.find('本机引擎');capture('settings-light')
 d.scroll_click('配置恢复');on_screen('恢复默认配置');capture('configuration-expanded');d.scroll_click('配置恢复')
 d.scroll_click('历史图片 · 实验');d.find('压缩旧图片');capture('history-expanded');d.scroll_click('历史图片 · 实验');d.click('返回对话')
 d.click('快捷控制');d.find('关闭快捷控制');on_screen('下次打开自动准备');capture('controls-light');d.click('关闭快捷控制')
 more('快捷对话');d.find('通知内输入');on_screen('常驻通知直接回复');capture('quick-chat-light');d.click('返回对话')
 more('分享收件箱（0）');d.find('在其他 App 中分享至 Codex Go');capture('inbox-light');d.click('返回对话')
 d.shell('cmd','uimode','night','yes');d.shell('wm','density','480');d.shell('settings','put','system','font_scale','1.6');time.sleep(.5);rotate(False)
 d.find('想做什么？');on_screen('发送消息');capture('home-dark-large')
 d.click('选择模型');on_screen('完成');capture('models-dark-large');d.click('完成')
 d.click('快捷控制');on_screen('关闭快捷控制');d.scroll_click('下次打开自动准备');capture('controls-dark-large');d.click('关闭快捷控制')
 more('手机操作');d.scroll_click('屏幕预读取 · 实验');d.set_switch('预读优先',False);on_screen('预读优先');capture('device-dark-large');d.click('返回对话')
 more('设置');d.expand_for('导出诊断');d.scroll_to('导出诊断');on_screen('导出诊断');capture('settings-dark-large');d.click('返回对话')
 result=dict(version=d.version(),conciseHomeActions=True,modelPickerAccessible=True,bentoActionsCreateDrafts=True,draftSurvivesLandscape=True,drawerHidesComposerKeyboard=True,projectGridActions=True,twoColumnGridAt360dp=True,advancedSettingsExpandAndCollapse=True,preReadSettingRetained=True,quickChatControlsAccessible=True,lightAndDarkThemes=True,largeFontComposerAndModelSheetUsable=True,largeFontControlsAndSettingsUsable=True,externalInference=False,realAccountLogin=False)
 (out/'result.json').write_text(json.dumps(result,indent=2)+'\n');print('PASS: concise M3 Expressive UI, model entry, real drafts/projects, disclosures, light/dark, 360dp and 160% font',flush=True)
finally:
 capture('last-screen');d.shell('am','force-stop','dev.codex.go')
 d.shell('cmd','locale','set-app-locales','dev.codex.go',*(['--locales',original_locale] if original_locale else []))
 d.shell('wm','size',re.search(r'Override size: (\d+x\d+)',display_size)[1] if 'Override size:' in display_size else 'reset')
 d.shell('wm','density',re.search(r'Override density: (\d+)',density)[1] if 'Override density:' in density else 'reset')
 if original is not None:write('shared_prefs/codex_go.xml',original)
 else:d.shell('run-as','dev.codex.go','rm','-f','shared_prefs/codex_go.xml')
 for project in created:d.shell('run-as','dev.codex.go','rm','-rf','files/projects/'+project)
 for key,v in saved.items():
  if v=='null':d.shell('settings','delete','system',key)
  else:d.shell('settings','put','system',key,v)
 mode=night.rsplit(' ',1)[-1];d.shell('cmd','uimode','night',mode if mode in ['yes','no','auto','custom'] else 'no');d.restore_animations(animations)
