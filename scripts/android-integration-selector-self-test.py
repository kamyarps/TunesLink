#!/usr/bin/env python3
"""Regression checks for phone/tablet UI-tree selectors used by emulator CI."""

from pathlib import Path
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
