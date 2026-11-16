#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
codex_go_abi="${1:-arm64-v8a}"
case "$codex_go_abi" in arm64-v8a|x86_64) ;; *) printf '%s\n' 'Usage: bash tools/build_apk.sh [arm64-v8a|x86_64]' >&2; exit 2 ;; esac
codex_go_jdk=''
for codex_go_candidate in "${CODEX_GO_JAVA_HOME:-}" "${JAVA_HOME:-}" "$HOME/.cache/codex-go/jdk" /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/java-21-openjdk-amd64; do
    if [[ -x "$codex_go_candidate/bin/java" ]]; then
        codex_go_version="$("$codex_go_candidate/bin/java" -version 2>&1)"
        if [[ "$codex_go_version" == *'version "21.'* ]]; then
            codex_go_jdk="$codex_go_candidate"
            break
        fi
    fi
done
if [[ -z "$codex_go_jdk" ]]; then
    printf '%s\n' 'JDK 21 is required. Set CODEX_GO_JAVA_HOME to its installation directory.' >&2
    exit 1
fi
if [[ -n "${CODEX_GO_RUNTIME_APK:-}" ]]; then
    python3 tools/prepare_runtime.py --abi "$codex_go_abi" --from-apk "$CODEX_GO_RUNTIME_APK" --apk-sha256 "${CODEX_GO_RUNTIME_APK_SHA256:?Set the published source APK SHA256}"
else
    python3 tools/prepare_runtime.py --abi "$codex_go_abi"
fi
codex_go_extra_args=()
if [[ "${CODEX_GO_ANTIGRAVITY:-0}" == 1 ]]; then
    python3 tools/prepare_antigravity.py --abi "$codex_go_abi"
    codex_go_extra_args+=('-Pantigravity')
fi
JAVA_HOME="$codex_go_jdk" ./gradlew "-PruntimeAbi=$codex_go_abi" "${codex_go_extra_args[@]}" :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
python3 - "$codex_go_abi" <<'PY'
import hashlib, json, shutil, sys, zipfile
from pathlib import Path
from xml.etree import ElementTree as E
abi = sys.argv[1]
metadata = json.loads(Path('app/build/outputs/apk/debug/output-metadata.json').read_text())['elements'][0]
version = metadata['versionName']
source = Path('app/build/outputs/apk/debug') / metadata['outputFile']
destination = Path('artifacts/codex-go.apk') if abi == 'arm64-v8a' else Path('.gradle/validation/codex-go-x86_64-debug.apk')
destination.parent.mkdir(parents=True, exist_ok=True)
shutil.copyfile(source, destination)
tests = failures = 0
for report in Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    attrs = E.parse(report).getroot().attrib
    tests += int(attrs['tests']); failures += int(attrs['failures']) + int(attrs['errors'])
assert failures == 0
result = dict(version=version, abi=abi, apkBytes=destination.stat().st_size, sha256=hashlib.sha256(destination.read_bytes()).hexdigest(), unitTests=tests, unitFailures=failures)
with zipfile.ZipFile(destination) as apk:
    manifest = json.loads(apk.read('assets/runtime/manifest.json'))
    assert manifest['abi'] == abi, 'Distributed APK must contain its complete runtime'
    assert ('assets/antigravity/manifest.json' in apk.namelist()) == (f'lib/{abi}/libguest_agy.so' in apk.namelist()), 'Optional CLI manifest and binary disagree'
    result.update(versionCode=metadata['versionCode'], uiSmoke=False, runtimeIncluded=True,
                  antigravityRuntimeIncluded='assets/antigravity/manifest.json' in apk.namelist(),
                  runtimeManifestSha256=hashlib.sha256(apk.read('assets/runtime/manifest.json')).hexdigest())

provenance = Path('.gradle/runtime-bundles') / abi / 'source-apk.json'
if provenance.exists(): result['runtimeSourceApkSha256'] = json.loads(provenance.read_text())['sha256']
destination.with_suffix('.json').write_text(json.dumps(result, indent=2) + '\n')
verification = Path('artifacts/verification.json')
if abi == 'arm64-v8a' and verification.exists():
    previous = json.loads(verification.read_text()).get('builds', {}).get(abi, {})
    if previous.get('sha256') != result['sha256']:
        verification.unlink()
print(destination.resolve())
print(f'{tests} unit tests passed; SHA256 {result["sha256"]}')
PY
