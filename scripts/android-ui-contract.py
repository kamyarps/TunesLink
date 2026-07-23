#!/usr/bin/env python3
"""Fail an emulator smoke capture when app nodes clip or actionable targets are too small."""

import argparse
import re
import sys
import xml.etree.ElementTree as ET


BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("xml")
    parser.add_argument("--width", type=int, required=True)
    parser.add_argument("--height", type=int, required=True)
    parser.add_argument("--density", type=int, required=True)
    parser.add_argument("--require-scrollable", action="store_true")
    parser.add_argument(
        "--navigation-rail-width-dp",
        type=int,
        help="Require navigation item descendants to stay inside this rail width.",
    )
    args = parser.parse_args()

    root = ET.parse(args.xml).getroot()
    package = "com.kamyarps.tuneslink"
    failures: list[str] = []
    app_nodes = [node for node in root.iter("node") if node.get("package") == package]
    parents = {child: parent for parent in root.iter("node") for child in parent}
    if not app_nodes:
        failures.append("no TunesLink nodes were present")

    has_scroll_container = any(
        node.get("scrollable") == "true" or node.get("class") == "android.widget.ScrollView"
        for node in app_nodes
    )
    if args.require_scrollable and not has_scroll_container:
        failures.append("large-text screen did not expose a scroll container")

    minimum_target = round(44 * args.density / 160)

    def clipped_by_scroll_ancestor(node: ET.Element, bounds: tuple[int, int, int, int]) -> bool:
        left, top, right, bottom = bounds
        ancestor = parents.get(node)
        while ancestor is not None:
            if ancestor.get("scrollable") == "true" or ancestor.get("class") == "android.widget.ScrollView":
                ancestor_match = BOUNDS.fullmatch(ancestor.get("bounds", ""))
                if ancestor_match is not None:
                    ancestor_left, ancestor_top, ancestor_right, ancestor_bottom = map(
                        int, ancestor_match.groups()
                    )
                    if (
                        left < ancestor_left
                        or top < ancestor_top
                        or right > ancestor_right
                        or bottom > ancestor_bottom
                    ):
                        return True
            ancestor = parents.get(ancestor)
        return False

    for node in app_nodes:
        match = BOUNDS.fullmatch(node.get("bounds", ""))
        if match is None:
            continue
        left, top, right, bottom = map(int, match.groups())
        label = node.get("content-desc") or node.get("text") or node.get("class") or "node"
        if left < 0 or top < 0 or right > args.width or bottom > args.height:
            failures.append(f"clipped node {label!r}: {left},{top},{right},{bottom}")
        if node.get("clickable") == "true" and node.get("enabled") == "true":
            if (
                right - left < minimum_target or bottom - top < minimum_target
            ) and not clipped_by_scroll_ancestor(node, (left, top, right, bottom)):
                failures.append(
                    f"small target {label!r}: {right-left}x{bottom-top}px; "
                    f"minimum {minimum_target}px"
                )

    if args.navigation_rail_width_dp is not None:
        rail_right = round(args.navigation_rail_width_dp * args.density / 160)
        destination_labels = {"Library", "Now Playing", "Search"}
        for node in app_nodes:
            if node.get("clickable") != "true" or node.get("content-desc") not in destination_labels:
                continue
            for descendant in node.iter("node"):
                match = BOUNDS.fullmatch(descendant.get("bounds", ""))
                if match is None:
                    continue
                _, _, right, _ = map(int, match.groups())
                if right > rail_right:
                    failures.append(
                        f"navigation label escaped {args.navigation_rail_width_dp}dp rail: "
                        f"{node.get('content-desc')!r} ended at {right}px; rail ends at {rail_right}px"
                    )

    if failures:
        print("Android UI contract failed:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
