#!/usr/bin/env python3
"""Compare deterministic Android screenshots with tracked perceptual baselines.

The decoder intentionally uses only the Python standard library so the CI gate does not add a
package-install or network dependency. System bars are cropped before a 16x16 luminance average
hash and whole-frame average color are calculated. The hash catches layout/content movement while
the color distance catches theme and large material regressions.
"""

from __future__ import annotations

import argparse
import json
import struct
import sys
import zlib
from pathlib import Path


PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def paeth(left: int, up: int, upper_left: int) -> int:
    prediction = left + up - upper_left
    left_distance = abs(prediction - left)
    up_distance = abs(prediction - up)
    upper_left_distance = abs(prediction - upper_left)
    if left_distance <= up_distance and left_distance <= upper_left_distance:
        return left
    if up_distance <= upper_left_distance:
        return up
    return upper_left


def decode_png(path: Path) -> tuple[int, int, int, bytes]:
    data = path.read_bytes()
    if not data.startswith(PNG_SIGNATURE):
        raise ValueError(f"{path} is not a PNG")
    offset = len(PNG_SIGNATURE)
    compressed = bytearray()
    width = height = color_type = bit_depth = interlace = -1
    while offset < len(data):
        length = struct.unpack(">I", data[offset : offset + 4])[0]
        chunk_type = data[offset + 4 : offset + 8]
        chunk = data[offset + 8 : offset + 8 + length]
        offset += 12 + length
        if chunk_type == b"IHDR":
            width, height, bit_depth, color_type, _, _, interlace = struct.unpack(">IIBBBBB", chunk)
        elif chunk_type == b"IDAT":
            compressed.extend(chunk)
        elif chunk_type == b"IEND":
            break
    channels = {0: 1, 2: 3, 4: 2, 6: 4}.get(color_type)
    if bit_depth != 8 or channels is None or interlace != 0:
        raise ValueError(
            f"{path} uses unsupported PNG format: depth={bit_depth}, color={color_type}, interlace={interlace}"
        )
    raw = zlib.decompress(compressed)
    stride = width * channels
    expected = (stride + 1) * height
    if len(raw) != expected:
        raise ValueError(f"{path} has an unexpected decoded size")
    decoded = bytearray(stride * height)
    source = 0
    for y in range(height):
        filter_type = raw[source]
        source += 1
        row_start = y * stride
        for x in range(stride):
            value = raw[source]
            source += 1
            left = decoded[row_start + x - channels] if x >= channels else 0
            up = decoded[row_start - stride + x] if y else 0
            upper_left = decoded[row_start - stride + x - channels] if y and x >= channels else 0
            if filter_type == 1:
                value = (value + left) & 0xFF
            elif filter_type == 2:
                value = (value + up) & 0xFF
            elif filter_type == 3:
                value = (value + ((left + up) // 2)) & 0xFF
            elif filter_type == 4:
                value = (value + paeth(left, up, upper_left)) & 0xFF
            elif filter_type != 0:
                raise ValueError(f"{path} uses unknown PNG filter {filter_type}")
            decoded[row_start + x] = value
    return width, height, channels, bytes(decoded)


def rgb_at(decoded: bytes, width: int, channels: int, x: int, y: int) -> tuple[int, int, int]:
    offset = (y * width + x) * channels
    if channels in (1, 2):
        value = decoded[offset]
        return value, value, value
    return decoded[offset], decoded[offset + 1], decoded[offset + 2]


def fingerprint(path: Path) -> dict[str, object]:
    width, height, channels, decoded = decode_png(path)
    top = round(height * 0.05)
    bottom = round(height * 0.94)
    usable_height = max(1, bottom - top)
    luminance: list[int] = []
    rgb: list[tuple[int, int, int]] = []
    for row in range(16):
        y = min(bottom - 1, top + ((row * 2 + 1) * usable_height // 32))
        for column in range(16):
            x = min(width - 1, (column * 2 + 1) * width // 32)
            red, green, blue = rgb_at(decoded, width, channels, x, y)
            rgb.append((red, green, blue))
            luminance.append((red * 299 + green * 587 + blue * 114) // 1000)
    average_luminance = sum(luminance) / len(luminance)
    bits = "".join("1" if value >= average_luminance else "0" for value in luminance)
    average_rgb = [round(sum(pixel[channel] for pixel in rgb) / len(rgb)) for channel in range(3)]
    return {
        "hash": f"{int(bits, 2):064x}",
        "average_rgb": average_rgb,
    }


def hamming(left: str, right: str) -> int:
    return (int(left, 16) ^ int(right, 16)).bit_count()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument(
        "--manifest",
        type=Path,
        default=Path("scripts/android-visual-baselines.json"),
    )
    parser.add_argument("--print", action="store_true", dest="print_only")
    parser.add_argument("paths", nargs="*")
    args = parser.parse_args()
    root = args.root.resolve()
    if args.print_only:
        generated = {path: fingerprint(root / path) for path in args.paths}
        print(json.dumps(generated, indent=2, sort_keys=True))
        return 0

    manifest_path = args.manifest if args.manifest.is_absolute() else root / args.manifest
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
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
