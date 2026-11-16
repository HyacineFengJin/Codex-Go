#!/usr/bin/env python3
"""Real USB bridge and official Shizuku user-service checks on an owned emulator.
Install/start official Shizuku first. Does not substitute a fake su manager.
"""
import argparse, json, re, socket, struct, subprocess, time
from pathlib import Path
from android_driver import AndroidDriver

p=argparse.ArgumentParser();p.add_argument('--serial',default='emulator-5560');args=p.parse_args()
d=AndroidDriver(args.serial);out=Path('.gradle/validation/reports/shell-access');out.mkdir(parents=True,exist_ok=True)
(out/'result.json').unlink(missing_ok=True)
animations=d.disable_animations()
def nodes():return list(d.xml().iter('node'))
def until(predicate, message, seconds=20):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        if predicate():return
        time.sleep(.15)
    raise AssertionError(message)
def shell_ready():
    for _ in range(5):d.shell('input','swipe','530','800','530','1950','150')
    return any(n.get('text')=='Android Shell 已就绪' for n in nodes())
click=d.scroll_click
def test_id(provider):
    click('测试 Shell 连接');d.find('Shell 连接测试')
    text='\n'.join(n.get('text','') for n in nodes())
    assert 'uid=2000(shell)' in text and provider in text,text
    d.capture(out/(provider+'-id.png'));d.click('完成')
try:
    assert d.shell('pm','path','moe.shizuku.privileged.api').strip(),'Install official Shizuku before this check'
    d.shell('pm','grant','dev.codex.go','android.permission.POST_NOTIFICATIONS')
    d.start();d.click('快捷控制');d.click('启动 Codex');d.find('本机 Codex 已就绪',timeout=100);d.click('关闭快捷控制');d.find('打开侧栏');d.click('打开侧栏');d.find('手机操作');d.click('手机操作');d.find('手机能力')
    click('选择 有线 ADB');click('电脑 ADB 启动指南');d.find('从电脑启动 Shell')
    command=next(n.get('text') for n in nodes() if n.get('text','').startswith('adb shell '))
    assert command.startswith("adb shell '") and command.endswith("'")
    port=int(re.search(r'WiredShellDaemon (\d+) ',command)[1])
    forwarded=subprocess.check_output(d.adb+['forward','tcp:0',f'tcp:{port}'],text=True).strip()
    try:
        with socket.create_connection(('127.0.0.1',int(forwarded)),timeout=5) as connection:
            connection.sendall(struct.pack('>H',64)+b'0'*64)
            assert connection.recv(1)==b'','Unauthenticated bridge connection accepted'
    finally:subprocess.run(d.adb+['forward','--remove','tcp:'+forwarded],check=True)
    subprocess.run(d.adb+['shell',command[len("adb shell '"):-1]],check=True)
    d.find('已连接 · ADB Shell',timeout=20);d.click('完成');test_id('有线 ADB')
    d.capability('shell',True);click('选择 Shizuku')
    until(lambda:not shell_ready(),'Backend change kept Shell enabled')
    click('授权并连接 Shizuku')
    end=time.monotonic()+20
    while time.monotonic()<end:
        labels=[n.get('text','') for n in nodes()]
        if 'Allow all the time' in labels:d.click('Allow all the time')
        if '已连接 · ADB Shell' in labels:break
        time.sleep(.15)
    test_id('Shizuku');d.capability('shell',True)
    pid=d.shell('pidof','shizuku_server').strip();assert re.fullmatch(r'\d+',pid)
    d.shell('kill',pid)
    until(lambda:not shell_ready(),'Shizuku death did not revoke Shell')
    click('选择 有线 ADB');until(lambda:shell_ready(),'Unrelated wired connection lost with Shizuku');test_id('有线 ADB')
    click('选择 Root');click('请求 root 授权');d.find('未获得 root：请检查 su 或授权状态',timeout=40)
    until(lambda:not shell_ready(),'Unavailable root enabled Shell')
    d.capture(out/'root-unavailable.png')
    d.shell('cmd','uimode','night','yes');time.sleep(.4);d.capture(out/'dark.png')
    d.shell('settings','put','system','font_scale','1.3');time.sleep(.4);d.capture(out/'large-font.png')
    (out/'result.json').write_text(json.dumps(dict(version=d.version(),authenticatedWiredBridge=True,wrongTokenRejected=True,wiredShellUidVerified=True,officialShizukuUserService=True,shizukuShellUidVerified=True,selectedBackendControlsAvailability=True,shizukuDeathRevokesShell=True,connectionsRemainIndependent=True,unavailableRootNeverEnablesShell=True,externalInference=False,realAccountLogin=False),indent=2)+'\n')
    print('PASS: authenticated USB bridge, official Shizuku, identity checks, independent connections and permission lifecycle')
finally:
    d.shell('settings','put','system','font_scale','1.0');d.shell('cmd','uimode','night','no');d.restore_animations(animations)
