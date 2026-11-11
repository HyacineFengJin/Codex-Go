#!/usr/bin/env python3
"""Build Codex Go's per-ABI asset bundle from hash-locked upstream packages."""
from pathlib import Path, PurePosixPath
import argparse
import hashlib
import io
import json
import os
import subprocess
import tarfile
import urllib.request
import zipfile
import gzip
import warnings
warnings.filterwarnings('ignore', message='Duplicate name:')

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.gradle/runtime-downloads'
parser = argparse.ArgumentParser()
parser.add_argument('--abi', choices=['arm64-v8a', 'x86_64'], default='arm64-v8a')
args = parser.parse_args()
ARCH = 'aarch64' if args.abi == 'arm64-v8a' else 'x86_64'
BUNDLE = ROOT / '.gradle/runtime-bundles' / args.abi
NATIVE = BUNDLE / 'jniLibs' / args.abi
ASSETS = BUNDLE / 'assets/runtime'
LOCK = ROOT / 'tools' / ('runtime.lock.json' if args.abi == 'arm64-v8a' else 'runtime-x86_64.lock.json')
for directory in (CACHE, NATIVE, ASSETS):
    directory.mkdir(parents=True, exist_ok=True)

def sha(data):
    return hashlib.sha256(data).hexdigest()

def download(url, filename, digest):
    target = CACHE / filename
    if not target.exists():
        print('Fetch', filename, flush=True)
        with urllib.request.urlopen(url, timeout=120) as source, target.with_suffix('.partial').open('wb') as output:
            import shutil
            shutil.copyfileobj(source, output)
        target.with_suffix('.partial').replace(target)
    if sha(target.read_bytes()) != digest:
        raise ValueError('Upstream hash mismatch: ' + filename)
    return target

if not LOCK.exists():
    base = 'https://packages.termux.dev/apt/termux-main/'
    index = urllib.request.urlopen(base + f'dists/stable/main/binary-{ARCH}/Packages', timeout=60).read().decode()
    packages = {}
    for block in index.split('\n\n'):
        fields = dict(line.split(': ', 1) for line in block.splitlines() if ': ' in line and not line.startswith(' '))
        if fields.get('Package') in ('proot', 'libtalloc', 'libandroid-shmem'):
            packages[fields['Package']] = {'name': fields['Package'], 'kind': 'deb', 'url': base + fields['Filename'], 'file': PurePosixPath(fields['Filename']).name, 'sha256': fields['SHA256']}
    assert len(packages) == 3
    alpine_url = f'https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/{ARCH}/alpine-minirootfs-3.22.1-{ARCH}.tar.gz'
    alpine_sha = urllib.request.urlopen(alpine_url + '.sha256', timeout=60).read().decode().split()[0]
    inputs = [
        {'name': 'codex', 'kind': 'codex', 'url': f'https://github.com/openai/codex/releases/download/rust-v0.165.0/codex-package-{ARCH}-unknown-linux-musl.tar.gz', 'file': f'codex-package-0.165.0-{ARCH}.tar.gz', 'sha256': 'c8b2844fdef57498572db960344aee8c836ce9f75c203379da0a9c3a334fc3b7' if ARCH == 'aarch64' else 'e97c4463d7f146692a955d7b0e1c8cf48f97cd4d616cd45c5dcab2e8d9743316'},
        {'name': 'alpine', 'kind': 'rootfs', 'url': alpine_url, 'file': f'alpine-3.22.1-{ARCH}.tar.gz', 'sha256': alpine_sha},
        *packages.values(),
    ]
    LOCK.write_text(json.dumps(inputs, indent=2) + '\n')
inputs = json.loads(LOCK.read_text())
toolchain = json.loads((ROOT / 'tools' / f'toolchain-{args.abi}.lock.json').read_text())
inputs += toolchain['packages']
links, bindings, executables = {}, {}, set()

def native(data, name):
    path = NATIVE / name
    path.write_bytes(data)
    path.chmod(0o755)

def add_guest(bundle, path, data, executable=False):
    links.pop(path, None)
    bindings.pop('/' + path, None)
    executables.discard(path)
    if data.startswith(b'\x7fELF'):
        name = 'libguest_' + sha(path.encode())[:16] + '.so'
        native(data, name)
        bindings['/' + path] = name
        bundle.writestr(path, b'')
    else:
        bundle.writestr(path, data)
        if executable: executables.add(path)

overlay = ASSETS / 'rootfs-overlay.zip'
with zipfile.ZipFile(overlay, 'w', zipfile.ZIP_DEFLATED) as bundle:
    for package in inputs:
        archive = download(package['url'], package['file'], package['sha256'])
        if package['kind'] == 'deb':
            names = subprocess.check_output(['ar', 't', str(archive)], text=True).splitlines()
            payload = subprocess.check_output(['ar', 'p', str(archive), next(name for name in names if name.startswith('data.tar'))])
            with tarfile.open(fileobj=io.BytesIO(payload)) as contents:
                for member in contents:
                    if not member.isfile(): continue
                    name = PurePosixPath(member.name).name
                    mapping = {'proot': 'libproot.so', 'loader': 'libloader.so', 'loader32': 'libloader32.so', 'libandroid-shmem.so': 'libandroid-shmem.so'}
                    destination = 'libtalloc.so' if name.startswith('libtalloc.so') else mapping.get(name)
                    if destination:
                        data = contents.extractfile(member).read()
                        # Android packages cannot retain a versioned .so.2 SONAME. Keep ELF offsets intact.
                        data = data.replace(b'libtalloc.so.2\0', b'libtalloc.so\0\0\0')
                        native(data, destination)
        else:
            stream = io.BytesIO(gzip.decompress(archive.read_bytes())) if package['kind'] == 'apk' else archive
            with tarfile.open(fileobj=stream, mode='r:', ignore_zeros=True) if package['kind'] == 'apk' else tarfile.open(archive) as contents:
                for member in contents:
                    relative = member.name.removeprefix('./').rstrip('/')
                    if not relative: continue
                    if package['kind'] == 'apk' and relative.startswith('.'): continue
                    assert not relative.startswith('/') and '..' not in PurePosixPath(relative).parts
                    path = 'opt/codex/' + relative if package['kind'] == 'codex' else relative
                    if member.issym():
                        bindings.pop('/' + path, None)
                        links[path] = member.linkname
                    elif member.isfile(): add_guest(bundle, path, contents.extractfile(member).read(), bool(member.mode & 0o111))
                    elif member.islnk():
                        target = contents.getmember(member.linkname)
                        add_guest(bundle, path, contents.extractfile(target).read(), bool(member.mode & 0o111))
    links['usr/local/bin/codex'] = '/opt/codex/bin/codex'
    links['usr/local/bin/codex-code-mode-host'] = '/opt/codex/bin/codex-code-mode-host'
    links['usr/local/bin/rg'] = '/opt/codex/codex-path/rg'
    bundle.writestr('.links.json', json.dumps(links))
    bundle.writestr('.bindings.json', json.dumps(bindings))
    bundle.writestr('.executables.json', json.dumps(sorted(executables)))
# Flatten package overlays, omit overwritten regular files that became symlinks.
with zipfile.ZipFile(overlay) as source, zipfile.ZipFile(ASSETS / 'rootfs.zip', 'w', zipfile.ZIP_DEFLATED) as output:
    entries = {entry.filename: entry for entry in source.infolist() if entry.filename not in links}
    for name, entry in entries.items():
        with source.open(entry) as data, output.open(name, 'w') as target:
            import shutil
            shutil.copyfileobj(data, target)
    unpacked_bytes = sum(entry.file_size for entry in entries.values())
overlay.unlink()
ca = Path('/etc/ssl/certs/ca-certificates.crt').read_bytes()
(ASSETS / 'ca.pem').write_bytes(ca)
fingerprint = sha(json.dumps(inputs, sort_keys=True).encode() + Path(__file__).read_bytes())[:12]
manifest = {'version': f'codex-0.165.0-alpine-3.22.1-{args.abi}-v3-{fingerprint}', 'abi': args.abi, 'codexVersion': '0.165.0', 'tools': toolchain['wanted'], 'unpackedBytes': unpacked_bytes, 'inputs': inputs, 'caSha256': sha(ca)}
(ASSETS / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
print('Prepared', args.abi, len(bindings), 'native guest files in', BUNDLE, flush=True)
