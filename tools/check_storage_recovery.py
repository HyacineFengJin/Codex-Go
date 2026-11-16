#!/usr/bin/env python3
"""Check cross-engine inbox/draft storage through production Android controllers."""
import argparse,hashlib,json,re
from pathlib import Path
from android_driver import AndroidDriver
p=argparse.ArgumentParser();p.add_argument('--serial',default='emulator-5570');a=p.parse_args();d=AndroidDriver(a.serial)
out=Path('.gradle/validation/reports/storage-recovery');out.mkdir(parents=True,exist_ok=True)
response=d.shell('am','instrument','-w','dev.codex.go.testdriver/.StorageRecoveryDriver');(out/'instrumentation.log').write_text(response);assert 'INSTRUMENTATION_CODE: -1' in response,response
facts=json.loads(re.search(r'INSTRUMENTATION_RESULT: facts=(.*)',response)[1])
for _ in range(2):
 d.start(auto_prepare=None);assert d.find(classname='android.widget.EditText').get('text')==facts['antigravityText'];d.shell('input','keyevent','KEYCODE_HOME')
d.start(auto_prepare=None);d.click('更多');d.click('设置');d.click('Codex');d.click('返回对话');assert d.find(classname='android.widget.EditText').get('text')==facts['codexText']
result=dict(version=d.version(),shareAndQuickUseSelectedEngine=True,noCodexDraftOverwrite=True,repeatedColdOpenPreservesAntigravityDraft=True,switchBackPreservesCodexDraft=True,externalInference=False,realAccountLogin=False)
(out/'result.json').write_text(json.dumps(result,indent=2)+'\n');print('PASS: production share/quick controllers, engine isolation, repeated cold opens and switch-back')
