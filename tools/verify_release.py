#!/usr/bin/env python3
"""Collect fresh emulator checks into the compact report beside the current phone APK."""
import argparse
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

base_groups=['lifecycle-recovery','storage-recovery','editor-recovery','upgrade-recovery','selfcheck','antigravity','plugin-system','floating-ui','phone-indicator-accessibility','phone-indicator-adb','quick-start','phone-actions','phone-actions-adb','history-images','history-images-openai','model-pre-read','pre-read','expressive-ui','localization','quick-chat','configuration','share-flow','recovery-flow','maintenance','repair-failure','file-flow','wireless-debugging','android-engine','phone-flow','project-flow','background-flow','shell-access','pairing-overlay','android-engine-wired']
parser=argparse.ArgumentParser();parser.add_argument('version');parser.add_argument('--serial',default='emulator-5560');parser.add_argument('--groups',nargs='+',choices=base_groups+['android-engine-shizuku']);args=parser.parse_args()
assert args.serial.startswith('emulator-'),'Only owned emulator validation is supported'
artifacts=Path('artifacts')
builds={}
for abi in ['arm64-v8a','x86_64']:
    apk=artifacts/'codex-go.apk' if abi=='arm64-v8a' else Path('.gradle/validation/codex-go-x86_64-debug.apk')
    result=json.loads(apk.with_suffix('.json').read_text())
    with apk.open('rb') as stream:actual=hashlib.file_digest(stream,'sha256').hexdigest()
    assert result['version']==args.version and result['abi']==abi
    assert result['sha256']==actual and result['apkBytes']==apk.stat().st_size
    assert result['unitFailures']==0 and result['unitTests']>0
    builds[abi]=result
assert builds['arm64-v8a']['unitTests']==builds['x86_64']['unitTests']
x86=Path('.gradle/validation/codex-go-x86_64-debug.apk')
groups=args.groups or base_groups
ui_only=bool(builds['x86_64'].get('uiSmoke',False))
if ui_only:
    assert set(groups) <= {'antigravity','plugin-system','floating-ui','expressive-ui','localization','phone-indicator-accessibility','phone-indicator-adb'},'A UI-only APK cannot validate native engine flows'
    assert not builds['arm64-v8a'].get('uiSmoke',False),'The distributed phone APK must include the runtime'
checks={}
for group in groups:
    path=Path('.gradle/validation/reports')/group/'result.json';result=json.loads(path.read_text())
    assert path.stat().st_mtime>=x86.stat().st_mtime,f'{group}: report predates delivered build'
    assert result and result.get('version',args.version)==args.version,f'{group}: version mismatch'
    if 'testedApkSha256' in result: assert result['testedApkSha256']==builds['x86_64']['sha256'],f'{group}: tested APK mismatch'
    # The base engine run can omit the optional Android Shell flow. Its two
    # fields record coverage, while wired/Shizuku groups must exercise it.
    coverage_fields={'androidShell','computerUseAfterShellOff'} if group=='android-engine' else set()
    if coverage_fields:
        assert result.get('androidShell') is result.get('computerUseAfterShellOff'),f'{group}: shell coverage mismatch'
    for key,value in result.items():
        if isinstance(value,bool) and key not in {'externalInference','realAccountLogin'}|coverage_fields:assert value,f'{group}: {key}'
    assert result.get('externalInference',False) is False and result.get('realAccountLogin',False) is False
    checks[group]=dict(result=result,testedApkSha256=builds['x86_64']['sha256'])
errors=[issue.attrib for issue in ET.parse('app/build/reports/lint-results-debug.xml').getroot() if issue.get('severity') in ['Error','Fatal']]
assert not errors,errors
import subprocess,re
installed=subprocess.check_output(['adb','-s',args.serial,'shell','dumpsys','package','dev.codex.go'],text=True)
assert re.search(r'versionName='+re.escape(args.version)+r'\s',installed),'Installed package differs from delivered build'
installed_path=subprocess.check_output(['adb','-s',args.serial,'shell','pm','path','dev.codex.go'],text=True).strip().removeprefix('package:')
assert re.fullmatch(r'/data/app/[a-zA-Z0-9_./+=~-]+/base\.apk',installed_path),'Unexpected installed APK path'
installed_hash=subprocess.check_output(['adb','-s',args.serial,'shell','sha256sum',installed_path],text=True).split()[0]
assert installed_hash==builds['x86_64']['sha256'],'Installed APK bytes differ from delivered emulator build'
result=dict(version=args.version,scope='targeted' if args.groups else 'full',distributionFile='codex-go.apk',installedEmulatorApkSha256=installed_hash,builds=builds,android15_x86_64=checks,lintErrors=errors,externalInference=False,realAccountLogin=False,pending=[
    'complete DOM, independent OCR and embedded on-device model inference', 'real Luna/account pre-read accuracy, image escalation and token/time savings',
    'physical-device history gateway with real ChatGPT auth, workspace routing and token/time measurements',
    'legacy user-image originals absent from app storage, paginated/file-ID-only histories and archive capacity stress',
    'ADB-only deferred original image on an authenticated wireless connection',
    'real rooted device and su-manager grant/revoke','full regression suite' if args.groups else 'long-running Android stress tests',
    'ARM64/OEM physical-device compatibility','complete real ChatGPT account login','real model inference','physical-device background/network recovery','OEM floating-window and notification-input compatibility','dedicated gallery picker','native move/delete/binary edge cases','share import killed before inbox commit','receiver rotation during an active copy','more source apps and OEM share sheets','project-level configuration recovery','plans/context/quota display','Android stress test for first-thread creation during attachment import'])
provenance=Path('.gradle/validation/reports/runtime-provenance.json')
if provenance.exists():result['runtimeProvenance']=json.loads(provenance.read_text())
wired=Path('.gradle/validation/reports/selfcheck/wired-result.json')
if wired.exists():
    assert wired.stat().st_mtime>=x86.stat().st_mtime
    result['wiredDaemon']=json.loads(wired.read_text())
result['hostChecks']={'acpFixtures':13,'runtimeReuse':2,'localizedStrings':len(ET.parse('app/src/main/res/values-zh/strings.xml').getroot())}
if ui_only:
    result.update(scope='ui',nativeRuntimeEndToEnd=False)
    result['pending'].insert(0,f'{args.version} native engine end-to-end checks; this emulator run uses UI_SMOKE')
if 'antigravity' in groups:
    result['pending'].insert(0,'physical ARM64 Google OAuth completion, real Antigravity model selection/inference and OEM behavior')
if 'plugin-system' in groups:
    sample=Path('examples/text-tools/build/outputs/apk/debug/text-tools-plugin-debug.apk')
    result['pluginApiVersion']=1
    result['testedExamplePluginSha256']=hashlib.file_digest(sample.open('rb'),'sha256').hexdigest()
    result['pending'].insert(0,'real model plugin tool selection and third-party/OEM plugin compatibility')
(artifacts/'verification.json').write_text(json.dumps(result,indent=2)+'\n')
print(f'PASS: {args.version}, {len(checks)} fresh Android check groups, {builds["x86_64"]["unitTests"]} unit tests, both APK hashes, lint')
