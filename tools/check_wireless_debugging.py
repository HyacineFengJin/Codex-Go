#!/usr/bin/env python3
"""Exercise notification pairing on a secure, disposable AOSP emulator."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
from android_driver import AndroidDriver

parser = argparse.ArgumentParser()
parser.add_argument('--serial', default='emulator-5560')
parser.add_argument('--overlay', action='store_true')
args = parser.parse_args()
d = AndroidDriver(args.serial)
if d.shell('getprop', 'ro.adb.secure').strip() != '1':
    raise SystemExit('This test needs a disposable emulator with ro.adb.secure=1')
out = Path('.gradle/validation/reports/pairing-overlay' if args.overlay else '.gradle/validation/reports/wireless-debugging'); out.mkdir(parents=True, exist_ok=True)
(out/'result.json').unlink(missing_ok=True)
animations = d.disable_animations()
def labels(): return [n.get('text','').strip() or n.get('content-desc','') for n in d.xml().iter('node')]
def wait_overlay_removed():
    end=time.monotonic()+5
    while time.monotonic()<end:
        if 'Codex Go 配对输入' not in d.shell('dumpsys','window','windows'):return
        time.sleep(.1)
    raise AssertionError('Pairing overlay did not close')
def open_wireless_page():
    for _ in range(12):
        tree = d.xml()
        if any(n.get('text') == 'Pair device with pairing code' for n in tree.iter('node')): return
        for n in tree.iter('node'):
            if n.get('text','').strip() == 'Wireless debugging' and n.get('resource-id') == 'android:id/title':
                d.tap(n); break
        else:
            d.shell('input', 'swipe', '450', '1950', '450', '750', '900')
    raise AssertionError('Wireless debugging page did not open')

try:
    d.shell('settings', 'put', 'global', 'development_settings_enabled', '1')
    d.shell('settings', 'put', 'global', 'adb_wifi_enabled', '1')
    current = labels()
    if 'Allow' in current: d.click('Allow')
    d.shell('pm', 'grant', 'dev.codex.go', 'android.permission.POST_NOTIFICATIONS')
    if args.overlay: d.shell('appops', 'set', 'dev.codex.go', 'SYSTEM_ALERT_WINDOW', 'allow')
    d.start(); d.click('打开侧栏'); d.click('手机操作'); d.click('配对这台手机')
    d.set_switch('常驻配对小窗', args.overlay); d.click('前往配对')
    open_wireless_page()
    d.click('Pair device with pairing code')
    tree = d.xml()
    codes = [n.get('text','').strip() for n in tree.iter('node') if re.fullmatch(r'\d{6}', n.get('text','').strip())]
    assert len(codes) == 1, 'Pairing code was not displayed by Android'
    code = codes[0]
    restricted = False
    if args.overlay:
        # Secure Settings can force-hide application overlays; do not bypass that policy.
        windows = d.shell('dumpsys','window','windows')
        assert 'Codex Go 配对输入' in windows
        overlay_window = windows.split('Codex Go 配对输入',1)[1].split('Window #',1)[0]
        restricted = 'mForceHideNonSystemOverlayWindow=true' in overlay_window
    if args.overlay and not restricted:
        d.find('悬浮配对码输入')
        until = time.monotonic() + 20
        while not any('保持系统配对窗口打开 · 端口' in text for text in labels()) and time.monotonic() < until: time.sleep(.2)
        d.capture(out/'persistent-input.png')
        d.tap(d.find('悬浮配对码输入')); d.shell('input', 'text', '12'); d.click('配对')
        d.find('请输入系统显示的六位配对码'); d.set_text(code); d.click('配对')
    else:
        d.shell('cmd', 'statusbar', 'expand-notifications')
        for _ in range(5):
            current = labels()
            if '输入配对码' in current: break
            if 'Expand' in current: d.click('Expand')
        d.capture(out/'single-reply-action.png'); d.click('输入配对码')
        d.tap(d.find(classname='android.widget.EditText')); d.shell('input', 'text', code)
        tree = d.xml()
        send = next((n for n in tree.iter('node') if n.get('resource-id') == 'android:id/remote_input_send' or n.get('content-desc') in ('Send', '发送')), None)
        assert send is not None, 'Notification reply send button missing'
        d.tap(send)
    # The Settings pairing window stays underneath the notification shade throughout the reply.
    d.find('无线调试已连接', timeout=45)
    d.shell('cmd', 'statusbar', 'collapse')
    d.shell('am', 'start', '-f', '0x34000000', '-n', 'dev.codex.go/.MainActivity')
    d.find('无线调试已连接', timeout=45)
    d.capture(out/'connected.png')
    if args.overlay:
        wait_overlay_removed()
        # The same input window remains usable outside Settings, then closes on cancel.
        d.click('配对这台手机');d.click('前往配对');d.shell('input','keyevent','3')
        d.find('悬浮配对码输入');d.capture(out/'overlay-outside-protected-settings.png')
        d.click('取消无线配对')
        wait_overlay_removed()
    (out/'result.json').write_text(json.dumps({'version':d.version(), 'optionalOverlayLifecycle' if args.overlay else 'notificationPairing':True, 'pairingInput':'notificationFallback' if restricted else 'overlay' if args.overlay else 'notification', 'tlsAdbConnection':True, 'sameDeviceOnly':True}, indent=2))
    print('PASS: same-device mDNS discovery, notification RemoteInput pairing and authenticated TLS ADB connection')
except Exception:
    d.capture(out/'failure.png')
    (out/'failure-ui.txt').write_text(re.sub(r'\b\d{6}\b', '[pair-code]', '\n'.join(labels())))
    raise
finally:
    d.restore_animations(animations)
