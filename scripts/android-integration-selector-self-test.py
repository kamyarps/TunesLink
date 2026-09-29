#!/usr/bin/env python3
"""Regression checks for Android emulator UI selectors and input handling."""

from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parent.parent
HELPER = ROOT / "scripts" / "android-ui-node-center.py"
BLOCKING = ROOT / "scripts" / "android-ui-blocking-foreground.py"
IME = ROOT / "scripts" / "android-emulator-ime.sh"
SMOKE = ROOT / "scripts" / "android-emulator-smoke.sh"
INTEGRATION = ROOT / "scripts" / "android-emulator-integration.sh"
APP_PACKAGE = "com.kamyarps.tuneslink"

TABLET_SEARCH = """\
<hierarchy>
  <node text="" class="android.widget.EditText" content-desc="" focused="true" bounds="[10,10][210,70]">
    <node text="Search" class="android.widget.TextView" content-desc="" focused="false" bounds="[50,20][150,60]" />
  </node>
  <node text="Search your music" class="android.widget.TextView" content-desc="" focused="false" bounds="[300,100][600,160]" />
  <node text="Songs" class="android.widget.TextView" content-desc="" focused="false" bounds="[10,200][110,260]" />
</hierarchy>
"""

PHONE_SEARCH = """\
<hierarchy>
  <node text="" class="android.widget.EditText" content-desc="" focused="true" bounds="[10,10][210,70]">
    <node text="Search your library" class="android.widget.TextView" content-desc="" focused="false" bounds="[30,20][190,60]" />
  </node>
  <node text="Cancel" class="android.widget.TextView" content-desc="" focused="false" bounds="[220,10][320,70]" />
  <node text="" class="android.view.View" content-desc="Library" focused="false" bounds="[10,200][110,260]" />
  <node text="Search your music" class="android.widget.TextView" content-desc="" focused="false" bounds="[100,100][400,160]" />
</hierarchy>
"""

GBOARD_SETUP = """\
<hierarchy>
  <node text="Theme" class="android.widget.TextView" package="com.google.android.inputmethod.latin" content-desc="" bounds="[157,104][322,169]" />
  <node text="" class="android.widget.ImageButton" package="com.google.android.inputmethod.latin" content-desc="Navigate up" bounds="[0,63][147,210]" />
  <node text="My themes" class="android.widget.TextView" package="com.google.android.inputmethod.latin" content-desc="" bounds="[0,231][238,286]" />
</hierarchy>
"""

GBOARD_OVER_APP = """\
<hierarchy>
  <node text="Enter address" class="android.widget.Button" package="com.kamyarps.tuneslink" content-desc="" bounds="[40,400][400,480]" />
  <node text="" class="android.widget.EditText" package="com.google.android.inputmethod.latin" content-desc="" bounds="[0,1200][1080,1794]" />
</hierarchy>
"""

ANR_DIALOG = """\
<hierarchy>
  <node text="System UI isn't responding" class="android.widget.TextView" package="android" content-desc="" bounds="[100,800][980,860]" />
  <node text="Wait" class="android.widget.Button" package="android" content-desc="" bounds="[200,900][400,980]" />
</hierarchy>
"""


def match(xml: str, mode: str, value: str, expected: str | None) -> None:
    with tempfile.TemporaryDirectory() as directory:
        fixture = Path(directory) / "window.xml"
        fixture.write_text(xml, encoding="utf-8")
        result = subprocess.run(
            [sys.executable, str(HELPER), str(fixture), mode, value],
            check=False,
            capture_output=True,
            text=True,
        )
    if expected is None:
        if result.returncode == 0:
            raise AssertionError(f"Unexpected {mode} match for {value!r}: {result.stdout.strip()}")
        return
    if result.returncode != 0 or result.stdout.strip() != expected:
        raise AssertionError(
            f"Expected {mode} {value!r} at {expected}, got "
            f"status {result.returncode} and {result.stdout.strip()!r}"
        )


def classify_kind(xml: str, package: str) -> str:
    with tempfile.TemporaryDirectory() as directory:
        fixture = Path(directory) / "window.xml"
        fixture.write_text(xml, encoding="utf-8")
        result = subprocess.run(
            [sys.executable, str(BLOCKING), str(fixture), package],
            check=False,
            capture_output=True,
            text=True,
        )
    if result.returncode != 0:
        raise AssertionError(
            f"Blocking classifier failed: {result.returncode} {result.stderr.strip()}"
        )
    return result.stdout.strip()


def check_input_helpers(source: str) -> None:
    # Exercise the real shell helper with a dump larger than a pipe buffer.
    # API 31 puts mInputShown near the start, so an early-exiting grep can make
    # the preceding tr fail with SIGPIPE and skip dismissal under pipefail.
    start = source.index("dismiss_ime_if_visible() {")
    helper = source[start : source.index("\n}", start) + 2]
    bash = shutil.which("bash")
    if sys.platform == "win32":
        git_bash = Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git/bin/bash.exe"
        if git_bash.is_file():
            bash = str(git_bash)
    if not bash:
        raise AssertionError("Bash is required to test the Android integration helpers")
    script = "set -euo pipefail\n" + helper + r"""
adb_command=fake_adb
back_count=0
sleep() { :; }
fake_adb() {
  case "$*" in
    'shell dumpsys input_method')
      printf 'mInputShown=%s\r\n' "$shown"
      printf '%1048576s\n' ''
      ;;
    'shell input keyevent 4') back_count=$((back_count + 1)) ;;
    *) return 2 ;;
  esac
}
shown=true
dismiss_ime_if_visible
[[ "$back_count" == 1 ]] || { echo 'Visible IME was not dismissed' >&2; exit 1; }
shown=false
dismiss_ime_if_visible
[[ "$back_count" == 1 ]] || { echo 'Hidden IME caused an unintended Back action' >&2; exit 1; }
"""
    start = source.index("return_to_library() {")
    script += source[start : source.index("\n}", start) + 2] + r"""
# Android 6 can expose navigation semantics behind the keyboard after search
# is cleared. A tap at Library's coordinates then types a comma into search.
shown=true
destination=search
injected_text=0
ui_xml=/dev/null
dump_ui() { :; }
scrollable_swipe() { return 1; }
node_center() {
  case "$*" in
    'text Midnight Drive') [[ "$destination" == library ]] ;;
    'text Search your music') [[ "$destination" == search ]] ;;
    'desc Library') printf '100 900\n' ;;
    *) return 1 ;;
  esac
}
fake_adb() {
  case "$*" in
    'shell dumpsys input_method') printf 'mInputShown=%s\r\n' "$shown" ;;
    'shell input keyevent 4') shown=false ;;
    'shell input tap 100 900')
      if [[ "$shown" == true ]]; then
        injected_text=$((injected_text + 1))
      else
        destination=library
      fi
      ;;
    *) return 2 ;;
  esac
}
return_to_library
[[ "$destination" == library && "$injected_text" == 0 ]] || {
  echo 'Library navigation typed into the keyboard after clearing search' >&2; exit 1;
}
ui_xml="$(mktemp)"
trap 'rm -f "$ui_xml"' EXIT
printf '<node text="Archive Track 061"/>\n' > "$ui_xml"
destination=archive
node_center() { [[ "$*" == 'text Midnight Drive' && "$destination" == library ]]; }
scrollable_swipe() {
  [[ "$1" == down ]] || return 1
  destination=library
}
return_to_library
rm -f "$ui_xml"
trap - EXIT
"""
    start = source.index("scroll_until_node() {")
    script += source[start : source.index("\n}", start) + 2] + r"""
# Rotation can restore the list past the desired row. At a boundary, the
# helper must search back through the list instead of swiping the end forever.
ui_xml="$(mktemp)"
trap 'rm -f "$ui_xml"' EXIT
dump_ui() { printf '<row position="%s"/>\n' "$position" > "$ui_xml"; }
node_center() { [[ "$position" == "$target" ]]; }
scrollable_swipe() {
  if [[ "$1" == up ]]; then
    if (( position < 8 )); then position=$((position + 2)); fi
  else
    if (( position > 0 )); then position=$((position - 2)); fi
  fi
}
position=8
target=6
scroll_until_node up text target
position=2
scroll_until_node down text target
# A bridge response can be logged while Android is still applying page two.
# Do not turn back at the old page boundary before the new rows appear.
position=8
target=10
page_loaded=0
remaining_load_ticks=6
dump_ui() { printf '<row position="%s" loaded="%s"/>\n' "$position" "$page_loaded" > "$ui_xml"; }
sleep() {
  if (( remaining_load_ticks > 0 )); then
    remaining_load_ticks=$((remaining_load_ticks - 1))
    if (( remaining_load_ticks == 0 )); then page_loaded=1; fi
  fi
}
scrollable_swipe() {
  if [[ "$1" == up ]]; then
    if (( page_loaded == 1 && position < 10 )); then position=$((position + 2)); fi
  else
    if (( position > 0 )); then position=$((position - 2)); fi
  fi
}
scroll_until_node up text target
[[ "$position" == 10 ]] || { echo 'Delayed page was skipped' >&2; exit 1; }
page_loaded=1
remaining_load_ticks=0
target=9
if scroll_until_node up text missing 2>/dev/null; then
  echo 'Scrolling reported success for a missing row' >&2; exit 1;
fi
rm -f "$ui_xml"
trap - EXIT
"""
    script += IME.read_text(encoding="utf-8") + r"""
fake_adb() {
  case "$*" in
    'shell ime list -a -s'|'shell ime list -s') printf '%s\n' "$available_imes" ;;
    'shell ime enable '*) : ;;
    'shell ime set '*) selected_ime="$4" ;;
    'shell ime disable '*) disabled_ime="$4" ;;
    'shell am force-stop com.google.android.inputmethod.latin') : ;;
    *) return 2 ;;
  esac
}
gboard='com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME'
aosp='com.android.inputmethod.latin/.LatinIME'
voice='com.google.android.googlequicksearchbox/VoiceInputMethodService'
selected_ime="$voice"
disabled_ime=''
available_imes="$voice"$'\n'"$gboard"
android_emulator_suppress_gboard_first_run fake_adb
[[ "$selected_ime" == "$gboard" && -z "$disabled_ime" ]] || {
  echo 'Gboard-only image was left with voice input' >&2; exit 1;
}
available_imes="$available_imes"$'\n'"$aosp"
android_emulator_suppress_gboard_first_run fake_adb
[[ "$selected_ime" == "$aosp" && "$disabled_ime" == "$gboard" ]] || {
  echo 'AOSP keyboard was not preferred over Gboard' >&2; exit 1;
}
"""
    start = source.index("scrollable_swipe() {")
    script += source[start : source.index("\n}", start) + 2] + r"""
ui_xml="$(mktemp)"
swipe_calls="$(mktemp)"
trap 'rm -f "$ui_xml" "$swipe_calls"' EXIT
cat > "$ui_xml" <<'XML'
<hierarchy>
  <node scrollable="true" bounds="[0,0][100,1848]" />
  <node scrollable="true" bounds="[0,220][1080,1459]" />
</hierarchy>
XML
adb_command=fake_adb
fake_adb() { printf '%s\n' "$*" >> "$swipe_calls"; }
scrollable_swipe up precise
scrollable_swipe down precise
scrollable_swipe up
[[ "$(sed -n '1p' "$swipe_calls")" == 'shell input swipe 540 1046 540 633 650' ]] || {
  echo 'Precise swipe did not use a short, slow drag in the list' >&2; exit 1;
}
[[ "$(sed -n '2p' "$swipe_calls")" == 'shell input swipe 540 633 540 1046 650' ]] || {
  echo 'Precise reverse swipe used the wrong bounds' >&2; exit 1;
}
[[ "$(sed -n '3p' "$swipe_calls")" == 'shell input swipe 540 1212 540 467 250' ]] || {
  echo 'Paging swipe no longer reaches the end of the list' >&2; exit 1;
}
"""
    result = subprocess.run([bash, "-s"], input=script, capture_output=True, text=True)
    if result.returncode != 0:
        raise AssertionError(f"Input helper regression: {result.stderr.strip()}")


def main() -> None:
    # API 31 tablet workspaces expose an empty EditText and a separate Search
    # placeholder; they do not expose the phone-only Cancel or Library nodes.
    match(TABLET_SEARCH, "edit-text", "", "110 40")
    match(TABLET_SEARCH, "text", "Search your music", "450 130")
    match(TABLET_SEARCH, "text", "Songs", "60 230")
    match(TABLET_SEARCH, "text", "Cancel", None)
    match(TABLET_SEARCH, "desc", "Library", None)

    # Phone search uses Cancel plus the bottom-navigation Library destination.
    match(PHONE_SEARCH, "edit-text", "", "110 40")
    match(PHONE_SEARCH, "text", "Cancel", "270 40")
    match(PHONE_SEARCH, "desc", "Library", "60 230")
    match(PHONE_SEARCH, "text", "Songs", None)

    source = INTEGRATION.read_text(encoding="utf-8")
    check_input_helpers(source)
    required_contracts = (
        'node_center edit-text ""',
        'node_center text "Cancel"',
        'node_center desc "Library"',
        'node_center text "Songs"',
    )
    for contract in required_contracts:
        if contract not in source:
            raise AssertionError(f"Integration script is missing selector contract: {contract}")
    if 'node_center text "Search your library"' in source:
        raise AssertionError("Empty search detection must not depend on a layout-specific placeholder")

    if classify_kind(GBOARD_SETUP, APP_PACKAGE) != "gboard-setup":
        raise AssertionError("Gboard Theme first-run UI must be classified as blocking")
    if classify_kind(GBOARD_OVER_APP, APP_PACKAGE) != "none":
        raise AssertionError("Gboard IME overlay over TunesLink must not be treated as first-run setup")
    if classify_kind(ANR_DIALOG, APP_PACKAGE) != "anr":
        raise AssertionError("System UI ANR dialog must be classified as blocking")
    if classify_kind(PHONE_SEARCH, APP_PACKAGE) != "none":
        raise AssertionError("App-only dumps must not be classified as blocking")

    blocking_contracts = (
        "gboard-setup",
        "com.google.android.inputmethod.latin",
        "suppress_gboard_first_run",
    )
    for contract in blocking_contracts:
        if contract not in source:
            raise AssertionError(f"Integration script is missing overlay contract: {contract}")
    ime_source = IME.read_text(encoding="utf-8")
    smoke_source = SMOKE.read_text(encoding="utf-8")
    if "android_emulator_suppress_gboard_first_run" not in ime_source:
        raise AssertionError("IME helper is missing Gboard suppression")
    if "gboard-setup" not in smoke_source:
        raise AssertionError("Smoke script must dismiss Gboard first-run UI")

    start = source.index("return_to_library()")
    restore = source[start : source.index("capture()", start)]
    if 'node_center text "Midnight Drive"' not in restore:
        raise AssertionError("return_to_library must wait for Midnight Drive instead of returning after the first Songs tap")
    if 'text="Archive Track' not in restore:
        raise AssertionError("return_to_library must scroll a restored archive page up to Midnight Drive")
    delay = source.index("library-delay:60:1800")
    rotate = source[delay : delay + 400]
    if "wait_orientation landscape 1" not in rotate or rotate.find("wait_orientation landscape 1") > rotate.find("wait_orientation portrait 0"):
        raise AssertionError("Delayed page rotation must wait for landscape before returning to portrait")

    print("Android integration selector self-test passed.")


if __name__ == "__main__":
    main()
