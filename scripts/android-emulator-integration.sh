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
apk="${TunesLink_ANDROID_APK:-${workspace}/android/app/build/outputs/apk/debug/app-debug.apk}"
reports="${TunesLink_INTEGRATION_REPORTS:-${workspace}/android/app/build/reports/device-integration}"
bridge_project="${workspace}/test-support/TuneLinkDemoBridge/TuneLinkDemoBridge.csproj"
bridge_config="${RUNNER_TEMP:-/tmp}/TunesLink-demo-bridge-$$"
bridge_log="${reports}/bridge.log"
ui_xml="${reports}/window.xml"
ui_node_center_script="${workspace}/scripts/android-ui-node-center.py"
ui_blocking_script="${workspace}/scripts/android-ui-blocking-foreground.py"
dotnet_command="${DOTNET_COMMAND:-dotnet}"
adb_command="${ADB_COMMAND:-adb}"
bridge_port="${TunesLink_BRIDGE_PORT:-45832}"
bridge_address="${TunesLink_BRIDGE_ADDRESS:-10.0.2.2}"
profile="${TunesLink_DEVICE_PROFILE:-full}"
case "$profile" in
  full|quick|visual) ;;
  *) printf 'Unknown device profile: %s\n' "$profile" >&2; exit 2 ;;
esac
source "$workspace/scripts/android-emulator-settings.sh"

mkdir -p "$reports"
bridge_pid=""
if [[ "${TunesLink_BRIDGE_EXTERNAL:-0}" != "1" ]]; then
  "$dotnet_command" run --project "$bridge_project" --configuration Release -- \
    --port "$bridge_port" --discovery-port 0 --pair-code 123456 \
    --library-delay-ms 1800 \
    --playback-fault-file "${bridge_config}.playback-fault" \
    --artwork-fault-file "${bridge_config}.artwork-fault" \
    --config-directory "$bridge_config" >"$bridge_log" 2>&1 &
  bridge_pid=$!
fi

cleanup() {
  if [[ -n "$bridge_pid" ]]; then
    kill "$bridge_pid" >/dev/null 2>&1 || true
    wait "$bridge_pid" >/dev/null 2>&1 || true
  fi
  android_emulator_set_font_scale "$adb_command" 1.0 >/dev/null 2>&1 || true
  "$adb_command" shell settings put system accelerometer_rotation 1 >/dev/null 2>&1 || true
  "$adb_command" shell wm user-rotation free >/dev/null 2>&1 || true
  "$adb_command" shell wm size reset >/dev/null 2>&1 || true
  "$adb_command" shell wm density reset >/dev/null 2>&1 || true
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

source "$workspace/scripts/android-emulator-ui.sh"

"$adb_command" wait-for-device
suppress_gboard_first_run
android_emulator_prepare_settings "$adb_command" "$workspace"
android_emulator_set_font_scale "$adb_command" 1.0
"$adb_command" shell wm size 1080x1920
"$adb_command" shell wm density 420
set_rotation 0
"$adb_command" install -r "$apk" >/dev/null
"$adb_command" shell pm clear "$package" >/dev/null
"$adb_command" logcat -b crash -c >/dev/null 2>&1 || true
"$adb_command" shell am start -W -n "$component" >/dev/null

sdk_int="$("$adb_command" shell getprop ro.build.version.sdk | tr -d '\r')"
entry_ready=0
startup_relaunched=0
for _ in $(seq 1 30); do
  dump_ui
  if node_center class "android.widget.EditText" >/dev/null ||
      node_center text "Allow local network access" >/dev/null ||
      node_center id "com.android.permissioncontroller:id/permission_allow_button" >/dev/null; then
    entry_ready=1
    break
  fi

  # Clearing data can remove the old task after am start reports success on API 37.
  # Retry that initial launch once if only Launcher is visible. The final crash-log
  # assertion still fails the run if an app crash caused the disappearance.
  if (( startup_relaunched == 0 )) &&
      grep -q 'package="com.google.android.apps.nexuslauncher"' "$ui_xml" &&
      ! grep -q "package=\"$package\"" "$ui_xml"; then
    startup_relaunched=1
    "$adb_command" shell am start -W -n "$component" >/dev/null
    continue
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
  dump_ui
  if ! node_center id "com.android.permissioncontroller:id/permission_allow_button" >/dev/null; then
    wait_node text "Allow local network access" >/dev/null
    capture "local-network-rationale"
    tap_node text "Continue"
  fi
  wait_node text "Allow" >/dev/null
  capture "local-network-permission"
  deny_local_network_permission
  wait_node text "Local network access is required to find and control your computer." >/dev/null
  capture "local-network-denied"
  tap_node text "Enter address"
  tap_node text "Continue"
  tap_node text "Allow"
  sleep 1
  permission_state="$("$adb_command" shell dumpsys package "$package" | tr -d '\r')"
  [[ "$permission_state" == *"android.permission.ACCESS_LOCAL_NETWORK: granted=true"* ]]
fi
# Smoke ends in a rotated tablet viewport. Once startup/permission handling has
# returned to the app, settle the phone layout before entering connection data.
# The wide layout has an Albums workspace, not the expected Browse Library page.
wait_orientation portrait 0
wait_node class "android.widget.EditText" >/dev/null
connect_manual_address "$bridge_address"

# Exercise the real 429 contract and its countdown before completing pairing.
if [[ -n "$bridge_pid" && "$profile" == "full" ]]; then
  replace_focused_edit_text "000000"
  "$adb_command" shell input keyevent KEYCODE_ENTER
  # Submission hides the keyboard. Sending Back during that asynchronous hide
  # can reach the dialog instead and cancel pairing; wait for the result first.
  wait_node text "The pairing code was not accepted. Check the current code on your computer." >/dev/null
  for _ in $(seq 1 5); do
    tap_node text "Pair securely"
    sleep 1
  done
  dump_ui
  grep -q "Too many pairing attempts. Try again in" "$ui_xml"
  python3 "$workspace/scripts/android-pairing-cooldown-check.py" "$ui_xml" blocked
  capture "pairing-cooldown"
  tap_node class "android.widget.EditText"
  replace_focused_edit_text "123456"
  dismiss_ime_if_visible
  for _ in $(seq 1 65); do
    dump_ui
    if python3 "$workspace/scripts/android-pairing-cooldown-check.py" "$ui_xml" ready; then
      break
    fi
    sleep 1
  done
  python3 "$workspace/scripts/android-pairing-cooldown-check.py" "$ui_xml" ready
  # Restore authoritative field focus after waiting for the cooldown. API 31
  # can drop a synthetic button tap while the input window is settling.
  wait_for_pairing_code_field
else
  # connect_manual_address returns only after the pairing field is focused.
  replace_focused_edit_text "123456"
fi
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

# Empty Search must return to Library after any keyboard/search-focus dismissal.
open_search
# Wait for navigation to finish before sending Back; API 31 can deliver the
# preceding navigation tap after the next input command has already arrived.
wait_node text "Search your music" >/dev/null
dismiss_ime_if_visible
"$adb_command" shell input keyevent 4
dump_ui
if ! node_center text "Browse Library" >/dev/null; then
  "$adb_command" shell input keyevent 4
fi
wait_node text "Browse Library" >/dev/null
capture "empty-search-back"

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
if [[ "$profile" == "full" ]]; then
  scroll_until_bridge_line "library-delay:60:1800"
  expect_bridge_line "library-delay:60:1800"
  set_rotation 1
  wait_orientation landscape 1
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
fi

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
open_search
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
return_to_library
wait_node text "Midnight Drive" >/dev/null

# Intermediate Android versions exercise pairing, keyboard navigation, playback,
# search and recreation. The oldest/newest versions retain the complete journey.
if [[ "$profile" == "quick" ]]; then
  set_rotation 1
  wait_orientation landscape 1
  capture "paired-landscape"
  set_rotation 0
  wait_orientation portrait 0
  wait_node text "Midnight Drive" >/dev/null
  assert_no_app_crash
  echo "Android quick integration passed."
  exit 0
fi

# Keep the live state stream while the app process is in the background.
if [[ "$profile" == "full" ]]; then
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
fi

# Playback failure feedback must remain visible on every connected surface.
if [[ -n "$bridge_pid" ]]; then
  tap_node desc "Library"
  dump_ui
  if ! node_center text "Browse Library" >/dev/null; then
    "$adb_command" shell input keyevent 4
  fi
  wait_node text "Browse Library" >/dev/null
  touch "${bridge_config}.playback-fault"
  tap_node text "Songs"
  tap_node text "Golden Static"
  wait_node text "Couldn’t play Golden Static" >/dev/null
  capture "command-error-library"
  tap_node desc "Search"
  dismiss_ime_if_visible
  wait_node text "Couldn’t play Golden Static" >/dev/null
  capture "command-error-search"
  tap_node desc "Now Playing"
  wait_node text "Couldn’t play Golden Static" >/dev/null
  capture "command-error-player"
  "$adb_command" shell wm size 1600x1000
  "$adb_command" shell wm density 240
  set_rotation 0
  wait_orientation landscape 0
  wait_node text "Couldn’t play Golden Static" >/dev/null
  capture "command-error-tablet"
  tap_node text "Dismiss"
  dump_ui
  if grep -q "Couldn’t play Golden Static" "$ui_xml"; then
    printf 'Dismissed command error is still visible.\n' >&2
    exit 1
  fi
  rm -f "${bridge_config}.playback-fault"
  # A ~620 dp window (split screen, small tablet): the workspace header wraps its now-playing
  # surface onto a second row instead of collapsing the title, and no control is clipped.
  "$adb_command" shell wm size 1240x1800
  "$adb_command" shell wm density 320
  set_rotation 0
  wait_orientation portrait 0
  wait_node text "Midnight Drive" >/dev/null
  capture "tablet-medium-width"
  python3 "$workspace/scripts/android-ui-contract.py" \
    "${reports}/tablet-medium-width.xml" --width 1240 --height 1800 --density 320
  "$adb_command" shell wm size 1080x1920
  "$adb_command" shell wm density 420
  set_rotation 0
  tap_node desc "Library"
fi

# Exercise configuration recreation and accessibility-sized text while paired.
set_rotation 1
sleep 2
wait_node text "Midnight Drive" >/dev/null
wait_orientation landscape 1
capture "paired-landscape"
set_rotation 0
android_emulator_set_font_scale "$adb_command" 1.5
"$adb_command" shell am force-stop "$package"
"$adb_command" shell am start -W -n "$component" >/dev/null
wait_node text "Browse Library" >/dev/null
wait_orientation portrait 0
capture "paired-large-text"
tap_node desc "Now Playing"
  # The slider's label is "iTunes volume"; its value ("64%") is a stateDescription, which
  # uiautomator dumps do not include.
  wait_node desc "iTunes volume" >/dev/null
capture "paired-large-text-player-controls"
tap_node desc "Library"
android_emulator_set_font_scale "$adb_command" 2.0
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
android_emulator_set_font_scale "$adb_command" 1.0

"$adb_command" shell am force-stop "$package"
"$adb_command" shell am start -W -n "$component" >/dev/null
wait_node text "Golden Static" >/dev/null
capture "restored-pairing"

# Cold launches must display yesterday's covers even when the bridge cannot refresh them.
# This operates only on the disposable debug app's private cache, never a user's library.
if [[ -n "$bridge_pid" && "$profile" == "full" ]]; then
  tap_node text "Songs"
  tap_node text "Golden Static"
  # The current song's row also has a "Now Playing" icon description; use the tab's text.
  tap_node text "Now Playing"
  wait_node desc "iTunes volume" >/dev/null
  wait_node desc "Artwork for Golden Static" >/dev/null
  # DemoLibrary identifies tracks by the first 20 hex digits of their title's SHA-256.
  player_artwork_line="artwork:$(printf %s 'Golden Static' | sha256sum | cut -c1-20):900"
  player_fault_line="artwork-fault:${player_artwork_line#artwork:}"
  "$adb_command" shell am force-stop "$package"
  cache_epoch="$(date -u -d '1 day ago' '+%s')"
  # Android 6's touch needs an explicit fractional second to set the supplied date.
  cache_stamp="$(date -u -d "@$cache_epoch" '+%Y-%m-%dT%H:%M:%S.0Z')"
  cache_times="$("$adb_command" shell "run-as $package sh -c 'touch -d $cache_stamp cache/artwork-v1/*.jpg && stat -c %Y cache/artwork-v1/*.jpg'" | tr -d '\r')"
  # Old ADB shells do not propagate command failures; verify the actual timestamps too.
  awk -v expected="$cache_epoch" '$0 != expected { exit 1 } END { if (NR == 0) exit 1 }' <<< "$cache_times"
  touch "${bridge_config}.artwork-fault"
  for attempt in 1 2; do
    faults_before="$(bridge_line_count "$player_fault_line")"
    "$adb_command" shell am start -W -n "$component" >/dev/null
    # Let the restored Library settle before looking up its navigation controls.
    wait_node text "Browse Library" >/dev/null
    sleep 1
    tap_node text "Now Playing"
    wait_node desc "iTunes volume" >/dev/null
    wait_node desc "Artwork for Golden Static" >/dev/null
    capture "cached-artwork-refresh-failed-${attempt}"
    (( $(bridge_line_count "$player_fault_line") > faults_before ))
    tap_node desc "Library"
    wait_node text "Browse Library" >/dev/null
    tap_node text "Songs"
    wait_node text "Golden Static" >/dev/null
    capture "cached-library-artwork-${attempt}"
    "$adb_command" shell am force-stop "$package"
  done
  rm -f "${bridge_config}.artwork-fault"
  artwork_before="$(bridge_line_count "$player_artwork_line")"
  "$adb_command" shell am start -W -n "$component" >/dev/null
  wait_node text "Browse Library" >/dev/null
  sleep 1
  tap_node text "Now Playing"
  wait_node desc "iTunes volume" >/dev/null
  wait_node desc "Artwork for Golden Static" >/dev/null
  for _ in $(seq 1 20); do
    (( $(bridge_line_count "$player_artwork_line") > artwork_before )) && break
    sleep 0.2
  done
  (( $(bridge_line_count "$player_artwork_line") > artwork_before ))
  capture "cached-artwork-refresh-recovered"
  # A fresh disk hit must not download the same cover again on the next cold launch.
  artwork_before="$(bridge_line_count "$player_artwork_line")"
  "$adb_command" shell am force-stop "$package"
  "$adb_command" shell am start -W -n "$component" >/dev/null
  wait_node text "Browse Library" >/dev/null
  sleep 1
  tap_node text "Now Playing"
  wait_node desc "iTunes volume" >/dev/null
  wait_node desc "Artwork for Golden Static" >/dev/null
  [[ "$(bridge_line_count "$player_artwork_line")" -eq "$artwork_before" ]]
  capture "cached-artwork-fresh-relaunch"
  tap_node desc "Library"
fi

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

  # Two denials must lead to Settings rather than a permission prompt loop.
  "$adb_command" shell pm revoke "$package" android.permission.ACCESS_LOCAL_NETWORK
  "$adb_command" shell pm clear-permission-flags "$package" \
    android.permission.ACCESS_LOCAL_NETWORK user-set user-fixed
  "$adb_command" shell am force-stop "$package"
  "$adb_command" shell am start -W -n "$component" >/dev/null
  tap_node text "Continue"
  deny_local_network_permission
  tap_node text "Enter address"
  tap_node text "Continue"
  deny_local_network_permission
  wait_node text "Local network access is turned off for TunesLink. Open Settings, choose Permissions, and allow Local network." >/dev/null
  tap_node text "Enter address"
  wait_node text "Open Settings" >/dev/null
  capture "local-network-permanently-denied"
  tap_node text "Open Settings"
  wait_node text "Permissions" >/dev/null
  # Apply the Settings grant without depending on the system Settings layout.
  "$adb_command" shell pm grant "$package" android.permission.ACCESS_LOCAL_NETWORK
  "$adb_command" shell input keyevent KEYCODE_BACK
  wait_node text "Golden Static" >/dev/null
  capture "local-network-settings-recovered"
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

assert_no_app_crash
printf 'Android %s integration passed.\n' "$profile"
