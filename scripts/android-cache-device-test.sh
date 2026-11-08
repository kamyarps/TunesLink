#!/usr/bin/env bash
set -euo pipefail
workspace="${GITHUB_WORKSPACE:-$PWD}"
reports="${TunesLink_CACHE_REPORTS:-$workspace/android/app/build/reports/device-cache}"
mkdir -p "$reports"
adb install -r "$workspace/android/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
adb install -r "$workspace/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" >/dev/null
adb shell am instrument -w \
  com.kamyarps.tuneslink.test/androidx.test.runner.AndroidJUnitRunner > "$reports/result.txt"
cat "$reports/result.txt"
# am instrument can return exit code zero even when the runner reports a failure.
grep -Eq '^OK \([1-9][0-9]* tests?\)' "$reports/result.txt"
