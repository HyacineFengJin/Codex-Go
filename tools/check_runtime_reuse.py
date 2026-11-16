#!/usr/bin/env python3
"""Check pinned APK import, ABI/input checks and omission of optional binaries."""
import hashlib,io,json,tempfile,unittest,zipfile
from pathlib import Path
from reuse_runtime import reuse

class RuntimeReuseTests(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name);self.apk=self.root/'source.apk';self.target=self.root/'runtime';self.inputs=[{'name':'fixture','kind':'codex','sha256':'a'*64}]
  inner=io.BytesIO()
  with zipfile.ZipFile(inner,'w') as z:z.writestr('.bindings.json',json.dumps({'/usr/bin/fixture':'libguest_fixture.so'}))
  with zipfile.ZipFile(self.apk,'w') as z:
   z.writestr('assets/runtime/manifest.json',json.dumps({'abi':'arm64-v8a','inputs':self.inputs}));z.writestr('assets/runtime/rootfs.zip',inner.getvalue());z.writestr('assets/runtime/ca.pem','fixture CA')
   for name in ['libproot.so','libloader.so','libtalloc.so','libandroid-shmem.so','libguest_fixture.so','libguest_agy.so','libframework.so']:z.writestr('lib/arm64-v8a/'+name,b'fixture '+name.encode())
  with self.apk.open('rb') as stream:self.sha=hashlib.file_digest(stream,'sha256').hexdigest()
 def tearDown(self):self.temp.cleanup()
 def test_verified_runtime_keeps_payload_and_omits_unrelated_optional_libraries(self):
  self.assertEqual(8,reuse(self.apk,self.sha,'arm64-v8a',self.target,self.inputs));self.assertTrue((self.target/'jniLibs/arm64-v8a/libguest_fixture.so').exists());self.assertFalse((self.target/'jniLibs/arm64-v8a/libguest_agy.so').exists())
 def test_bad_hash_abi_and_inputs_preserve_previous_bundle(self):
  self.target.mkdir();(self.target/'keep').write_text('old')
  for sha,abi,inputs in [('b'*64,'arm64-v8a',self.inputs),(self.sha,'x86_64',self.inputs),(self.sha,'arm64-v8a',[])]:
   with self.assertRaises(ValueError):reuse(self.apk,sha,abi,self.target,inputs)
   self.assertEqual('old',(self.target/'keep').read_text())

if __name__=='__main__':unittest.main(verbosity=2)
