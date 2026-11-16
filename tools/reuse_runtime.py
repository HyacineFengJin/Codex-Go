"""Recover an exact, hash-verified runtime from an existing Codex Go APK."""
import hashlib,json,re,shutil,zipfile
from pathlib import Path

def reuse(apk, digest, abi, target, expected):
    apk=Path(apk);target=Path(target)
    if not re.fullmatch(r'[a-f0-9]{64}',digest):raise ValueError('Provide the published APK SHA256')
    with apk.open('rb') as stream:
        if hashlib.file_digest(stream,'sha256').hexdigest()!=digest:raise ValueError('APK SHA256 mismatch')
    with zipfile.ZipFile(apk) as source:
        manifest=json.loads(source.read('assets/runtime/manifest.json'))
        if manifest['abi']!=abi:raise ValueError('Runtime ABI mismatch')
        fields=lambda items:{(p['name'],p['kind'],p['sha256']) for p in items}
        if fields(manifest['inputs'])!=fields(expected):raise ValueError('Runtime inputs do not match repository locks')
        import io
        with zipfile.ZipFile(io.BytesIO(source.read('assets/runtime/rootfs.zip'))) as root:
            bindings=json.loads(root.read('.bindings.json'))
        libraries=set(bindings.values())|{'libproot.so','libloader.so','libtalloc.so','libandroid-shmem.so'}
        if f'lib/{abi}/libloader32.so' in source.namelist():libraries.add('libloader32.so')
        if not all(re.fullmatch(r'lib[a-zA-Z0-9_.-]+\.so',n) for n in libraries):raise ValueError('Invalid native runtime path')
        entries={f'assets/runtime/{n}':Path('assets/runtime')/n for n in ['manifest.json','rootfs.zip','ca.pem']}
        entries.update({f'lib/{abi}/{n}':Path('jniLibs')/abi/n for n in libraries})
        for name in entries:source.getinfo(name)
        staging=target.with_name(target.name+'-reuse-tmp')
        if staging.exists():shutil.rmtree(staging)
        try:
            for name,destination in entries.items():
                path=staging/destination;path.parent.mkdir(parents=True,exist_ok=True)
                with source.open(name) as original,path.open('wb') as output:shutil.copyfileobj(original,output)
            (staging/'source-apk.json').write_text(json.dumps({'sha256':digest,'runtimeEntries':len(entries)},indent=2)+'\n')
            if target.exists():shutil.rmtree(target)
            staging.replace(target)
        finally:
            if staging.exists():shutil.rmtree(staging)
    return len(entries)
