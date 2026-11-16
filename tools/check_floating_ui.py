#!/usr/bin/env python3
"""Exercise the real floating window on an owned emulator, without model inference."""
import argparse
import json
import re
import subprocess
import time
from pathlib import Path
from android_driver import AndroidDriver

p = argparse.ArgumentParser()
p.add_argument('--serial', default='emulator-5560')
a = p.parse_args()
d = AndroidDriver(a.serial)
out = Path('.gradle/validation/reports/floating-ui')
out.mkdir(parents=True, exist_ok=True)
(out / 'result.json').unlink(missing_ok=True)
private = 'files/floating-validation/'
sequence = 0
process = None
log = None

def read(name):
    return subprocess.check_output(d.adb + ['exec-out', 'run-as', 'dev.codex.go', 'cat', private + name], stderr=subprocess.DEVNULL, text=True)

def wait_for(test, message, timeout=12):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        try:
            value = test()
            if value: return value
        except (subprocess.CalledProcessError, json.JSONDecodeError): pass
        if process and process.poll() is not None:
            raise AssertionError('Overlay fixture exited: ' + (out / 'instrumentation.log').read_text())
        time.sleep(.08)
    raise AssertionError(message)

def command(action='query', **kwargs):
    global sequence
    sequence += 1
    d.private_write(private + 'next.json', json.dumps(dict(id=sequence, action=action, **kwargs)).encode())
    d.shell('run-as', 'dev.codex.go', 'mv', private + 'next.json', private + 'command.json')
    def response():
        value = json.loads(read('state.json'))
        return value if value['id'] == sequence else None
    return wait_for(response, 'Overlay command timed out: ' + action)

def shot(name):
    time.sleep(.25)  # Wait for the native window's first composited frame.
    d.capture(out / (name + '.png'))

def visible_controls(input_label='Floating message input', close='Close', send='Send', keyboard=False):
    from PIL import Image
    shot('bounds-check')
    w, h = Image.open(out / 'bounds-check.png').size
    ime = re.search(r'type=ime frame=\[\d+,(\d+)\]\[\d+,\d+\][^\n]* visible=true', d.shell('dumpsys', 'window', 'displays'))
    if keyboard: assert ime, 'Keyboard was not visible'
    if ime: h = min(h, int(ime.group(1)))
    for label in (input_label, close, send):
        node = d.find(label)
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
        assert 0 <= x1 < x2 <= w and 0 <= y1 < y2 <= h, (label, node.attrib, (w, h))

settings = {k: d.shell('settings', 'get', 'system', k).strip() for k in ['font_scale', 'accelerometer_rotation', 'user_rotation']}
night = d.shell('cmd', 'uimode', 'night').strip().split()[-1]
density = d.shell('wm', 'density')
override = re.search(r'Override density: (\d+)', density)
locale = d.shell('cmd', 'locale', 'get-app-locales', 'dev.codex.go').strip()
locale = re.search(r'\[(.*?)\]', locale).group(1)
blur = d.shell('settings', 'get', 'global', 'disable_window_blurs').strip()
animations = d.disable_animations()
try:
    d.shell('am', 'force-stop', 'dev.codex.go')
    d.shell('appops', 'set', 'dev.codex.go', 'SYSTEM_ALERT_WINDOW', 'allow')
    d.shell('cmd', 'locale', 'set-app-locales', 'dev.codex.go', '--locales', 'en')
    d.shell('cmd', 'uimode', 'night', 'no')
    d.shell('settings', 'put', 'system', 'font_scale', '1.0')
    d.shell('wm', 'density', '420')
    d.shell('wm', 'user-rotation', 'lock', '0')
    d.shell('settings', 'put', 'global', 'disable_window_blurs', '0')
    d.shell('input', 'keyevent', 'KEYCODE_HOME')
    d.shell('run-as', 'dev.codex.go', 'rm', '-rf', private)
    log = (out / 'instrumentation.log').open('w')
    process = subprocess.Popen(d.adb + ['shell', 'am', 'instrument', '-w', 'dev.codex.go.testdriver/.OverlayDriver'], stdout=log, stderr=subprocess.STDOUT)
    wait_for(lambda: read('phase') == 'ready', 'Overlay fixture did not start')
    d.find('Open floating chat'); shot('bubble')
    bubble = d.find('Open floating chat')
    b = list(map(int, re.findall(r'\d+', bubble.get('bounds'))))
    destination_y = '1100' if abs((b[1]+b[3])//2 - 1100) > 150 else '800'
    d.shell('input', 'swipe', str((b[0]+b[2])//2), str((b[1]+b[3])//2), '350', destination_y, '400')
    b = list(map(int, re.findall(r'\d+', d.find('Open floating chat').get('bounds'))))
    assert b[0] < 50 and b[1] > 100, 'Bubble did not snap inside safe bounds'
    d.click('Open floating chat'); visible_controls()
    assert d.find('Send').get('enabled') == 'false', 'Empty composer could send'
    assert command()['expanded']
    blur_path = 'native window blur' if command().get('usingBlur') else 'platform opaque fallback'
    shot('light')
    d.set_text('Continue from here')
    assert d.find('Send').get('enabled') == 'true'
    visible_controls(keyboard=True); shot('keyboard')
    facts = command()
    assert facts['draft'] == 'Continue from here' and facts['mainDraft'] == 'MAIN_DRAFT_KEEP'
    assert not facts['nativeEngineStarted']
    d.shell('input', 'keyevent', 'KEYCODE_BACK')
    assert not command('hide')['visible']
    assert not command('hide')['visible']
    assert not command('restore')['visible']
    shot('hidden')
    assert command('restore')['visible']
    assert command('refresh')['draft'] == 'Continue from here'
    d.find('Floating message input')
    command('busy', value=True); d.find('Queue & send'); command('busy', value=False)
    d.click('Collapse'); d.click('Open floating chat')
    assert d.find('Floating message input').get('text') == 'Continue from here'
    d.shell('cmd', 'uimode', 'night', 'yes')
    d.find('Floating message input'); shot('dark')
    d.shell('settings', 'put', 'global', 'disable_window_blurs', '1')
    fallback = wait_for(lambda: (v if (v := command()).get('fillOpacity') == 255 else None), 'Blur fallback did not become opaque')
    assert not fallback['usingBlur']; shot('opaque-fallback')
    d.shell('settings', 'put', 'global', 'disable_window_blurs', '0')
    d.shell('settings', 'put', 'system', 'font_scale', '1.6')
    d.shell('wm', 'density', '480')
    d.find('Floating message input'); visible_controls(); shot('large-font')
    d.shell('am', 'start', '-n', 'dev.codex.go.sharefixture/dev.codex.go.testdriver.ReadingFixtureActivity', '--es', 'mode', 'indicator')
    d.shell('wm', 'user-rotation', 'lock', '1')
    d.find('Floating message input'); visible_controls(); shot('landscape')
    from PIL import Image
    assert Image.open(out / 'landscape.png').width > Image.open(out / 'landscape.png').height, 'Landscape was not rendered'
    d.set_text('Continue from here'); visible_controls(keyboard=True); shot('landscape-keyboard')
    d.shell('input', 'keyevent', 'KEYCODE_BACK')
    d.shell('wm', 'user-rotation', 'lock', '0')
    d.shell('settings', 'put', 'system', 'font_scale', '1.0')
    d.shell('wm', 'density', '420')
    command('target', value='different-thread')
    d.find('Save for review')
    command('refresh'); d.find('Save for review')
    d.shell('cmd', 'locale', 'set-app-locales', 'dev.codex.go', '--locales', 'zh-Hans')
    d.find('悬浮消息输入'); d.find('保存待处理')
    assert command()['draft'] == 'Continue from here'
    shot('chinese')
    # A changed destination must save for review, never call the fixture's absent RPC.
    d.click('保存待处理')
    facts = command()
    assert facts['draft'] == '' and facts['mainDraft'] == 'MAIN_DRAFT_KEEP' and not facts['nativeEngineStarted']
    d.find('消息已保留，请在“快捷对话”中检查后发送。')
    d.click('关闭'); assert not command()['attached']
    assert not command('finish')['attached']
    process.wait(timeout=8); log.close(); log = None
    assert 'INSTRUMENTATION_CODE: -1' in (out / 'instrumentation.log').read_text()
    result = dict(version=d.version(), bubbleDragAndSnap=True, lightAndDark=True, keyboardControls=True,
                  largeFontAndLandscape=True, blankSendDisabled=True, independentPersistentDraft=True,
                  nestedToolHiding=True, changedTargetHeldForReview=True, localeAndConfigurationRetainTarget=True,
                  busyQueueLabel=True, opaqueBlurFallback=True, closeCleanup=True,
                  noNativeEngineStarted=True, blurPath=blur_path, externalInference=False, realAccountLogin=False)
    (out / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print('PASS: floating chat layout, keyboard, drafts, target routing, tool hiding, blur fallback and cleanup')
finally:
    shot('last-screen')
    d.shell('am', 'force-stop', 'dev.codex.go')
    if process and process.poll() is None: process.wait(timeout=8)
    if log: log.close()
    d.shell('cmd', 'locale', 'set-app-locales', 'dev.codex.go', *(['--locales', locale] if locale else []))
    d.shell('cmd', 'uimode', 'night', night)
    d.shell('settings', 'put', 'global', 'disable_window_blurs', '1' if blur == '1' else '0')
    if blur == 'null': d.shell('settings', 'delete', 'global', 'disable_window_blurs')
    for key, value in settings.items():
        d.shell('settings', 'delete', 'system', key) if value == 'null' else d.shell('settings', 'put', 'system', key, value)
    d.shell('wm', 'density', override.group(1) if override else 'reset')
    d.restore_animations(animations)
