# Shared emulator interaction helpers. Sourced by the device suite and helper tests.

assert_no_app_crash() {
  "$adb_command" logcat -d -b crash > "${reports}/crash-buffer.txt"
  if grep -Eq "$package|hasReadColorBufferDma" "${reports}/crash-buffer.txt"; then
    cat "${reports}/crash-buffer.txt"
    return 1
  fi
}

pull_ui_hierarchy() {
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

blocking_foreground_kind() {
  python3 "$ui_blocking_script" "$ui_xml" "$package"
}

dismiss_blocking_foreground() {
  local kind
  local center=""
  kind="$(blocking_foreground_kind)"
  case "$kind" in
    anr)
      printf 'Dismissing System UI ANR dialog.\n' >&2
      if center="$(node_center text "Wait")"; then
        "$adb_command" shell input tap $center
      else
        "$adb_command" shell input keyevent KEYCODE_BACK
      fi
      sleep 2
      return 0
      ;;
    system-crash)
      printf 'Dismissing System UI crash dialog.
' >&2
      if center="$(node_center text "Close app")"; then
        "$adb_command" shell input tap $center
      else
        "$adb_command" shell input keyevent KEYCODE_BACK
      fi
      sleep 2
      return 0
      ;;
    gboard-setup)
      printf 'Dismissing Gboard first-run UI covering TunesLink.\n' >&2
      if center="$(node_center desc "Navigate up")"; then
        "$adb_command" shell input tap $center
      else
        "$adb_command" shell input keyevent KEYCODE_BACK
      fi
      sleep 1
      return 0
      ;;
  esac
  return 1
}

dump_ui() {
  local attempt
  pull_ui_hierarchy
  for attempt in $(seq 1 8); do
    if ! dismiss_blocking_foreground; then
      return
    fi
    pull_ui_hierarchy
  done
  if [[ "$(blocking_foreground_kind)" == "gboard-setup" ]]; then
    printf 'Force-stopping Gboard first-run activity.\n' >&2
    "$adb_command" shell am force-stop com.google.android.inputmethod.latin >/dev/null 2>&1 || true
    sleep 1
    pull_ui_hierarchy
  fi
}

suppress_gboard_first_run() {
  source "$workspace/scripts/android-emulator-ime.sh"
  android_emulator_suppress_gboard_first_run "$adb_command"
}

set_rotation() {
  local rotation="$1"
  local output
  # Smoke restores accelerometer rotation on exit. If this lock path succeeds
  # without disabling it, later emulator frames can rotate back to landscape.
  "$adb_command" shell settings put system accelerometer_rotation 0 >/dev/null
  if output="$("$adb_command" shell wm user-rotation lock "$rotation" 2>&1)" &&
      [[ -z "${output//[[:space:]]/}" ]]; then
    return
  fi
  "$adb_command" shell settings put system user_rotation "$rotation"
}

orientation_matches() {
  local expected="$1"
  local expected_rotation="$2"
  python3 - "$ui_xml" "$expected" "$expected_rotation" "$package" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

xml, expected, expected_rotation, package = sys.argv[1:]
try:
    root = ET.parse(xml).getroot()
    rotation = int(root.attrib.get("rotation", "0"))
except (OSError, ValueError, ET.ParseError):
    raise SystemExit(1)
# Check both the requested rotation and settled viewport. A tablet override can
# be naturally landscape at rotation 0, unlike the default phone viewport.
if rotation != int(expected_rotation):
    raise SystemExit(1)
sizes = []
for node in root.iter("node"):
    if node.get("package") != package:
        continue
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    if match:
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
  local settled=0
  for _ in $(seq 1 20); do
    dump_ui
    if orientation_matches "$expected" "$rotation"; then
      # UI automation can restore the rotation it observed while connecting.
      # A second matching dump confirms that the first one did not undo the lock.
      settled=$((settled + 1))
      if (( settled >= 2 )); then return; fi
    else
      settled=0
      set_rotation "$rotation"
    fi
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
  local precision="${2:-fast}"
  local coordinates
  if ! coordinates="$(python3 - "$ui_xml" "$direction" "$precision" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

path, direction, precision = sys.argv[1:]
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
# A short, slow drag keeps adjacent UI dumps overlapping. The normal paging
# swipe can fling past an exact row on Android 6 after Activity recreation.
padding = max(24, (bottom - top) // (3 if precision == "precise" else 5))
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
  if [[ "$precision" == precise ]]; then
    "$adb_command" shell input swipe $coordinates 650
  else
    "$adb_command" shell input swipe $coordinates 250
  fi
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
  local previous_ui="" snapshot="" reversed=0 stalled=0
  for _ in $(seq 1 50); do
    dump_ui
    if node_center "$mode" "$value" >/dev/null; then
      return
    fi
    snapshot="$(cat "$ui_xml")"
    if [[ "$snapshot" == "$previous_ui" ]]; then
      # The bridge may have returned the next page before the phone has applied
      # it. An unchanged viewport at the page boundary is not yet the list end.
      stalled=$((stalled + 1))
      if (( stalled < 8 )); then
        sleep 1
        continue
      fi
      if (( reversed == 1 )); then break; fi
      if [[ "$direction" == up ]]; then direction=down; else direction=up; fi
      reversed=1
      stalled=0
    else
      stalled=0
    fi
    previous_ui="$snapshot"
    if ! scrollable_swipe "$direction" precise; then
      # Activity recreation briefly shows a non-scrollable reconnecting screen.
      # Wait for the retained destination instead of treating that as a test failure.
      previous_ui=""
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

deny_local_network_permission() {
  local center
  wait_node text "Allow" >/dev/null
  center="$(node_center id "com.android.permissioncontroller:id/permission_deny_button" ||
    node_center id "com.android.permissioncontroller:id/permission_deny_and_dont_ask_again_button")"
  "$adb_command" shell input tap $center
}

open_search() {
  local center=""

  dump_ui
  # Tablet workspaces expose Search as an EditText in the top bar. On API 31,
  # its placeholder is reported as text rather than as a content description.
  if center="$(node_center class "android.widget.EditText")"; then
    "$adb_command" shell input tap $center
    return
  fi

  # Phone layouts expose Search as a navigation destination.
  tap_node desc "Search"
}

clear_focused_edit_text() {
  "$adb_command" shell input keyevent KEYCODE_MOVE_END
  for _ in $(seq 1 64); do
    "$adb_command" shell input keyevent KEYCODE_DEL >/dev/null
  done
}

dismiss_ime_if_visible() {
  # Consume the full dump: grep -q can close the pipe early, causing tr to exit
  # with SIGPIPE under pipefail and making a visible keyboard look hidden.
  if "$adb_command" shell dumpsys input_method | tr -d '\r' | grep 'mInputShown=true' >/dev/null; then
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
    # The empty-field state is the stable contract. Phone layouts expose
    # "Search your library" as the field placeholder, while tablet layouts
    # expose "Search" and render "Search your music" in the content pane.
    if node_center edit-text "" >/dev/null; then
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

return_to_library() {
  local center=""

  # Older images can expose navigation nodes behind the keyboard after search
  # is cleared. Dismiss it before tapping so navigation cannot type into IME.
  dismiss_ime_if_visible
  for _ in $(seq 1 20); do
    dump_ui

    if node_center text "Midnight Drive" >/dev/null; then
      return
    fi

    # Phone search exposes a Cancel action while the field is active, followed
    # by the bottom-navigation Library destination.
    if center="$(node_center text "Cancel")"; then
      "$adb_command" shell input tap $center
      sleep 1
      # Cancel must keep the keyboard dismissed, including on Android 6 where
      # clearing focus used to refocus the text field and reopen the input method.
      local ime_hidden=0
      for _ in $(seq 1 5); do
        if ! "$adb_command" shell dumpsys input_method | tr -d '\r' | grep 'mInputShown=true' >/dev/null; then
          ime_hidden=1
          break
        fi
        sleep 1
      done
      if (( ime_hidden == 0 )); then
        printf 'Search Cancel left the keyboard visible.\n' >&2
        return 1
      fi
      continue
    fi
    if node_center text "Search your music" >/dev/null ||
        node_center text "Search Results" >/dev/null ||
        node_center text "1 result" >/dev/null; then
      if center="$(node_center text "Songs")"; then
        "$adb_command" shell input tap $center
      elif center="$(node_center desc "Library")"; then
        "$adb_command" shell input tap $center
      else
        "$adb_command" shell input keyevent KEYCODE_BACK
      fi
      sleep 1
      continue
    fi

    # Leaving Search can restore Songs at the archive page from earlier paging.
    # Midnight Drive is on page one, so scroll instead of re-tapping Library.
    if grep -q 'text="Archive Track' "$ui_xml"; then
      if ! scrollable_swipe down; then
        sleep 1
      fi
      sleep 1
      continue
    fi

    if center="$(node_center desc "Library")"; then
      "$adb_command" shell input tap $center
      sleep 1
      continue
    fi

    # Tablet search is part of the persistent library workspace. Selecting a
    # sidebar category cancels search and restores the Library destination.
    # Do not return on the first tap: the field can still be focused, and an
    # earlier Songs page can still be scrolled to archive tracks.
    if center="$(node_center text "Songs")"; then
      "$adb_command" shell input tap $center
      sleep 1
      continue
    fi

    if ! scrollable_swipe down; then
      sleep 1
    fi
    sleep 1
  done

  printf 'Unable to restore the Songs list to Midnight Drive.\n' >&2
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
