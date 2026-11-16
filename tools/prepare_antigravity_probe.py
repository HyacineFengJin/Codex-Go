#!/usr/bin/env python3
"""Build an emulator-only minimal Linux root for the production PRoot launch probe."""
from pathlib import Path,PurePosixPath
import urllib.request,json,hashlib,subprocess,io,tarfile,zipfile
root=Path(__file__).resolve().parent.parent;work=root/'.gradle/antigravity-work'
work.mkdir(parents=True,exist_ok=True)
lock=json.loads((root/'tools/antigravity-probe.lock.json').read_text());rows=lock['termux'];base=lock['rootfs']
out=root/'.gradle/antigravity-runtime/x86_64';native=out/'jniLibs/x86_64';native.mkdir(parents=True,exist_ok=True)
for row in rows:
 archive=work/row['Filename'].split('/')[-1]
 if not archive.exists():urllib.request.urlretrieve('https://packages.termux.dev/apt/termux-main/'+row['Filename'],archive)
 assert hashlib.file_digest(archive.open('rb'),'sha256').hexdigest()==row['SHA256']
 names=subprocess.check_output(['ar','t',str(archive)],text=True).splitlines();payload=subprocess.check_output(['ar','p',str(archive),next(n for n in names if n.startswith('data.tar'))])
 with tarfile.open(fileobj=io.BytesIO(payload)) as tar:
  for member in tar:
   if not member.isfile():continue
   name=PurePosixPath(member.name).name;target={'proot':'libproot.so','loader':'libloader.so','loader32':'libloader32.so','libandroid-shmem.so':'libandroid-shmem.so'}.get(name)
   if name.startswith('libtalloc.so'):target='libtalloc.so'
   if target:(native/target).write_bytes(tar.extractfile(member).read().replace(b'libtalloc.so.2\0',b'libtalloc.so\0\0\0'))
archive=work/'probe-alpine.tar.gz'
if not archive.exists():urllib.request.urlretrieve(base['url'],archive)
assert hashlib.file_digest(archive.open('rb'),'sha256').hexdigest()==base['sha256']
asset=out/'assets/antigravity/probe-rootfs.zip';asset.parent.mkdir(parents=True,exist_ok=True);links={};bindings={}
with tarfile.open(archive) as tar,zipfile.ZipFile(asset,'w',zipfile.ZIP_DEFLATED) as zip:
 for member in tar:
  name=member.name.removeprefix('./').rstrip('/')
  if not name:continue
  assert not name.startswith('/') and '..' not in PurePosixPath(name).parts
  if member.issym():links[name]=member.linkname
  elif member.isfile():
   data=tar.extractfile(member).read()
   if data.startswith(b'\x7fELF'):
    filename='libprobe_guest_'+hashlib.sha256(name.encode()).hexdigest()[:16]+'.so';(native/filename).write_bytes(data);bindings['/'+name]=filename;data=b'\0'
   zip.writestr(name,data)
 zip.writestr('.links.json',json.dumps(links));zip.writestr('.bindings.json',json.dumps(bindings))
print('Prepared minimal x86_64 PRoot probe')
