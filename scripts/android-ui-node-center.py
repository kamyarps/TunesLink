#!/usr/bin/env python3
"""Print the center of the first matching node in a uiautomator hierarchy."""

import re
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    if len(sys.argv) != 4:
        print("usage: android-ui-node-center.py XML MODE VALUE", file=sys.stderr)
        return 2

    path, mode, value = sys.argv[1:]
    attributes = {"text": "text", "desc": "content-desc", "class": "class"}
    if mode not in {*attributes, "focused-edit", "edit-text"}:
        print(f"unsupported match mode: {mode}", file=sys.stderr)
        return 2

    root = ET.parse(path).getroot()
    for node in root.iter("node"):
        if mode == "focused-edit":
            matches = (
                node.attrib.get("class") == "android.widget.EditText"
                and node.attrib.get("focused") == "true"
            )
        elif mode == "edit-text":
            matches = (
                node.attrib.get("class") == "android.widget.EditText"
                and node.attrib.get("text") == value
            )
        else:
            attribute = attributes[mode]
            matches = node.attrib.get(attribute, "") == value

        if not matches:
            continue
        bounds = re.fullmatch(
            r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", "")
        )
        if bounds:
            left, top, right, bottom = map(int, bounds.groups())
            print((left + right) // 2, (top + bottom) // 2)
            return 0
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
