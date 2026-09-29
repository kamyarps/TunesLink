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

previous_apk="${workspace}/artifacts/device-previous/TunesLink.apk"
if [[ ! -f "$previous_apk" ]]; then
  exit 0
fi

build_tools="${ANDROID_HOME}/build-tools/$(sed -n 's/^TunesLink.buildToolsVersion=//p' "$workspace/android/gradle.properties")"
previous_min_sdk="$("$build_tools/aapt" dump badging "$previous_apk" |
  sed -n "s/^sdkVersion:'\([0-9]*\)'/\1/p")"
device_sdk="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
test -n "$previous_min_sdk"
test -n "$device_sdk"
if (( device_sdk < previous_min_sdk )); then
  printf 'Previous APK needs API %s; skipping upgrade on API %s.\n' \
    "$previous_min_sdk" "$device_sdk"
  exit 0
fi

# The fresh-install smoke test above leaves the release APK installed. Replace it
# with the previous public APK, then verify that Android upgrades it in place.
adb uninstall com.kamyarps.tuneslink >/dev/null
adb install "$previous_apk" >/dev/null
adb shell am start -W -n com.kamyarps.tuneslink/.MainActivity >/dev/null
before="$(adb shell dumpsys package com.kamyarps.tuneslink)"
before_uid="$(sed -n 's/^[[:space:]]*userId=\([0-9]*\).*/\1/p' <<< "$before" | head -n 1)"
before_install="$(sed -n 's/^[[:space:]]*firstInstallTime=//p' <<< "$before" | head -n 1)"
test -n "$before_uid"
test -n "$before_install"

adb install -r "$TunesLink_ANDROID_APK" >/dev/null
after="$(adb shell dumpsys package com.kamyarps.tuneslink)"
after_uid="$(sed -n 's/^[[:space:]]*userId=\([0-9]*\).*/\1/p' <<< "$after" | head -n 1)"
after_install="$(sed -n 's/^[[:space:]]*firstInstallTime=//p' <<< "$after" | head -n 1)"
test "$after_uid" = "$before_uid"
test "$after_install" = "$before_install"
adb shell am start -W -n com.kamyarps.tuneslink/.MainActivity >/dev/null
printf 'Published APK upgraded in place on API %s; app identity and install time stayed intact.\n' \
  "$device_sdk"
