#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
codex_go_serial="${1:-emulator-5560}"
[[ "$codex_go_serial" == emulator-* ]] || { printf '%s\n' 'Only disposable emulators are supported'; exit 2; }
codex_go_sdk="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
codex_go_jdk="${CODEX_GO_JAVA_HOME:-$HOME/.cache/codex-go/jdk}"
codex_go_bt="$codex_go_sdk/build-tools/36.0.0"
codex_go_jar="$codex_go_sdk/platforms/android-36/android.jar"
codex_go_out='.gradle/ui-driver'
mkdir -p "$codex_go_out/classes" "$codex_go_out/dex"
"$codex_go_jdk/bin/javac" --release 17 -classpath "$codex_go_jar" -d "$codex_go_out/classes" tools/android-ui-driver/*.java
JAVA_HOME="$codex_go_jdk" "$codex_go_bt/d8" --min-api 30 --lib "$codex_go_jar" --output "$codex_go_out/dex" "$codex_go_out"/classes/dev/codex/go/testdriver/*.class
"$codex_go_bt/aapt2" compile --dir tools/android-ui-driver/res -o "$codex_go_out/resources.zip"
"$codex_go_bt/aapt2" link "$codex_go_out/resources.zip" -I "$codex_go_jar" --manifest tools/android-ui-driver/AndroidManifest.xml -o "$codex_go_out/driver-unsigned.apk"
python3 - "$codex_go_out" <<'PY'
import sys,zipfile
from pathlib import Path
p=Path(sys.argv[1])
with zipfile.ZipFile(p/'driver-unsigned.apk','a') as z: z.write(p/'dex/classes.dex','classes.dex')
PY
"$codex_go_bt/zipalign" -f 4 "$codex_go_out/driver-unsigned.apk" "$codex_go_out/driver-aligned.apk"
JAVA_HOME="$codex_go_jdk" "$codex_go_bt/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --out "$codex_go_out/driver.apk" "$codex_go_out/driver-aligned.apk"
adb -s "$codex_go_serial" install -r "$codex_go_out/driver.apk"

"$codex_go_bt/aapt2" link -I "$codex_go_jar" --manifest tools/android-ui-driver/ShareManifest.xml -o "$codex_go_out/share-unsigned.apk"
python3 - "$codex_go_out" <<'PYCODE'
import sys,zipfile
from pathlib import Path
p=Path(sys.argv[1])
with zipfile.ZipFile(p/'share-unsigned.apk','a') as z: z.write(p/'dex/classes.dex','classes.dex')
PYCODE
"$codex_go_bt/zipalign" -f 4 "$codex_go_out/share-unsigned.apk" "$codex_go_out/share-aligned.apk"
JAVA_HOME="$codex_go_jdk" "$codex_go_bt/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --out "$codex_go_out/share.apk" "$codex_go_out/share-aligned.apk"
adb -s "$codex_go_serial" install -r "$codex_go_out/share.apk"
