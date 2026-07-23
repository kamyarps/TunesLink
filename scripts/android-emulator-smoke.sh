#!/usr/bin/env bash
set -euo pipefail

package="com.kamyarps.tuneslink"
component="${package}/.MainActivity"
workspace="${GITHUB_WORKSPACE:-$PWD}"
apk="${workspace}/android/app/build/outputs/apk/debug/app-debug.apk"
reports="${workspace}/android/app/build/reports/device-smoke"
ui_node_center_script="${workspace}/scripts/android-ui-node-center.py"
ui_contract_script="${workspace}/scripts/android-ui-contract.py"

mkdir -p "$reports"

cleanup() {
  adb shell wm user-rotation free >/dev/null 2>&1 || true
  adb shell settings put system accelerometer_rotation 1 >/dev/null 2>&1 || true
  adb shell wm size reset >/dev/null 2>&1 || true
  adb shell wm density reset >/dev/null 2>&1 || true
  adb shell settings put system font_scale 1.0 >/dev/null 2>&1 || true
  adb shell settings put global window_animation_scale 1 >/dev/null 2>&1 || true
  adb shell settings put global transition_animation_scale 1 >/dev/null 2>&1 || true
  adb shell settings put global animator_duration_scale 1 >/dev/null 2>&1 || true
}
trap cleanup EXIT

set_rotation() {
  local rotation="$1"
  local output
  if output="$(adb shell wm user-rotation lock "$rotation" 2>&1)" &&
      [[ -z "${output//[[:space:]]/}" ]]; then
    return
  fi
  adb shell settings put system accelerometer_rotation 0
  adb shell settings put system user_rotation "$rotation"
}

orientation_matches() {
  local xml="$1"
  local expected="$2"
  python3 - "$xml" "$expected" "$package" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

xml, expected, package = sys.argv[1:]
bounds = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
sizes = []
for node in ET.parse(xml).getroot().iter("node"):
    if node.attrib.get("package") != package:
        continue
    match = bounds.fullmatch(node.attrib.get("bounds", ""))
    if match is None:
        continue
    left, top, right, bottom = map(int, match.groups())
    sizes.append((right - left, bottom - top))
if not sizes:
    raise SystemExit(1)
width, height = max(sizes, key=lambda size: size[0] * size[1])
actual = "landscape" if width > height else "portrait"
raise SystemExit(0 if actual == expected else 1)
PY
}

capture() {
  local name="$1"
  local expected_orientation="${2:-}"
  local expected_rotation="${3:-}"
  local xml="${reports}/${name}.xml"
  local screenshot="${reports}/${name}.png"
  local center=""
  for _ in $(seq 1 20); do
    rm -f "$xml"
    if ! timeout 12s adb shell uiautomator dump "/sdcard/${name}.xml" >/dev/null 2>&1 ||
        ! timeout 12s adb pull "/sdcard/${name}.xml" "$xml" >/dev/null 2>&1; then
      sleep 1
      continue
    fi
    if grep -q "Your iTunes. Right here." "$xml" &&
        orientation_matches "$xml" "$expected_orientation"; then
      # The accessibility tree can become ready just before Android removes the
      # starting-window overlay. Give the first real frame time to present so
      # screenshot baselines never capture a transient splash screen.
      sleep 1
      adb exec-out screencap -p > "$screenshot"
      return
    fi
    if [[ -n "$expected_rotation" ]]; then
      set_rotation "$expected_rotation"
    fi
    if grep -Fq "System UI isn't responding" "$xml" &&
        center="$(python3 "$ui_node_center_script" "$xml" text "Wait")"; then
      adb shell input tap $center
      sleep 2
      continue
    fi
    sleep 1
  done
  adb exec-out screencap -p > "$screenshot" || true
  cat "$xml" >&2 2>/dev/null || true
  return 1
}

launch() {
  adb shell am force-stop "$package"
  adb shell am start -W -n "$component" >/dev/null
  sleep 2
  adb shell dumpsys activity activities |
    grep -E "mResumedActivity|topResumedActivity" |
    grep -q "$package"
}

adb wait-for-device
adb install -r "$apk" >/dev/null
# This smoke case verifies a fresh install. `adb install -r` intentionally keeps
# app data, so clear it explicitly to avoid a previously paired emulator skipping
# the welcome screen.
adb shell pm clear "$package" >/dev/null
adb shell settings put system font_scale 1.0
adb shell settings put global window_animation_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global animator_duration_scale 1
adb logcat -b crash -c >/dev/null 2>&1 || true

adb shell wm size 1080x1920
adb shell wm density 420
set_rotation 0
launch
capture "phone-portrait-motion" portrait 0

adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
launch
capture "phone-portrait-reduced-motion" portrait 0

set_rotation 1
sleep 2
capture "phone-landscape" landscape 1

adb shell settings put system font_scale 2.0
set_rotation 0
launch
capture "phone-portrait-font-200" portrait 0
python3 "$ui_contract_script" "${reports}/phone-portrait-font-200.xml" \
  --width 1080 --height 1920 --density 420

set_rotation 1
sleep 2
capture "phone-landscape-font-200" landscape 1
python3 "$ui_contract_script" "${reports}/phone-landscape-font-200.xml" \
  --width 1920 --height 1080 --density 420 --require-scrollable

adb shell settings put system font_scale 1.5

adb shell wm size 1600x1000
adb shell wm density 240
set_rotation 0
launch
capture "tablet-landscape" landscape 0

set_rotation 1
sleep 2
capture "tablet-portrait" portrait 1

adb shell am force-stop "$package"
adb shell am start -W -n "$component" >/dev/null
sleep 1

adb logcat -d -b crash > "${reports}/crash-buffer.txt"
if grep -q "$package" "${reports}/crash-buffer.txt"; then
  cat "${reports}/crash-buffer.txt"
  exit 1
fi
