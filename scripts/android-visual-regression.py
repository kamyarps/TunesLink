#!/usr/bin/env python3
"""Compare deterministic Android screenshots with tracked perceptual baselines.

System bars are cropped before a 16x16 area-average luminance hash and full cropped-image
average color are calculated. Install scripts/requirements-visual.txt to run this optional gate.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from PIL import Image, ImageStat


ALGORITHM = "area-average-v1"


def image_fingerprint(image: Image.Image) -> dict[str, object]:
    width, height = image.size
    top = round(height * 0.05)
    bottom = max(top + 1, round(height * 0.94))
    cropped = image.convert("RGB").crop((0, top, width, bottom))
    # BOX averages every pixel in each cell instead of sampling one point per cell.
    cells = list(cropped.resize((16, 16), Image.Resampling.BOX).get_flattened_data())
    luminance = [(r * 299 + g * 587 + b * 114) / 1000 for r, g, b in cells]
    average = sum(luminance) / len(luminance)
    bits = "".join("1" if value >= average else "0" for value in luminance)
    return {
        "hash": f"{int(bits, 2):064x}",
        "average_rgb": [round(value) for value in ImageStat.Stat(cropped).mean],
    }


def fingerprint(path: Path) -> dict[str, object]:
    with Image.open(path) as image:
        return image_fingerprint(image)


def self_test() -> None:
    blank = Image.new("RGB", (256, 256), "white")
    changed = Image.new("RGB", blank.size, "black")
    # Preserve all 256 points read by the former algorithm; change the rest.
    top, bottom = round(256 * 0.05), round(256 * 0.94)
    for row in range(16):
        for column in range(16):
            changed.putpixel(((column * 2 + 1) * 8,
                              top + (row * 2 + 1) * (bottom - top) // 32), (255, 255, 255))
    assert image_fingerprint(blank)["average_rgb"] != image_fingerprint(changed)["average_rgb"]
    print("Visual comparison detects changes between the former sample points.")


def hamming(left: str, right: str) -> int:
    return (int(left, 16) ^ int(right, 16)).bit_count()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument(
        "--manifest",
        type=Path,
        default=Path("scripts/android-visual-baselines.json"),
    )
    parser.add_argument("--print", action="store_true", dest="print_only")
    parser.add_argument("paths", nargs="*")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    root = args.root.resolve()
    if args.print_only:
        generated = {path: fingerprint(root / path) for path in args.paths}
        print(json.dumps(generated, indent=2, sort_keys=True))
        return 0

    manifest_path = args.manifest if args.manifest.is_absolute() else root / args.manifest
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("algorithm") != ALGORITHM:
        raise ValueError("Visual baselines must use " + ALGORITHM)
    failures: list[str] = []
    for relative_path, expected in manifest["screenshots"].items():
        path = root / relative_path
        if not path.exists():
            failures.append(f"missing screenshot: {relative_path}")
            continue
        actual = fingerprint(path)
        hash_distance = hamming(str(expected["hash"]), str(actual["hash"]))
        color_distance = max(
            abs(int(expected["average_rgb"][index]) - int(actual["average_rgb"][index]))
            for index in range(3)
        )
        max_hash_distance = int(expected.get("max_hash_distance", manifest["max_hash_distance"]))
        max_color_distance = int(expected.get("max_color_distance", manifest["max_color_distance"]))
        print(
            f"{relative_path}: hash distance {hash_distance}/{max_hash_distance}, "
            f"color distance {color_distance}/{max_color_distance}"
        )
        if hash_distance > max_hash_distance or color_distance > max_color_distance:
            failures.append(
                f"meaningful visual difference: {relative_path} "
                f"(hash={hash_distance}, color={color_distance})"
            )
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
