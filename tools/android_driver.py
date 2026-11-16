"""Small UI test driver restricted to disposable emulators."""
from pathlib import Path
import re
import base64
import subprocess
import time
import xml.etree.ElementTree as ET

class AndroidDriver:
    def __init__(self, serial='emulator-5560'):
        if not serial.startswith('emulator-'):
            raise ValueError('This driver only accepts disposable Android emulators')
        self.adb = ['adb', '-s', serial]
        self.safe_ui = bool(self.shell('pm', 'path', 'dev.codex.go.testdriver').strip())
    def shell(self, *args):
        return subprocess.check_output(self.adb + ['shell', *args], text=True)
    def version(self):
        match=re.search(r'versionName=([^\s]+)',self.shell('dumpsys','package','dev.codex.go'))
        if not match: raise RuntimeError('Cannot determine installed Codex Go version')
        return match[1]
    def disable_animations(self):
        keys=['window_animation_scale','transition_animation_scale','animator_duration_scale']
        saved={key:self.shell('settings','get','global',key).strip() for key in keys}
        for key in keys: self.shell('settings','put','global',key,'0')
        return saved
    def restore_animations(self, saved):
        for key,value in saved.items():
            if value=='null': self.shell('settings','delete','global',key)
            else: self.shell('settings','put','global',key,value)
    def xml(self):
        if self.safe_ui:
            for _ in range(4):
                result = self.shell('am', 'instrument', '-w', 'dev.codex.go.testdriver/.UiDriver')
                match = re.search(r'INSTRUMENTATION_RESULT: xml=(<hierarchy.*</hierarchy>)', result)
                if match:
                    root = ET.fromstring(match.group(1))
                    if len(root): return root
                # Activity recreation can briefly expose no accessibility windows.
            raise RuntimeError('Emulator UI driver could not observe windows: '+result[-500:])
        for _ in range(4):
            result = self.shell('uiautomator', 'dump', '/sdcard/codex-go-test.xml')
            if 'dumped to' in result:
                return ET.fromstring(self.shell('cat', '/sdcard/codex-go-test.xml'))
        raise RuntimeError('Android UI hierarchy unavailable')
    def find(self, label=None, classname=None, timeout=10):
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            for node in self.xml().iter('node'):
                if label and (node.get('text', '').strip() == label or node.get('content-desc') == label): return node
                if classname and node.get('class') == classname: return node
        raise AssertionError('UI element missing: ' + str(label or classname))
    def set_text(self, text):
        change=['setTextBase64',base64.b64encode(text.encode()).decode()] if text else ['clearText','true']
        for attempt in range(3):
            self.tap(self.find(classname='android.widget.EditText'))
            result=self.shell('am','instrument','-w','-e',*change,'dev.codex.go.testdriver/.UiDriver')
            if 'textSet=true' in result:break
            if 'No visible editable field' not in result:raise RuntimeError('Android set-text failed: '+result[-500:])
            time.sleep(.2) # An IME/window transition may briefly own the active accessibility root.
        else:raise RuntimeError('Android set-text failed: '+result[-500:])
        until=time.monotonic()+5
        while time.monotonic()<until:
            if self.find(classname='android.widget.EditText').get('text','')==text:return
        raise AssertionError('Editable field did not retain replacement text')
    def set_switch(self, description, enabled):
        self.expand_for(description)
        height=int(re.findall(r'(\d+)x(\d+)',self.shell('wm','size'))[-1][1])
        for _ in range(10):
            target=next((n for n in self.xml().iter('node') if n.get('checkable')=='true' and n.get('content-desc')==description),None)
            if target is not None:
                x1,y1,x2,y2=map(int,re.findall(r'\d+',target.get('bounds')));center=(y1+y2)//2
                if 350<center<height-100:
                    if target.get('enabled')!='true':time.sleep(.2);continue
                    if (target.get('checked')=='true')==enabled:return
                    self.tap(target);continue
                if center<=350:self.shell('input','swipe','530','850','530','1900','300');continue
            self.shell('input','swipe','530','1900','530','850','300')
        raise AssertionError('Visible switch unavailable: '+description)
    def tap(self, node):
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
        self.shell('input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    def click(self, label): self.tap(self.find(label=label))
    def scroll_click(self, label):
        self.expand_for(label)
        self.scroll_to(label);time.sleep(.25)
        self.tap(self.scroll_to(label));time.sleep(.2)
    def scroll_to(self, label):
        width,height=map(int,re.findall(r'(\d+)x(\d+)',self.shell('wm','size'))[-1])
        previous=None;direction=-1
        for _ in range(24):
            tree=list(self.xml().iter('node'))
            target=next((n for n in tree if n.get('text')==label or n.get('content-desc')==label),None)
            if target is not None:
                b=list(map(int,re.findall(r'\d+',target.get('bounds'))));middle=(b[1]+b[3])/2
                # Leave room for the fixed top bar and running/finished task banner.
                if height*.21<middle<height-160:
                    return target
                direction=1 if middle<=height*.21 else -1
            else:
                signature=tuple((n.get('text'),n.get('content-desc'),n.get('bounds')) for n in tree)
                if signature==previous:direction=-direction
                previous=signature
            self.shell('input','swipe',str(width//2),str(int(height*(.4 if direction>0 else .8))),str(width//2),str(int(height*(.8 if direction>0 else .4))),'250')
        raise AssertionError('Missing visible action '+label)
    def expand_for(self, label):
        sections = {
            '预读优先':'屏幕预读取 · 实验', '轻量模型辅助预读':'屏幕预读取 · 实验',
            '选择预读模型':'屏幕预读取 · 实验', '测试预读取':'屏幕预读取 · 实验', '测试辅助预读':'屏幕预读取 · 实验',
            '压缩历史图片':'历史图片 · 实验',
            '恢复默认配置':'配置恢复', '恢复上次启动配置':'配置恢复', '恢复前一份配置':'配置恢复',
            '查看运行日志':'修复与诊断', '导出诊断':'修复与诊断', '修复运行环境':'修复与诊断',
        }
        section = sections.get(label)
        if section is None:return
        # Use the real disclosure state; never toggle an already open section.
        self.scroll_to(section)
        root=self.xml()
        parents={child:parent for parent in root.iter() for child in parent}
        node=next(n for n in root.iter('node') if n.get('text')==section or n.get('content-desc')==section)
        # Compose exposes the disclosure state on its clickable header, above the Text leaf.
        while not node.get('state-desc') and node in parents:node=parents[node]
        state=node.get('state-desc','')
        assert state, 'Disclosure state missing: '+section
        if state.startswith(('已收起','Collapsed')):self.tap(node);time.sleep(.2)
    def capture(self, path):
        path = Path(path); path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(subprocess.check_output(self.adb + ['exec-out', 'screencap', '-p']))
    def private_write(self, path, data):
        # path is a test-owned constant, never a user string.
        assert re.fullmatch(r'files/[a-zA-Z0-9_./-]+', path) and '..' not in path
        subprocess.run(self.adb + ['shell', 'run-as', 'dev.codex.go', 'sh', '-c', f"'cat > {path}'"], input=data, check=True)
    def capability(self, kind, enabled=True):
        """Connected system grants are usable directly; only an explicit pause needs resuming."""
        title='手机操作已就绪' if kind=='phone' else 'Android Shell 已就绪'
        pause='暂停手机能力' if kind=='phone' else '暂停 Shell 能力'
        resume='恢复手机能力' if kind=='phone' else '恢复 Shell 能力'
        if not enabled:self.scroll_click(pause);return
        nodes=list(self.xml().iter('node'))
        if any(n.get('text')==resume for n in nodes):self.scroll_click(resume)
        # The status can be below the experiment panel; scroll without changing access.
        self.scroll_click(title)
    def preference(self, name, value):
        assert re.fullmatch(r'[a-zA-Z][a-zA-Z0-9]*',name)
        exists=subprocess.run(self.adb+['shell','run-as','dev.codex.go','test','-f','shared_prefs/codex_go.xml']).returncode==0
        raw=subprocess.check_output(self.adb+['exec-out','run-as','dev.codex.go','cat','shared_prefs/codex_go.xml']) if exists else None
        root=ET.fromstring(raw) if raw is not None else ET.Element('map')
        for item in list(root):
            if item.get('name')==name:root.remove(item)
        ET.SubElement(root,'boolean',{'name':name,'value':'true' if value else 'false'})
        self.shell('run-as','dev.codex.go','mkdir','-p','shared_prefs')
        subprocess.run(self.adb+['shell','run-as','dev.codex.go','sh','-c',"'cat > shared_prefs/codex_go.xml'"],input=ET.tostring(root),check=True)
    def start(self, auto_prepare=False):
        self.shell('cmd', 'statusbar', 'collapse')
        self.shell('am', 'force-stop', 'dev.codex.go')
        # Legacy scenarios explicitly start the engine. Dedicated startup coverage
        # passes None to exercise the product's default, or True for a saved choice.
        if auto_prepare is not None:self.preference('autoPrepare',auto_prepare)
        self.shell('am', 'start', '-f', '0x10008000', '-n', 'dev.codex.go/.MainActivity')
