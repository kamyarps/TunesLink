#!/usr/bin/env bash
set -euo pipefail

# This runs only on the disposable CI emulator, after the debug integration suite.
# Remove the debug signing identity before installing the actual release artifact.
workspace="${GITHUB_WORKSPACE:-$PWD}"
export TunesLink_ANDROID_APK="${workspace}/artifacts/device-release/TunesLink.apk"
export TunesLink_SMOKE_REPORTS="${workspace}/android/app/build/reports/device-release-smoke"
test -f "$TunesLink_ANDROID_APK"
adb uninstall com.kamyarps.tuneslink >/dev/null
bash "$workspace/scripts/android-emulator-smoke.sh"
