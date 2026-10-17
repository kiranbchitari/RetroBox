#!/usr/bin/env bash
# Builds and runs the on-device (instrumented) tests on a connected TV, phone or emulator.
#
# Unlike `./gradlew connectedDebugAndroidTest`, this does NOT uninstall the app afterwards, so the player's ROMs,
# saves and settings in the debug app (com.retrobox.tv.debug) are kept. The tests themselves only use a scratch
# folder in the app's cache and their own database file.
#
# Usage: tools/run-device-tests.sh [TestClass[#method]]      e.g.  tools/run-device-tests.sh com.retrobox.tv.GlLooksDeviceTest
#        ANDROID_SERIAL=<serial> tools/run-device-tests.sh    (pick a device when several are connected)
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"

./gradlew -q assembleDebug assembleDebugAndroidTest
"$ADB" install -r -t app/build/outputs/apk/debug/app-debug.apk >/dev/null
"$ADB" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null

ARGS=()
if [ $# -gt 0 ]; then ARGS=(-e class "$1"); fi
OUT="$("$ADB" shell am instrument -w -r ${ARGS[@]+"${ARGS[@]}"} com.retrobox.tv.debug.test/androidx.test.runner.AndroidJUnitRunner)"

# Print one line per test plus any failure details, then the summary.
python3 - "$OUT" <<'PY'
import re, sys
out = sys.argv[1]
cur, status = {}, None
for line in out.splitlines():
    m = re.match(r'INSTRUMENTATION_STATUS: (\w+)=(.*)', line)
    if m: cur[m.group(1)] = m.group(2); continue
    m = re.match(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)', line)
    if m:
        code = int(m.group(1))
        name = f"{cur.get('class','?').split('.')[-1]}.{cur.get('test','?')}"
        if code == 0: print(f"  PASS  {name}")
        elif code in (-1, -2): print(f"  FAIL  {name}\n        {cur.get('stack','').splitlines()[0] if cur.get('stack') else ''}")
        elif code == -3: print(f"  SKIP  {name}")
        cur = {}
for line in out.splitlines():
    if line.startswith(('OK (', 'FAILURES!!!', 'Tests run:', 'INSTRUMENTATION_FAILED', 'INSTRUMENTATION_RESULT: shortMsg')): print(line)
PY
echo "$OUT" | grep -q '^OK (' || { echo "Device tests failed (full output: adb logcat -s TestRunner RetroBoxDeviceTest)"; exit 1; }
