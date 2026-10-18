#!/usr/bin/env python3
"""Check scroll clipping and real touch-target failures through the CI contract CLI."""

from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET


HELPER = Path(__file__).with_name("android-ui-contract.py")
PACKAGE = "com.kamyarps.tuneslink"


class UiContractTests(unittest.TestCase):
    def check_contract(
        self, bounds, *, viewport="[298,579][1240,1674]", scrollable=True,
        viewport_class="android.view.View", nested=False, failure=None,
    ):
        root = ET.Element("hierarchy")
        parent = ET.SubElement(root, "node", {
            "package": PACKAGE, "bounds": viewport, "class": viewport_class,
            "scrollable": str(scrollable).lower(),
        })
        if nested:
            parent = ET.SubElement(parent, "node", {"package": PACKAGE})
        ET.SubElement(parent, "node", {
            "package": PACKAGE, "bounds": bounds, "class": "android.view.View",
            "clickable": "true", "enabled": "true", "text": "Song",
        })
        with tempfile.TemporaryDirectory() as directory:
            xml = Path(directory) / "window.xml"
            ET.ElementTree(root).write(xml, encoding="utf-8")
            result = subprocess.run(
                [sys.executable, str(HELPER), str(xml), "--width", "1240",
                 "--height", "1800", "--density", "320"],
                capture_output=True, text=True, check=False,
            )
        self.assertEqual(result.returncode, 1 if failure else 0, result.stderr)
        if failure:
            self.assertIn(failure, result.stderr)

    def test_failed_ci_captures(self):
        # Exact row and list bounds from run 39137848769: API 23 and API 31.
        for bounds, viewport in [
            ("[322,1662][1216,1728]", "[298,552][1240,1728]"),
            ("[322,1591][1216,1674]", "[298,579][1240,1674]"),
        ]:
            with self.subTest(bounds=bounds):
                self.check_contract(bounds, viewport=viewport)

    def test_top_clipped_row(self):
        self.check_contract("[322,579][1216,620]")

    def test_horizontal_clipping(self):
        for bounds in ["[298,700][330,800]", "[1200,700][1240,800]"]:
            with self.subTest(bounds=bounds):
                self.check_contract(bounds)

    def test_unclamped_partial_row(self):
        self.check_contract("[322,1640][1216,1700]")

    def test_nested_scroll_ancestor(self):
        self.check_contract("[322,1591][1216,1674]", nested=True)

    def test_scroll_view_class(self):
        self.check_contract("[322,1591][1216,1674]", scrollable=False,
                            viewport_class="android.widget.ScrollView")

    def test_full_size_target(self):
        self.check_contract("[322,700][410,788]")

    def test_small_interior_target(self):
        self.check_contract("[322,700][1216,760]", failure="small target")

    def test_one_pixel_inside_is_not_clipped(self):
        self.check_contract("[322,1591][1216,1673]", failure="small target")

    def test_non_scroll_edge_is_not_exempt(self):
        self.check_contract("[322,1591][1216,1674]", scrollable=False,
                            failure="small target")

    def test_horizontal_clipping_does_not_hide_short_target(self):
        self.check_contract("[298,700][1216,760]", failure="small target")

    def test_vertical_clipping_does_not_hide_narrow_target(self):
        self.check_contract("[322,1591][350,1674]", failure="small target")

    def test_tiny_scroll_viewport_is_not_exempt(self):
        self.check_contract("[322,1591][1216,1674]", viewport="[298,1591][1240,1674]",
                            failure="small target")

    def test_out_of_screen_clipping_still_fails(self):
        self.check_contract("[322,1640][1250,1700]", failure="clipped node")


if __name__ == "__main__":
    unittest.main()
