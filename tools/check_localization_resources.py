#!/usr/bin/env python3
"""Check language completeness, positional arguments, and untranslated UI source text."""
from pathlib import Path
import re,xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]/'app/src/main'
def catalog(name):
 entries=ET.parse(root/'res'/name/'strings.xml').getroot()
 result={e.get('name'):''.join(e.itertext()) for e in entries}
 assert len(result)==len(entries), 'Duplicate string resource'
 return result
english=catalog('values');chinese=catalog('values-zh')
fixed={e.get("name") for e in ET.parse(root/"res/values/strings.xml").getroot() if e.get("translatable")=="false"}
assert english.keys()-fixed==chinese.keys(),(english.keys()-fixed-chinese.keys(),chinese.keys()-english.keys())
for name,text in english.items():
 if name in fixed:continue
 assert set(re.findall(r'%\d+\$s',text))==set(re.findall(r'%\d+\$s',chinese[name])),name
 if name!='language_chinese':assert not re.search(r'[\u3400-\u9fff]',text),name
# The single exception is an internal quick-message source discriminator; user text is never translated.
for path in (root/'java/dev/codex/go/ui').glob('*.kt'):
 for line in path.read_text().splitlines():
  if re.search(r'[\u3400-\u9fff]',line):assert 'submitQuickText(value, target, "悬浮窗"' in line,(path,line)
for name in ['using','viewing','reading']:
 assert 'indicator_'+name in fixed
print(f'PASS: {len(chinese)} translated strings, {len(fixed)} fixed labels, matching positional arguments, English fallback and resource-only UI copy')
