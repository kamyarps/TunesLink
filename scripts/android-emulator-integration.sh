#!/usr/bin/env bash
set -Eeuo pipefail

report_failure() {
  local status=$?
  printf 'Integration failed at line %s: %s\n' "${BASH_LINENO[0]}" "$BASH_COMMAND" >&2
  exit "$status"
}
trap report_failure ERR

package="com.kamyarps.tuneslink"
component="${package}/.MainActivity"
workspace="${GITHUB_WORKSPACE:-$PWD}"
apk="${workspace}/android/app/build/outputs/apk/debug/app-debug.apk"
reports="${workspace}/android/app/build/reports/device-integration"
bridge_project="${workspace}/test-support/TuneLinkDemoBridge/TuneLinkDemoBridge.csproj"
bridge_config="${RUNNER_TEMP:-/tmp}/TunesLink-demo-bridge-$$"
bridge_log="${reports}/bridge.log"
ui_xml="${reports}/window.xml"
ui_node_center_script="${workspace}/scripts/android-ui-node-center.py"
dotnet_command="${DOTNET_COMMAND:-dotnet}"
adb_command="${ADB_COMMAND:-adb}"
bridge_port="${TunesLink_BRIDGE_PORT:-45832}"
bridge_address="${TunesLink_BRIDGE_ADDRESS:-10.0.2.2}"

mkdir -p "$reports"
bridge_pid=""
if [[ "${TunesLink_BRIDGE_EXTERNAL:-0}" != "1" ]]; then
  "$dotnet_command" run --project "$bridge_project" --configuration Release -- \
    --port "$bridge_port" --discovery-port 0 --pair-code 123456 \
    --library-delay-ms 1800 \
    --config-directory "$bridge_config" >"$bridge_log" 2>&1 &
  bridge_pid=$!
fi

cleanup() {
  if [[ -n "$bridge_pid" ]]; then
    kill "$bridge_pid" >/dev/null 2>&1 || true
    wait "$bridge_pid" >/dev/null 2>&1 || true
  fi
  "$adb_command" shell settings put system font_scale 1.0 >/dev/null 2>&1 || true
  "$adb_command" shell settings put system accelerometer_rotation 1 >/dev/null 2>&1 || true
  "$adb_command" shell wm user-rotation free >/dev/null 2>&1 || true
}
trap cleanup EXIT

for _ in $(seq 1 60); do
  if grep -q "^ready:" "$bridge_log" 2>/dev/null; then
    break
  fi
  if [[ -n "$bridge_pid" ]] && ! kill -0 "$bridge_pid" >/dev/null 2>&1; then
    cat "$bridge_log"
    exit 1
  fi
  sleep 1
done
grep -q "^ready:" "$bridge_log"

dump_ui() {
  local attempt
  local remote_ui_xml="/sdcard/tunelink-integration-window.xml"
  for attempt in $(seq 1 6); do
    rm -f "$ui_xml"
    if timeout 12s "$adb_command" shell uiautomator dump "$remote_ui_xml" >/dev/null 2>&1 &&
        timeout 12s "$adb_command" pull "$remote_ui_xml" "$ui_xml" >/dev/null 2>&1 &&
        grep -q '<hierarchy' "$ui_xml"; then
      return
    fi
    sleep 1
  done
  printf 'Unable to capture the Android UI hierarchy after %s attempts.\n' "$attempt" >&2
  return 1
}

set_rotation() {
  local rotation="$1"
  local output
  if output="$("$adb_command" shell wm user-rotation lock "$rotation" 2>&1)" &&
      [[ -z "${output//[[:space:]]/}" ]]; then
    return
  fi
  "$adb_command" shell settings put system accelerometer_rotation 0
  "$adb_command" shell settings put system user_rotation "$rotation"
}

orientation_matches() {
  local expected="$1"
  python3 - "$ui_xml" "$expected" "$package" <<'PY'
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

wait_orientation() {
  local expected="$1"
  local rotation="$2"
  for _ in $(seq 1 20); do
    dump_ui
    if orientation_matches "$expected"; then
      return
    fi
    set_rotation "$rotation"
    sleep 1
  done
  printf 'Android did not settle into %s orientation.\n' "$expected" >&2
  cat "$ui_xml" >&2
  return 1
}

node_center() {
  local mode="$1"
  local value="$2"
  python3 "$ui_node_center_script" "$ui_xml" "$mode" "$value"
}

scrollable_swipe() {
  local direction="$1"
  local coordinates
  if ! coordinates="$(python3 - "$ui_xml" "$direction" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

path, direction = sys.argv[1:]
root = ET.parse(path).getroot()
candidates = []
for node in root.iter("node"):
    if node.attrib.get("scrollable") != "true":
        continue
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib["bounds"])
    if not match:
        continue
    left, top, right, bottom = map(int, match.groups())
    width = right - left
    height = bottom - top
    if width > 0 and height > 0:
        candidates.append((width * height, left, top, right, bottom))
if not candidates:
    raise SystemExit(1)

# Tablet layouts expose both the navigation rail and the library list as
# scrollable. The list is the largest viewport; selecting the first node made
# CI swipe the rail forever while waiting for page-two tracks.
_, left, top, right, bottom = max(candidates)
x = (left + right) // 2
padding = max(24, (bottom - top) // 5)
upper = top + padding
lower = bottom - padding
if direction == "up":
    print(x, lower, x, upper)
else:
    print(x, upper, x, lower)
PY
)"; then
    return 1
  fi
  "$adb_command" shell input swipe $coordinates 250
}

scroll_until_bridge_line() {
  local expected="$1"
  for _ in $(seq 1 50); do
    if bridge_has_line "$expected"; then
      return
    fi
    dump_ui
    if ! scrollable_swipe up; then
      sleep 1
      continue
    fi
    sleep 1
  done
  cat "$ui_xml" >&2
  cat "$bridge_log" >&2
  return 1
}

scroll_until_node() {
  local direction="$1"
  local mode="$2"
  local value="$3"
  for _ in $(seq 1 50); do
    dump_ui
    if node_center "$mode" "$value" >/dev/null; then
      return
    fi
    if ! scrollable_swipe "$direction"; then
      # Activity recreation briefly shows a non-scrollable reconnecting screen.
      # Wait for the retained destination instead of treating that as a test failure.
      sleep 1
      continue
    fi
    sleep 1
  done
  cat "$ui_xml" >&2
  return 1
}

wait_node() {
  local mode="$1"
  local value="$2"
  local center=""
  for _ in $(seq 1 30); do
    dump_ui
    if center="$(node_center "$mode" "$value")"; then
      printf '%s\n' "$center"
      return
    fi
    sleep 1
  done
  printf 'Timed out waiting for UI node: mode=%s value=%q\n' "$mode" "$value" >&2
  cat "$ui_xml" >&2
  return 1
}

tap_node() {
  local center
  if ! center="$(wait_node "$1" "$2")"; then
    printf 'Unable to tap UI node: mode=%s value=%q\n' "$1" "$2" >&2
    return 1
  fi
  "$adb_command" shell input tap $center
}

clear_focused_edit_text() {
  "$adb_command" shell input keyevent KEYCODE_MOVE_END
  for _ in $(seq 1 64); do
    "$adb_command" shell input keyevent KEYCODE_DEL >/dev/null
  done
}

dismiss_ime_if_visible() {
  if "$adb_command" shell dumpsys input_method | tr -d '\r' | grep -q 'mInputShown=true'; then
    "$adb_command" shell input keyevent 4
    sleep 1
  fi
}

replace_focused_edit_text() {
  local value="$1"
  local character
  local index
  local keycode
  local stable_reads
  # Older Android input commands do not reliably accept multiple key codes in
  # one invocation. Send each delete separately so no argument is interpreted
  # as text by the focused field.
  for _ in $(seq 1 5); do
    clear_focused_edit_text

    if [[ "$value" =~ ^[0-9.]+$ ]]; then
      # Numeric fields are driven with key events because `input text` can
      # append delayed stray characters on API 31 under emulator load.
      for ((index = 0; index < ${#value}; index++)); do
        character="${value:index:1}"
        if [[ "$character" == "." ]]; then
          keycode="KEYCODE_PERIOD"
        else
          keycode="KEYCODE_${character}"
        fi
        "$adb_command" shell input keyevent "$keycode"
      done
    else
      "$adb_command" shell input text "$value"
    fi

    # Require several consecutive exact reads. A single immediate match is
    # insufficient because queued emulator input may arrive after the dump.
    stable_reads=0
    for _ in $(seq 1 3); do
      sleep 1
      dump_ui
      if ! node_center edit-text "$value" >/dev/null; then
        break
      fi
      stable_reads=$((stable_reads + 1))
    done
    if (( stable_reads == 3 )); then
      return
    fi
  done
  cat "$ui_xml" >&2
  return 1
}

enter_edit_text() {
  local value="$1"
  local center=""
  local attempt
  dump_ui
  if node_center edit-text "$value" >/dev/null; then
    return
  fi
  if center="$(node_center desc "Clear search")"; then
    "$adb_command" shell input tap $center
    sleep 1
  fi
  for attempt in $(seq 1 5); do
    dump_ui
    center="$(node_center class "android.widget.EditText")"
    "$adb_command" shell input tap $center
    sleep 1
    dump_ui
    if node_center focused-edit "" >/dev/null; then
      break
    fi
  done
  node_center focused-edit "" >/dev/null
  replace_focused_edit_text "$value"
}

focus_pairing_code_field() {
  local center

  dump_ui
  node_center text "Pair securely" >/dev/null || return 1
  center="$(node_center class "android.widget.EditText")" || return 1
  "$adb_command" shell input tap $center
  sleep 1
  dump_ui
  node_center focused-edit "" >/dev/null
}

wait_for_pairing_code_field() {
  local attempt

  for attempt in $(seq 1 12); do
    if focus_pairing_code_field; then
      return
    fi
    sleep 1
  done
  return 1
}

connect_manual_address() {
  local address="$1"
  local attempt
  local center
  local app_pid

  for attempt in $(seq 1 5); do
    if focus_pairing_code_field; then
      return
    fi

    dump_ui
    if ! center="$(node_center class "android.widget.EditText")"; then
      printf 'Manual address field was unavailable on attempt %s.\n' "$attempt" >&2
      break
    fi

    "$adb_command" shell input tap $center
    sleep 1
    replace_focused_edit_text "$address"
    # Submit through the field's IME Go action while focus is authoritative.
    # On API 31, dismissing the IME and immediately tapping the dialog button
    # can leave the text field focused and silently drop the synthetic tap.
    # The app deliberately wires this action to the same resolver as Connect.
    "$adb_command" shell input keyevent KEYCODE_ENTER
    if wait_for_pairing_code_field; then
      return
    fi

    dump_ui
    if node_center edit-text "$address" >/dev/null; then
      # Some API 31 emulator runs keep the IME action in the focused text
      # field instead of dispatching ImeAction.Go. Fall back to the visible
      # dialog action only after the exact address remains authoritative.
      dismiss_ime_if_visible
      dump_ui
      if center="$(node_center text "Connect")"; then
        "$adb_command" shell input tap $center
        if wait_for_pairing_code_field; then
          return
        fi
      fi
    fi

    dump_ui
    for _ in $(seq 1 3); do
      if node_center text "Enter a valid private IPv4 address." >/dev/null ||
          node_center text "Could not reach the computer. Check Wi-Fi and TunesLink Bridge." >/dev/null; then
        break
      fi
      sleep 1
    done

    printf 'Manual connection attempt %s did not reach the pairing step; retrying.\n' \
      "$attempt" >&2
  done

  printf 'Manual connection did not reach the pairing step after bounded retries.\n' >&2
  cat "$ui_xml" >&2
  printf '\nDemo bridge log:\n' >&2
  tail -n 80 "$bridge_log" >&2
  app_pid="$("$adb_command" shell pidof "$package" | tr -d '\r')"
  if [[ -n "$app_pid" ]]; then
    printf '\nTunesLink logcat:\n' >&2
    "$adb_command" logcat -d --pid="$app_pid" -t 200 >&2 || true
  fi
  return 1
}

clear_search() {
  local center
  for _ in $(seq 1 5); do
    dump_ui
    if node_center text "Search your library" >/dev/null; then
      return
    fi
    if center="$(node_center desc "Clear search")"; then
      "$adb_command" shell input tap $center
    fi
    dump_ui
    if center="$(node_center class "android.widget.EditText")"; then
      "$adb_command" shell input tap $center
      clear_focused_edit_text
    fi
    sleep 1
  done
  cat "$ui_xml" >&2
  return 1
}

capture() {
  local name="$1"
  dump_ui
  cp "$ui_xml" "${reports}/${name}.xml"
  "$adb_command" exec-out screencap -p > "${reports}/${name}.png"
}

bridge_has_line() {
  local expected="$1"
  tr -d '\r' < "$bridge_log" | grep -Fxq "$expected"
}

expect_bridge_line() {
  local expected="$1"
  if bridge_has_line "$expected"; then
    return
  fi
  printf 'Expected bridge event was not observed: %s\n' "$expected" >&2
  tail -n 80 "$bridge_log" >&2
  return 1
}

bridge_line_count() {
  local expected="$1"
  tr -d '\r' < "$bridge_log" |
    awk -v expected="$expected" '$0 == expected { count++ } END { print count + 0 }'
}

tap_until_log() {
  local mode="$1"
  local value="$2"
  local expected="$3"
  for _ in $(seq 1 10); do
    tap_node "$mode" "$value"
    for _ in $(seq 1 3); do
      if bridge_has_line "$expected"; then
        return
      fi
      sleep 1
    done
  done
  cat "$bridge_log" >&2
  return 1
}

"$adb_command" wait-for-device
"$adb_command" install -r "$apk" >/dev/null
"$adb_command" shell pm clear "$package" >/dev/null
"$adb_command" logcat -b crash -c >/dev/null 2>&1 || true
"$adb_command" shell am start -W -n "$component" >/dev/null

sdk_int="$("$adb_command" shell getprop ro.build.version.sdk | tr -d '\r')"
entry_ready=0
for _ in $(seq 1 30); do
  dump_ui
  if node_center class "android.widget.EditText" >/dev/null ||
      node_center text "Allow local network access" >/dev/null; then
    entry_ready=1
    break
  fi

  # Act on the same UI snapshot we just inspected. API 31 can deliver the tap
  # from a prior frame while a fresh wait is starting; waiting only for the
  # welcome-screen action then deadlocks after the manual dialog replaces it.
  if center="$(node_center text "Enter address")"; then
    "$adb_command" shell input tap $center
  fi
  sleep 1
done
if (( entry_ready != 1 )); then
  printf 'Android did not reach manual address entry or local-network permission.\n' >&2
  cat "$ui_xml" >&2
  exit 1
fi
if (( sdk_int >= 37 )); then
  wait_node text "Allow local network access" >/dev/null
  capture "local-network-rationale"
  tap_node text "Continue"
  wait_node text "Allow" >/dev/null
  capture "local-network-permission"
  tap_node text "Allow"
  sleep 1
  permission_state="$("$adb_command" shell dumpsys package "$package" | tr -d '\r')"
  [[ "$permission_state" == *"android.permission.ACCESS_LOCAL_NETWORK: granted=true"* ]]
fi
wait_node class "android.widget.EditText" >/dev/null
connect_manual_address "$bridge_address"

# connect_manual_address returns only after the pairing field is focused.
replace_focused_edit_text "123456"
# Pairing exposes ImeAction.Done and maps it to the same guarded pair action as
# the dialog button. Keep submission on the focused field so older emulators do
# not lose a synthetic button tap during keyboard dismissal.
"$adb_command" shell input keyevent KEYCODE_ENTER

wait_node text "Midnight Drive" >/dev/null
wait_node text "Browse Library" >/dev/null
for _ in $(seq 1 20); do
  grep -q "^state-stream:open$" "$bridge_log" && break
  sleep 1
done
[[ "$(tr -d '\r' < "$bridge_log" | grep -c '^state-stream:open$')" -eq 1 ]]
capture "paired-library-categories"

# Collection browsing is a distinct Library flow. Verify playlist drill-down and back navigation
# before opening the full Songs collection used by the paging checks below.
tap_node text "Playlists"
wait_node text "Night Drive" >/dev/null
capture "playlist-collections"
tap_node text "Night Drive"
wait_node text "Midnight Drive" >/dev/null
capture "playlist-tracks"
"$adb_command" shell input keyevent 4
wait_node text "Night Drive" >/dev/null
"$adb_command" shell input keyevent 4
wait_node text "Browse Library" >/dev/null
tap_node text "Songs"
wait_node text "Golden Static" >/dev/null
capture "paired-library-songs"

# Rotate while page two is deliberately delayed. The retained library controller must deliver
# the completed page to the recreated Activity rather than the destroyed screen instance.
scroll_until_bridge_line "library-delay:60:1800"
expect_bridge_line "library-delay:60:1800"
set_rotation 1
sleep 1
set_rotation 0
wait_orientation portrait 0
for _ in $(seq 1 30); do
  bridge_has_line "library-page::60:60:23:false" && break
  sleep 0.2
done
expect_bridge_line "library-page::60:60:23:false"
scroll_until_node up text "Archive Track 061"
capture "delayed-page-survives-rotation"

# Verify that automatic infinite scrolling loaded the final partial page. Bounds
# and swipe coordinates come from the UI hierarchy so this remains valid when
# the mini-player or viewport dimensions change.
scroll_until_node up text "Archive Track 083"
capture "last-partial-library-page"
scroll_until_node down text "Golden Static"

playback_streams_before="$(bridge_line_count "state-stream:open")"
playback_closes_before="$(bridge_line_count "state-stream:close")"
tap_until_log text "Golden Static" "play:Golden Static"
grep -q "^artwork:" "$bridge_log"
wait_node desc "Artwork for Golden Static" >/dev/null
capture "decoded-deterministic-artwork"

# Exercise the transport command contract, not only the dedicated track-play endpoint.
tap_until_log desc "Pause" "command:playPause"

# Now Playing exposes playback-mode controls backed by iTunes state.
# Tablet workspace already surfaces shuffle/repeat in the top chrome, so the
# dedicated destination control is absent there. Phone layouts still need the
# bottom-nav destination before those controls appear.
if ! node_center desc "Turn shuffle on" >/dev/null; then
  tap_node desc "Now Playing"
fi
wait_node desc "Turn shuffle on" >/dev/null
tap_until_log desc "Turn shuffle on" "command:shuffle"
tap_until_log desc "Turn repeat all on" "command:repeat"
capture "now-playing-modes"

# Playback state is pushed over the existing SSE connection. Commands must not
# tear it down and briefly present the app as disconnected.
sleep 3
playback_streams_after="$(bridge_line_count "state-stream:open")"
playback_closes_after="$(bridge_line_count "state-stream:close")"
if (( playback_streams_after != playback_streams_before ||
      playback_closes_after != playback_closes_before )); then
  printf 'Playback commands restarted the state stream.\n' >&2
  tail -n 80 "$bridge_log" >&2
  exit 1
fi

sleep 2
tap_node desc "Search"
enter_edit_text "Golden"
for _ in $(seq 1 20); do
  bridge_has_line "library:Golden" && break
  sleep 1
done
expect_bridge_line "library:Golden"
expect_bridge_line "library-page:Golden:0:60:1:false"
wait_node text "1 result" >/dev/null
capture "searched-library"

# Clearing the dedicated Search destination returns to its focused empty prompt. Full song
# browsing remains in Library > Songs rather than being duplicated under Search.
clear_search
echo "checkpoint: search text cleared"
wait_node text "Search your music" >/dev/null
echo "checkpoint: empty search prompt restored"
capture "search-reset"
echo "checkpoint: search reset captured"
tap_node text "Cancel"
tap_node desc "Library"
wait_node text "Midnight Drive" >/dev/null

# Keep the live state stream while the app process is in the background.
streams_before="$(bridge_line_count "state-stream:open")"
closes_before="$(bridge_line_count "state-stream:close")"
"$adb_command" shell input keyevent 3
sleep 3
streams_after="$(bridge_line_count "state-stream:open")"
closes_after="$(bridge_line_count "state-stream:close")"
if (( streams_after != streams_before || closes_after != closes_before )); then
  printf 'Backgrounding restarted or closed the state stream.\n' >&2
  tail -n 80 "$bridge_log" >&2
  exit 1
fi
"$adb_command" shell am start -W -n "$component" >/dev/null
wait_node text "Midnight Drive" >/dev/null
sleep 1
streams_after="$(bridge_line_count "state-stream:open")"
closes_after="$(bridge_line_count "state-stream:close")"
if (( streams_after != streams_before || closes_after != closes_before )); then
  printf 'Foregrounding replaced a healthy background state stream.\n' >&2
  tail -n 80 "$bridge_log" >&2
  exit 1
fi
capture "foreground-stream-preserved"

# Exercise configuration recreation and accessibility-sized text while paired.
set_rotation 1
sleep 2
wait_node text "Midnight Drive" >/dev/null
wait_orientation landscape 1
capture "paired-landscape"
set_rotation 0
"$adb_command" shell settings put system font_scale 1.5
"$adb_command" shell am force-stop "$package"
"$adb_command" shell am start -W -n "$component" >/dev/null
wait_node text "Browse Library" >/dev/null
wait_orientation portrait 0
capture "paired-large-text"
tap_node desc "Now Playing"
  wait_node desc "iTunes volume, 64%" >/dev/null
capture "paired-large-text-player-controls"
tap_node desc "Library"
"$adb_command" shell settings put system font_scale 2.0
set_rotation 1
"$adb_command" shell am force-stop "$package"
"$adb_command" shell am start -W -n "$component" >/dev/null
wait_node text "Browse Library" >/dev/null
wait_orientation landscape 1
capture "paired-font-200-landscape"
python3 "$workspace/scripts/android-ui-contract.py" \
  "${reports}/paired-font-200-landscape.xml" \
  --width 1920 --height 1080 --density 420 --require-scrollable \
  --navigation-rail-width-dp 80
set_rotation 0
"$adb_command" shell settings put system font_scale 1.0

"$adb_command" shell am force-stop "$package"
"$adb_command" shell am start -W -n "$component" >/dev/null
wait_node text "Golden Static" >/dev/null
capture "restored-pairing"

if (( sdk_int >= 37 )); then
  "$adb_command" shell pm revoke "$package" \
    android.permission.ACCESS_LOCAL_NETWORK
  "$adb_command" shell am force-stop "$package"
  "$adb_command" shell am start -W -n "$component" >/dev/null
  wait_node text "Allow local network access" >/dev/null
  capture "revoked-local-network"
  tap_node text "Continue"
  wait_node text "Allow" >/dev/null
  tap_node text "Allow"
  sleep 1
  wait_node text "Golden Static" >/dev/null
  capture "regranted-local-network"
fi

# Restart the same pinned bridge identity without SSE and verify lifecycle-scoped polling fallback.
if [[ -n "$bridge_pid" ]]; then
  kill "$bridge_pid" >/dev/null 2>&1 || true
  wait "$bridge_pid" >/dev/null 2>&1 || true
  bridge_pid=""
  legacy_bridge_log="${reports}/bridge-legacy.log"
  "$dotnet_command" run --project "$bridge_project" --configuration Release -- \
    --port "$bridge_port" --discovery-port 0 --pair-code 123456 --legacy-state \
    --config-directory "$bridge_config" >"$legacy_bridge_log" 2>&1 &
  bridge_pid=$!
  for _ in $(seq 1 60); do
    grep -q "^ready:" "$legacy_bridge_log" 2>/dev/null && break
    sleep 1
  done
  grep -q "^ready:" "$legacy_bridge_log"
  "$adb_command" shell am force-stop "$package"
  "$adb_command" shell am start -W -n "$component" >/dev/null
  wait_node text "Midnight Drive" >/dev/null
  for _ in $(seq 1 20); do
    grep -q "^state-poll$" "$legacy_bridge_log" && break
    sleep 1
  done
  grep -q "^state-poll$" "$legacy_bridge_log"
  capture "legacy-polling-fallback"

  # A different certificate must not inherit the saved trust. The client preserves the old pairing
  # while requiring the user to inspect the computer, compare the new ID, and pair again.
  kill "$bridge_pid" >/dev/null 2>&1 || true
  wait "$bridge_pid" >/dev/null 2>&1 || true
  bridge_pid=""
  changed_bridge_log="${reports}/bridge-changed-identity.log"
  "$dotnet_command" run --project "$bridge_project" --configuration Release -- \
    --port "$bridge_port" --discovery-port 0 --pair-code 123456 \
    --config-directory "${bridge_config}-changed" >"$changed_bridge_log" 2>&1 &
  bridge_pid=$!
  for _ in $(seq 1 60); do
    grep -q "^ready:" "$changed_bridge_log" 2>/dev/null && break
    sleep 1
  done
  wait_node text "Computer identity changed" >/dev/null
  wait_node text "Pair again" >/dev/null
  capture "changed-certificate-rejected"
fi

"$adb_command" logcat -d -b crash > "${reports}/crash-buffer.txt"
if grep -q "$package" "${reports}/crash-buffer.txt"; then
  cat "${reports}/crash-buffer.txt"
  exit 1
fi
