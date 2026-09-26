#!/usr/bin/env python3
"""Assert pairing submission availability using the actual accessibility tree."""

import sys
import xml.etree.ElementTree as ET


def main() -> int:
    root = ET.parse(sys.argv[1]).getroot()
    parents = {child: parent for parent in root.iter() for child in parent}
    for node in root.iter("node"):
        if node.get("text") != "Pair securely":
            continue
        while node is not None and node.get("clickable") != "true" and node.get("class") != "android.widget.Button":
            node = parents.get(node)
        if node is None:
            return 1
        enabled = node.get("enabled") == "true"
        return 0 if enabled == (sys.argv[2] == "ready") else 1
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
