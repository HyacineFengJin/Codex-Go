#!/usr/bin/env python3
"""Optional personal-build runtime; fetch only pinned official Google artifacts."""
import argparse,hashlib,json,shutil,tarfile,urllib.request
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--abi',choices=['arm64-v8a','x86_64'],default='arm64-v8a');a=p.parse_args()
root=Path(__file__).resolve().parent.parent;lock=json.loads((root/'tools/antigravity.lock.json').read_text())[a.abi]
cache=root/'.gradle/antigravity-downloads';cache.mkdir(parents=True,exist_ok=True);archive=cache/(a.abi+'.tar.gz')
if not archive.exists():urllib.request.urlretrieve(lock['url'],archive)
assert hashlib.file_digest(archive.open('rb'),'sha512').hexdigest()==lock['sha512'],'Official Antigravity payload checksum mismatch'
out=root/'.gradle/antigravity-runtime'/a.abi;binary=out/'jniLibs'/a.abi/'libguest_agy.so';binary.parent.mkdir(parents=True,exist_ok=True)
with tarfile.open(archive) as tar:
 member=tar.getmember('antigravity');assert member.isfile() and member.size<600*1024*1024
 with tar.extractfile(member) as source,binary.open('wb') as target:shutil.copyfileobj(source,target)
manifest=out/'assets/antigravity/manifest.json';manifest.parent.mkdir(parents=True,exist_ok=True)
manifest.write_text(json.dumps(dict(**lock,abi=a.abi,terms='https://antigravity.google/terms',sha256=hashlib.file_digest(binary.open('rb'),'sha256').hexdigest()),indent=2)+'\n')
print('Prepared official Antigravity CLI '+lock['version']+' for '+a.abi)
