#!/usr/bin/env python3
"""Build Codex Go's per-ABI asset bundle from hash-locked upstream packages."""
from pathlib import Path, PurePosixPath
import argparse
import hashlib
import io
import json
import os
import subprocess
import shutil
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
parser.add_argument('--from-apk', type=Path, help='Recover a runtime from a trusted previous APK, without fetching retired packages')
parser.add_argument('--cli-pack', action='store_true', help='Prepare a separate Codex CLI APK payload')
parser.add_argument('--apk-sha256', help='Required published SHA256 for --from-apk')
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
            shutil.copyfileobj(source, output)
        target.with_suffix('.partial').replace(target)
    with target.open('rb') as stream:
        actual = hashlib.file_digest(stream, 'sha256').hexdigest()
    if actual != digest:
        raise ValueError('Upstream hash mismatch: ' + filename)
    return target

inputs = json.loads(LOCK.read_text())
toolchain = json.loads((ROOT / 'tools' / f'toolchain-{args.abi}.lock.json').read_text())
inputs += toolchain['packages']
codex_package = next(package for package in inputs if package['kind'] == 'codex')
codex_version = codex_package['version']
links, bindings, executables = {}, {}, set()

def native(data, name, native_dir=NATIVE):
    path = native_dir / name
    path.write_bytes(data)
    path.chmod(0o755)

def add_guest(bundle, path, data, executable=False, native_dir=NATIVE):
    links.pop(path, None)
    bindings.pop('/' + path, None)
    executables.discard(path)
    if data.startswith(b'\x7fELF'):
        name = 'libguest_' + sha(path.encode())[:16] + '.so'
        native(data, name, native_dir)
        bindings['/' + path] = name
        bundle.writestr(path, b'')
    else:
        bundle.writestr(path, data)
        if executable: executables.add(path)

def add_archive(bundle, archive, kind, native_dir=NATIVE):
    stream = io.BytesIO(gzip.decompress(archive.read_bytes())) if kind == 'apk' else archive
    with tarfile.open(fileobj=stream, mode='r:', ignore_zeros=True) if kind == 'apk' else tarfile.open(archive) as contents:
        for member in contents:
            relative = member.name.removeprefix('./').rstrip('/')
            if not relative or (kind == 'apk' and relative.startswith('.')):
                continue
            assert not relative.startswith('/') and '..' not in PurePosixPath(relative).parts
            path = 'opt/codex/' + relative if kind == 'codex' else relative
            if member.issym():
                bindings.pop('/' + path, None)
                executables.discard(path)
                links[path] = member.linkname
            elif member.isfile() or member.islnk():
                add_guest(bundle, path, contents.extractfile(member).read(), bool(member.mode & 0o111), native_dir)


def write_guest_metadata(bundle):
    links['usr/local/bin/codex'] = '/opt/codex/bin/codex'
    links['usr/local/bin/codex-code-mode-host'] = '/opt/codex/bin/codex-code-mode-host'
    links['usr/local/bin/rg'] = '/opt/codex/codex-path/rg'
    bundle.writestr('.links.json', json.dumps(links))
    bundle.writestr('.bindings.json', json.dumps(bindings))
    bundle.writestr('.executables.json', json.dumps(sorted(executables)))
def write_manifest(assets, unpacked_bytes):
    fingerprint = sha(json.dumps(inputs, sort_keys=True).encode() + Path(__file__).read_bytes())[:12]
    manifest = dict(version=f'codex-{codex_version}-alpine-3.22.1-{args.abi}-v3-{fingerprint}',
                    abi=args.abi, codexVersion=codex_version, tools=toolchain['wanted'],
                    unpackedBytes=unpacked_bytes, inputs=inputs, caSha256=sha((assets / 'ca.pem').read_bytes()))
    (assets / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')


def refresh_codex(staging, manifest):
    previous = [package for package in manifest['inputs'] if package['kind'] == 'codex']
    if len(previous) != 1:
        raise ValueError('Expected one Codex package in the source APK')
    if previous[0]['sha256'] == codex_package['sha256'] and manifest['codexVersion'] == codex_version:
        return
    archive = download(codex_package['url'], codex_package['file'], codex_package['sha256'])
    assets = staging / 'assets/runtime'
    native_dir = staging / 'jniLibs' / args.abi
    rootfs = assets / 'rootfs.zip'
    updated = rootfs.with_name('rootfs-updated.zip')
    metadata = {'.links.json', '.bindings.json', '.executables.json'}
    with zipfile.ZipFile(rootfs) as source, zipfile.ZipFile(updated, 'w', zipfile.ZIP_DEFLATED) as bundle:
        previous_bindings = json.loads(source.read('.bindings.json'))
        bindings.update((path, library) for path, library in previous_bindings.items() if not path.startswith('/opt/codex/'))
        unused = set(previous_bindings.values()) - set(bindings.values())
        for library in unused:
            (native_dir / library).unlink()
        links.update((path, target) for path, target in json.loads(source.read('.links.json')).items() if not path.startswith('opt/codex/'))
        executables.update(path for path in json.loads(source.read('.executables.json')) if not path.startswith('opt/codex/'))
        for entry in source.infolist():
            if entry.filename not in metadata and not entry.filename.startswith('opt/codex/'):
                with source.open(entry) as data, bundle.open(entry.filename, 'w') as target:
                    shutil.copyfileobj(data, target)
        add_archive(bundle, archive, 'codex', native_dir)
        write_guest_metadata(bundle)
        unpacked_bytes = sum(entry.file_size for entry in bundle.infolist())
    updated.replace(rootfs)
    write_manifest(assets, unpacked_bytes)


if args.cli_pack:
    directory = ROOT / '.gradle/cli-packs' / args.abi
    assets = directory / 'assets/cli'
    natives = directory / 'jniLibs' / args.abi
    if directory.exists():
        shutil.rmtree(directory)
    assets.mkdir(parents=True)
    natives.mkdir(parents=True)
    if args.from_apk:
        # The signed, hash-verified host already contains the exact locked package.
        import tempfile
        from reuse_runtime import reuse
        with tempfile.TemporaryDirectory(prefix='cli-pack-', dir=directory) as temporary:
            recovered = Path(temporary) / 'runtime'
            reuse(args.from_apk, args.apk_sha256, args.abi, recovered, inputs)
            with zipfile.ZipFile(recovered / 'assets/runtime/rootfs.zip') as root, zipfile.ZipFile(assets / 'files.zip', 'w', zipfile.ZIP_DEFLATED) as payload:
                for entry in root.infolist():
                    if entry.filename.startswith('opt/codex/'):
                        with root.open(entry) as data, payload.open(entry.filename, 'w') as target:
                            shutil.copyfileobj(data, target)
                bindings.update((path, library) for path, library in json.loads(root.read('.bindings.json')).items() if path.startswith('/opt/codex/'))
                links.update((path, dest) for path, dest in json.loads(root.read('.links.json')).items() if path.startswith('opt/codex/'))
                executables.update(path for path in json.loads(root.read('.executables.json')) if path.startswith('opt/codex/'))
            for library in set(bindings.values()):
                shutil.copyfile(recovered / 'jniLibs' / args.abi / library, natives / library)
    else:
        archive = download(codex_package['url'], codex_package['file'], codex_package['sha256'])
        with zipfile.ZipFile(assets / 'files.zip', 'w', zipfile.ZIP_DEFLATED) as payload:
            add_archive(payload, archive, 'codex', natives)
    if '/opt/codex/bin/codex' not in bindings:
        raise ValueError('CLI pack has no Codex executable')
    manifest = dict(format=1, abi=args.abi, version=codex_version, sourceSha256=codex_package['sha256'],
                    bindings=bindings, links=links, executables=sorted(executables))
    (assets / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(f'Prepared standalone Codex CLI {codex_version} for {args.abi}')
    raise SystemExit(0)

if args.from_apk:
    if not args.apk_sha256:
        parser.error('--from-apk requires --apk-sha256')
    from reuse_runtime import reuse
    reuse(args.from_apk, args.apk_sha256, args.abi, BUNDLE, inputs, refresh_codex)
    print(f'Prepared {args.abi} with verified base APK and Codex {codex_version}', flush=True)
    raise SystemExit(0)

# A full preparation starts with an empty bundle so removed libraries cannot leak into the APK.
shutil.rmtree(BUNDLE)
NATIVE.mkdir(parents=True)
ASSETS.mkdir(parents=True)
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
            add_archive(bundle, archive, package['kind'])
    write_guest_metadata(bundle)
# Flatten package overlays, omit overwritten regular files that became symlinks.
with zipfile.ZipFile(overlay) as source, zipfile.ZipFile(ASSETS / 'rootfs.zip', 'w', zipfile.ZIP_DEFLATED) as output:
    entries = {entry.filename: entry for entry in source.infolist() if entry.filename not in links}
    for name, entry in entries.items():
        with source.open(entry) as data, output.open(name, 'w') as target:
            shutil.copyfileobj(data, target)
    unpacked_bytes = sum(entry.file_size for entry in entries.values())
overlay.unlink()
ca = Path('/etc/ssl/certs/ca-certificates.crt').read_bytes()
(ASSETS / 'ca.pem').write_bytes(ca)
write_manifest(ASSETS, unpacked_bytes)
print('Prepared', args.abi, len(bindings), 'native guest files in', BUNDLE, flush=True)
