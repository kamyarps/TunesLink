#!/usr/bin/env python3
"""Regression checks for phone/tablet UI-tree selectors used by emulator CI."""

from pathlib import Path
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parent.parent
HELPER = ROOT / "scripts" / "android-ui-node-center.py"
INTEGRATION = ROOT / "scripts" / "android-emulator-integration.sh"

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

    print("Android integration selector self-test passed.")


if __name__ == "__main__":
    main()
