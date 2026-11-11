#!/usr/bin/env python3
"""Production overlay instrumentation and pixel evidence on a disposable emulator only."""
import argparse
import json
import subprocess
import time
from pathlib import Path
from PIL import Image
from android_driver import AndroidDriver

p = argparse.ArgumentParser()
p.add_argument('--serial', default='emulator-5560')
p.add_argument('--backend', choices=['accessibility', 'adb'], default='accessibility')
a = p.parse_args()
d = AndroidDriver(a.serial)
assert d.safe_ui and d.shell('getprop', 'ro.hardware').strip() in ('ranchu', 'goldfish')
out = Path('.gradle/validation/reports') / ('phone-indicator-' + a.backend)
out.mkdir(parents=True, exist_ok=True)
(out / 'result.json').unlink(missing_ok=True)
for old in out.glob('*.png'): old.unlink()
private = 'files/indicator-validation/'
access = {k: d.shell('settings', 'get', 'secure', k).strip() for k in ['enabled_accessibility_services', 'accessibility_enabled']}
system = {k: d.shell('settings', 'get', ns, k).strip() for ns, k in [('system', 'font_scale'), ('system', 'accelerometer_rotation'), ('system', 'user_rotation')]}
density = d.shell('wm', 'density')
old_op = d.shell('appops', 'get', 'dev.codex.go', 'SYSTEM_ALERT_WINDOW')
process = None
log = None

def read(file):
    return subprocess.check_output(d.adb + ['exec-out', 'run-as', 'dev.codex.go', 'cat', private + file]).decode().strip()

def phase(value, timeout=30):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if process.poll() is not None:
            raise AssertionError('Instrumentation exited before ' + value + ': ' + '\n'.join(x.read_text()[-1500:] for x in out.glob('*instrumentation.log')))
        if read('phase') == value:
            return
        time.sleep(.05)
    raise AssertionError('Indicator fixture did not reach ' + value)

def advance():
    d.shell('run-as', 'dev.codex.go', 'touch', private + 'continue')

def capture(name):
    target = out / (name + '.png')
    d.capture(target)
    return target

def blue_edges(path):
    im = Image.open(path).convert('RGB')
    w, h = im.size
    # Samples along all four edges, excluding corners/cutouts/system labels.
    for points in [[(x, 2) for x in range(w//3, 2*w//3, 5)], [(x, h-3) for x in range(w//3, 2*w//3, 5)],
                   [(2, y) for y in range(h//3, 2*h//3, 5)], [(w-3, y) for y in range(h//3, 2*h//3, 5)]]:
        assert sum(im.getpixel(pt)[2] > im.getpixel(pt)[0] + 45 for pt in points) > len(points) * .75, 'Blue edge missing: ' + str(path)

def badge_bounds(path):
    im = Image.open(path).convert('RGB'); w, h = im.size
    rows = []
    # Skip the system bar and edge glow; detect the first continuous dark capsule.
    for y in range(max(64, int(h * .03)), min(h//2, 650)):
        count = sum(max(r, g, b) < 120 and max(r, g, b) - min(r, g, b) < 32 for r, g, b in [im.getpixel((x,y)) for x in range(w//6, 5*w//6, 3)])
        if count > w * .12 / 3: rows.append(y)
    assert rows and min(rows) >= 60, 'Badge overlaps system status area or is missing'
    runs = []
    for y in rows:
        if not runs or y > runs[-1][-1] + 3: runs.append([])
        runs[-1].append(y)
    capsule = next((run for run in runs if len(run) >= 10), None)
    assert capsule, 'Continuous status capsule missing'
    return min(capsule), max(capsule)

def windows():
    return d.shell('dumpsys', 'window', 'windows')

def launch():
    global process, log
    d.shell('run-as', 'dev.codex.go', 'rm', '-rf', private)
    d.shell('run-as', 'dev.codex.go', 'mkdir', '-p', private)
    d.private_write(private + 'phase', b'starting')
    log = (out / 'instrumentation.log').open('w')
    process = subprocess.Popen(d.adb + ['shell', 'am', 'instrument', '-w', '-e', 'backend', a.backend,
                                       'dev.codex.go.testdriver/.IndicatorDriver'], stdout=log, stderr=subprocess.STDOUT)
    if a.backend == 'accessibility':
        phase('waiting-accessibility')
        # Instrumentation restarts the target process; rebind our emulator-only service afterwards.
        d.shell('settings', 'put', 'secure', 'accessibility_enabled', '0')
        d.shell('settings', 'delete', 'secure', 'enabled_accessibility_services')
        time.sleep(.15)
        d.shell('settings', 'put', 'secure', 'enabled_accessibility_services', 'dev.codex.go/.phone.PhoneAccessService')
        d.shell('settings', 'put', 'secure', 'accessibility_enabled', '1')
    phase('visible')

def rotated(landscape):
    d.shell('wm', 'user-rotation', 'lock', '1' if landscape else '0')
    end = time.monotonic() + 10
    while time.monotonic() < end:
        image = capture('rotation-wait')
        w, h = Image.open(image).size
        if (w > h) == landscape:
            time.sleep(.2)
            return
        time.sleep(.1)
    raise AssertionError('Emulator display did not rotate')

try:
    d.shell('am', 'force-stop', 'dev.codex.go')
    d.shell('settings', 'put', 'secure', 'accessibility_enabled', '0')
    d.shell('appops', 'set', 'dev.codex.go', 'SYSTEM_ALERT_WINDOW', 'deny' if a.backend == 'accessibility' else 'allow')
    d.shell('settings', 'put', 'system', 'font_scale', '1.0')
    d.shell('settings', 'put', 'system', 'accelerometer_rotation', '0')
    rotated(False)
    d.shell('wm', 'density', '420')
    d.shell('pm', 'clear', 'dev.codex.go.sharefixture')
    d.shell('am', 'start', '-n', 'dev.codex.go.sharefixture/dev.codex.go.testdriver.ReadingFixtureActivity', '--es', 'mode', 'indicator')
    d.find('Touch through badge: 0')
    d.set_text('indicator_focus')
    rotated(False)
    capture('idle')
    launch()
    # UiAutomation may restore a previous instrument's orientation when it connects.
    # Fix orientation after the current connection, then assert the actual pixel shape.
    rotated(False)
    portrait = capture('portrait'); blue_edges(portrait); top, bottom = badge_bounds(portrait)
    assert Image.open(portrait).height > Image.open(portrait).width
    # Touch at the center of the visible badge, which overlaps the top fixture button.
    d.shell('input', 'tap', '540', str((top+bottom)//2))
    d.shell('input', 'text', '_kept')
    advance(); phase('resize-visible')
    d.shell('settings', 'put', 'system', 'font_scale', '1.6')
    d.shell('wm', 'density', '480')  # 360dp wide
    d.shell('cmd', 'overlay', 'enable', 'com.android.internal.display.cutout.emulation.tall')
    end = time.monotonic() + 10
    import re
    while time.monotonic() < end:
        display = d.shell('dumpsys', 'window', 'displays')
        cutouts = re.findall(r'mDisplayCutout=DisplayCutout\{insets=Rect\(([^)]*)\)', display)
        if any(any(int(n) for n in re.findall(r'\d+', cutout)) for cutout in cutouts): break
        time.sleep(.1)
    assert any(any(int(n) for n in re.findall(r'\d+', cutout)) for cutout in cutouts), 'Emulated notch did not take effect'
    (out / 'display-cutout.txt').write_text(display)
    assert '[x] com.android.internal.display.cutout.emulation.tall' in d.shell('cmd','overlay','list')
    rotated(False)
    small = capture('small-large-font-cutout'); blue_edges(small)
    rotated(True)
    landscape = capture('landscape-large-font-cutout'); blue_edges(landscape)
    d.shell('cmd', 'overlay', 'disable', 'com.android.internal.display.cutout.emulation.tall')
    d.shell('settings', 'put', 'system', 'font_scale', '1.0'); d.shell('wm', 'density', '420')
    rotated(False)
    advance()
    if a.backend == 'accessibility':
        phase('disconnect-ready'); advance(); phase('disconnect-active')
        d.shell('settings', 'put', 'secure', 'accessibility_enabled', '0')
        # Unbind is asynchronous in the OS; the production onUnbind removes its window synchronously.
        end = time.monotonic() + 3
        while 'Codex Go phone activity' in windows() and time.monotonic() < end: time.sleep(.03)
        assert 'Codex Go phone activity' not in windows()
        advance()
    phase('completed')
    facts = json.loads(read('result.json'))
    assert all(v for k, v in facts.items() if isinstance(v, bool)), facts
    assert 'Codex Go phone activity' not in windows(), 'Window remained after completion'
    capture('completed')
    # A killed process cannot leave a surface behind, even if no finally runs.
    advance(); phase('kill-visible'); blue_edges(capture('before-process-kill'))
    d.shell('am', 'force-stop', 'dev.codex.go'); process.wait(timeout=10); log.close()
    assert 'Codex Go phone activity' not in windows(), 'Process death left a surface'
    capture('after-process-kill')
    result = dict(version=d.version(), backend=a.backend, **facts, fourBlueEdges=True, portrait=True,
                  smallScreenAndLargeFont=True, displayCutout=True, landscape=True, processDeathCleanup=True,
                  noNewPermissionSwitch=True, externalInference=False, realAccountLogin=False)
    (out / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print('PASS: ' + a.backend + ' indicator, pass-through/focus, nested lifetimes, exception/cancel, cutout/rotation/font and process death')
finally:
    d.shell('am', 'force-stop', 'dev.codex.go')
    if process is not None and process.poll() is None: process.wait(timeout=10)
    if log is not None: log.close()
    d.shell('cmd', 'overlay', 'disable', 'com.android.internal.display.cutout.emulation.tall')
    for k, v in system.items():
        if v == 'null': d.shell('settings', 'delete', 'system', k)
        else: d.shell('settings', 'put', 'system', k, v)
    import re
    override = re.search(r'Override density: (\d+)', density)
    d.shell('wm', 'density', override[1] if override else 'reset')
    d.shell('appops', 'set', 'dev.codex.go', 'SYSTEM_ALERT_WINDOW', 'allow' if 'allow' in old_op else 'default')
    for k, v in access.items():
        if v == 'null': d.shell('settings', 'delete', 'secure', k)
        else: d.shell('settings', 'put', 'secure', k, v)
