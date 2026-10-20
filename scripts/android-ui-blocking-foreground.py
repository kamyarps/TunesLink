#!/usr/bin/env python3
"""Classify a uiautomator dump that is covering the app under test.

Hosted API 31 google_apis emulators can raise Gboard's Theme first-run
activity as the only window in the accessibility tree. The smoke and
integration scripts dismiss that overlay instead of waiting for TunesLink
controls that are no longer inspectable.

System UI can also crash on hosted emulators. Its "keeps stopping" dialog
covers the app the same way the ANR dialog does.

Prints one of: none, anr, system-crash, gboard-setup
"""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET

GBOARD_PACKAGE = "com.google.android.inputmethod.latin"


def classify(path: str, app_package: str) -> str:
    root = ET.parse(path).getroot()
    packages: set[str] = set()
    labels: list[str] = []
    for node in root.iter("node"):
        packages.add(node.attrib.get("package", ""))
        labels.append(node.attrib.get("text", ""))
        labels.append(node.attrib.get("content-desc", ""))

    if any(label == "System UI isn't responding" for label in labels):
        return "anr"
    # Only System UI: a TunesLink crash dialog is a real failure.
    if any(label in ("System UI keeps stopping", "System UI has stopped") for label in labels):
        return "system-crash"

    # An IME overlay still includes the app's nodes. The first-run Theme
    # activity does not, so only treat Gboard as blocking when TunesLink is
    # missing from the dump.
    if app_package not in packages and GBOARD_PACKAGE in packages:
        return "gboard-setup"
    return "none"


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: android-ui-blocking-foreground.py XML APP_PACKAGE", file=sys.stderr)
        return 2
    print(classify(sys.argv[1], sys.argv[2]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

