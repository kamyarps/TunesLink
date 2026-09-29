"""Vendor the small Lucide icon subset used by the Android Compose UI.

Run manually when changing icons. The app build uses only the generated Kotlin file.
Source: https://github.com/lucide-icons/lucide/tree/1.48.0/icons
"""

from pathlib import Path
from urllib.request import urlopen
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "android/app/src/main/java/com/kamyarps/tuneslink/TuneLinkIcons.kt"
LUCIDE_VERSION = "1.48.0"
ICONS = {
    "Album": "disc-3",
    "ArrowBack": "arrow-left",
    "CheckCircle": "circle-check",
    "ChevronLeft": "chevron-left",
    "ChevronRight": "chevron-right",
    "Clear": "x",
    "Computer": "monitor",
    "Equalizer": "audio-lines",
    "ErrorOutline": "circle-alert",
    "ExpandLess": "chevron-up",
    "LibraryMusic": "library",
    "Lock": "lock",
    "MusicNote": "music-2",
    "Pause": "pause",
    "Person": "user-round",
    "PlayArrow": "play",
    "PlaylistPlay": "list-music",
    "Repeat": "repeat",
    "RepeatOne": "repeat-1",
    "Search": "search",
    "Shuffle": "shuffle",
    "SkipNext": "skip-forward",
    "SkipPrevious": "skip-back",
    "VolumeDown": "volume-1",
    "VolumeUp": "volume-2",
    "WifiOff": "wifi-off",
}
MIRRORED = {"ArrowBack", "PlaylistPlay", "VolumeDown", "VolumeUp"}
FILLED_PATHS = {
    "Pause": (0, 1),
    "PlayArrow": (0,),
    "SkipNext": (1,),
    "SkipPrevious": (0,),
}


def number(value: str) -> str:
    return f"{float(value):g}"


def path_data(element: ET.Element) -> str:
    tag = element.tag.rsplit("}", 1)[-1]
    a = element.attrib
    if tag == "path":
        return a["d"]
    if tag == "line":
        return f'M{number(a["x1"])} {number(a["y1"])}L{number(a["x2"])} {number(a["y2"])}'
    if tag == "circle":
        x, y, r = (float(a[k]) for k in ("cx", "cy", "r"))
        return f"M{x-r:g} {y:g}a{r:g} {r:g} 0 1 0 {2*r:g} 0a{r:g} {r:g} 0 1 0 {-2*r:g} 0"
    if tag == "rect":
        x, y, w, h = (float(a.get(k, "0")) for k in ("x", "y", "width", "height"))
        rx = float(a.get("rx", "0"))
        ry = float(a.get("ry", str(rx)))
        if rx != ry:
            raise ValueError(f"Unsupported nonuniform rect radius: {a}")
        if rx:
            return (f"M{x+rx:g} {y:g}H{x+w-rx:g}a{rx:g} {rx:g} 0 0 1 {rx:g} {rx:g}"
                    f"V{y+h-rx:g}a{rx:g} {rx:g} 0 0 1 {-rx:g} {rx:g}"
                    f"H{x+rx:g}a{rx:g} {rx:g} 0 0 1 {-rx:g} {-rx:g}"
                    f"V{y+rx:g}a{rx:g} {rx:g} 0 0 1 {rx:g} {-rx:g}z")
        return f"M{x:g} {y:g}h{w:g}v{h:g}h{-w:g}z"
    raise ValueError(f"Unsupported SVG element: {tag}")


def fetch_icon(filename: str) -> list[str]:
    url = f"https://raw.githubusercontent.com/lucide-icons/lucide/{LUCIDE_VERSION}/icons/{filename}.svg"
    with urlopen(url, timeout=20) as response:
        root = ET.fromstring(response.read())
    if root.attrib.get("viewBox") != "0 0 24 24":
        raise ValueError(f"Unexpected viewBox in {filename}")
    return [path_data(child) for child in root]


def main() -> None:
    lines = [
        "package com.kamyarps.tuneslink",
        "",
        "import androidx.compose.ui.graphics.Color",
        "import androidx.compose.ui.graphics.SolidColor",
        "import androidx.compose.ui.graphics.StrokeCap",
        "import androidx.compose.ui.graphics.StrokeJoin",
        "import androidx.compose.ui.graphics.vector.ImageVector",
        "import androidx.compose.ui.graphics.vector.PathParser",
        "import androidx.compose.ui.unit.dp",
        "",
        f"// Lucide {LUCIDE_VERSION} icon paths, licensed ISC/MIT. See assets/lucide-license.txt.",
        "// Keep the icon geometry in one place so Android uses a consistent 24dp line style.",
        "internal object TuneLinkIcons {",
    ]
    for name, source in ICONS.items():
        paths = fetch_icon(source)
        lines.append(f"    // {source}.svg")
        lines.append(f"    val {name}: ImageVector by lazy {{")
        lines.append(f'        lineIcon("{name}", autoMirror = {str(name in MIRRORED).lower()},')
        if name in FILLED_PATHS:
            indices = ", ".join(str(index) for index in FILLED_PATHS[name])
            lines.append(f"            filledPaths = setOf({indices}),")
        else:
            lines.append("            filledPaths = emptySet(),")
        for data in paths:
            lines.append(f'            "{data}",')
        lines.append("        )")
        lines.append("    }")
    lines += [
        "}",
        "",
        "private fun lineIcon(",
        "    name: String,",
        "    autoMirror: Boolean,",
        "    filledPaths: Set<Int>,",
        "    vararg paths: String,",
        "): ImageVector =",
        "    ImageVector.Builder(",
        "        name = name,",
        "        defaultWidth = 24.dp,",
        "        defaultHeight = 24.dp,",
        "        viewportWidth = 24f,",
        "        viewportHeight = 24f,",
        "        autoMirror = autoMirror,",
        "    ).apply {",
        "        paths.forEachIndexed { index, data ->",
        "            addPath(",
        "                pathData = PathParser().parsePathString(data).toNodes(),",
        "                fill = if (index in filledPaths) SolidColor(Color.Black) else null,",
        "                stroke = SolidColor(Color.Black),",
        "                strokeLineWidth = 2f,",
        "                strokeLineCap = StrokeCap.Round,",
        "                strokeLineJoin = StrokeJoin.Round,",
        "            )",
        "        }",
        "    }.build()",
        "",
    ]
    DEST.write_text("\n".join(lines), encoding="utf-8")
    print(f"Generated {len(ICONS)} icons in {DEST}")


if __name__ == "__main__":
    main()
