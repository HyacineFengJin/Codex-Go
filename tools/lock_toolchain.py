#!/usr/bin/env python3
"""Resolve a fixed Alpine branch once; package bytes are pinned by SHA256 thereafter."""
import argparse
import gzip
import hashlib
import io
import json
import re
import tarfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--abi', choices=['arm64-v8a', 'x86_64'], required=True)
args = parser.parse_args()
arch = 'aarch64' if args.abi == 'arm64-v8a' else 'x86_64'
lock = ROOT / 'tools' / f'toolchain-{args.abi}.lock.json'
if lock.exists():
    raise SystemExit('Lock already exists; remove it explicitly to resolve new versions')
cache = ROOT / '.gradle/runtime-downloads'
cache.mkdir(parents=True, exist_ok=True)
packages, providers = {}, {}
for repo in ['main', 'community']:
    base = f'https://dl-cdn.alpinelinux.org/alpine/v3.22/{repo}/{arch}/'
    with urllib.request.urlopen(base + 'APKINDEX.tar.gz', timeout=60) as source:
        with tarfile.open(fileobj=io.BytesIO(source.read()), mode='r:gz') as archive:
            index = archive.extractfile('APKINDEX').read().decode()
    for block in index.split('\n\n'):
        fields = dict(line.split(':', 1) for line in block.splitlines() if ':' in line)
        if 'P' not in fields:
            continue
        fields['base'] = base
        packages.setdefault(fields['P'], fields)
        for provided in [fields['P'], *fields.get('p', '').split()]:
            providers.setdefault(re.split(r'[<>=~]', provided)[0], []).append(fields['P'])

selected = set()
def resolve(requirement):
    if requirement.startswith('!'):
        return
    name = re.split(r'[<>=~]', requirement)[0]
    package = name if name in packages else next(iter(providers.get(name, [])), None)
    if package is None:
        raise ValueError('Unresolved dependency: ' + requirement)
    if package in selected:
        return
    selected.add(package)
    for dependency in packages[package].get('D', '').split():
        resolve(dependency)

wanted = ['python3', 'py3-pip', 'nodejs', 'npm', 'git']
for package in wanted:
    resolve(package)
result = []
for name in sorted(selected):
    package = packages[name]
    filename = f'{name}-{package["V"]}.apk'
    url = package['base'] + filename
    cached_name = f'{arch}-{filename}'
    target = cache / cached_name
    if not target.exists():
        with urllib.request.urlopen(url, timeout=90) as source:
            target.write_bytes(source.read())
    data = target.read_bytes()
    # Validate the concatenated APK v2 gzip/tar streams before accepting the lock.
    with tarfile.open(fileobj=io.BytesIO(gzip.decompress(data)), mode='r:', ignore_zeros=True) as archive:
        metadata = archive.extractfile('.PKGINFO').read().decode()
        assert f'pkgname = {name}\n' in metadata
        assert f'pkgver = {package["V"]}\n' in metadata
    result.append(dict(name=name, kind='apk', version=package['V'], license=package.get('L', ''), origin=package.get('o', name), url=url, file=cached_name, sha256=hashlib.sha256(data).hexdigest()))
lock.write_text(json.dumps(dict(branch='v3.22', abi=args.abi, wanted=wanted, packages=result), indent=2) + '\n')
print(f'{lock}: {len(result)} locked packages')
