package com.kamyarps.tuneslink

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

// Lucide 1.48.0 icon paths, licensed ISC/MIT. See assets/lucide-license.txt.
// Keep the icon geometry in one place so Android uses a consistent 24dp line style.
internal object TuneLinkIcons {
    // disc-3.svg
    val Album: ImageVector by lazy {
        lineIcon("Album", autoMirror = false,
            filledPaths = emptySet(),
            "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0",
            "M6 12c0-1.7.7-3.2 1.8-4.2",
            "M10 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0",
            "M18 12c0 1.7-.7 3.2-1.8 4.2",
        )
    }
    // arrow-left.svg
    val ArrowBack: ImageVector by lazy {
        lineIcon("ArrowBack", autoMirror = true,
            filledPaths = emptySet(),
            "m12 19-7-7 7-7",
            "M19 12H5",
        )
    }
    // circle-check.svg
    val CheckCircle: ImageVector by lazy {
        lineIcon("CheckCircle", autoMirror = false,
            filledPaths = emptySet(),
            "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0",
            "m16 9-5.5 5.5L8 12",
        )
    }
    // chevron-left.svg
    val ChevronLeft: ImageVector by lazy {
        lineIcon("ChevronLeft", autoMirror = false,
            filledPaths = emptySet(),
            "m15 18-6-6 6-6",
        )
    }
    // chevron-right.svg
    val ChevronRight: ImageVector by lazy {
        lineIcon("ChevronRight", autoMirror = false,
            filledPaths = emptySet(),
            "m9 18 6-6-6-6",
        )
    }
    // x.svg
    val Clear: ImageVector by lazy {
        lineIcon("Clear", autoMirror = false,
            filledPaths = emptySet(),
            "M18 6 6 18",
            "m6 6 12 12",
        )
    }
    // monitor.svg
    val Computer: ImageVector by lazy {
        lineIcon("Computer", autoMirror = false,
            filledPaths = emptySet(),
            "M4 3H20a2 2 0 0 1 2 2V15a2 2 0 0 1 -2 2H4a2 2 0 0 1 -2 -2V5a2 2 0 0 1 2 -2z",
            "M8 21L16 21",
            "M12 17L12 21",
        )
    }
    // audio-lines.svg
    val Equalizer: ImageVector by lazy {
        lineIcon("Equalizer", autoMirror = false,
            filledPaths = emptySet(),
            "M2 10v3",
            "M6 6v11",
            "M10 3v18",
            "M14 8v7",
            "M18 5v13",
            "M22 10v3",
        )
    }
    // circle-alert.svg
    val ErrorOutline: ImageVector by lazy {
        lineIcon("ErrorOutline", autoMirror = false,
            filledPaths = emptySet(),
            "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0",
            "M12 8L12 12",
            "M12 16L12.01 16",
        )
    }
    // chevron-up.svg
    val ExpandLess: ImageVector by lazy {
        lineIcon("ExpandLess", autoMirror = false,
            filledPaths = emptySet(),
            "m18 15-6-6-6 6",
        )
    }
    // library.svg
    val LibraryMusic: ImageVector by lazy {
        lineIcon("LibraryMusic", autoMirror = false,
            filledPaths = emptySet(),
            "m16 6 4 14",
            "M12 6v14",
            "M8 8v12",
            "M4 4v16",
        )
    }
    // lock.svg
    val Lock: ImageVector by lazy {
        lineIcon("Lock", autoMirror = false,
            filledPaths = emptySet(),
            "M5 11H19a2 2 0 0 1 2 2V20a2 2 0 0 1 -2 2H5a2 2 0 0 1 -2 -2V13a2 2 0 0 1 2 -2z",
            "M7 11V7a5 5 0 0 1 10 0v4",
        )
    }
    // music-2.svg
    val MusicNote: ImageVector by lazy {
        lineIcon("MusicNote", autoMirror = false,
            filledPaths = emptySet(),
            "M4 18a4 4 0 1 0 8 0a4 4 0 1 0 -8 0",
            "M12 18V2l7 4",
        )
    }
    // pause.svg
    val Pause: ImageVector by lazy {
        lineIcon("Pause", autoMirror = false,
            filledPaths = setOf(0, 1),
            "M15 3H18a1 1 0 0 1 1 1V20a1 1 0 0 1 -1 1H15a1 1 0 0 1 -1 -1V4a1 1 0 0 1 1 -1z",
            "M6 3H9a1 1 0 0 1 1 1V20a1 1 0 0 1 -1 1H6a1 1 0 0 1 -1 -1V4a1 1 0 0 1 1 -1z",
        )
    }
    // user-round.svg
    val Person: ImageVector by lazy {
        lineIcon("Person", autoMirror = false,
            filledPaths = emptySet(),
            "M7 8a5 5 0 1 0 10 0a5 5 0 1 0 -10 0",
            "M20 21a8 8 0 0 0-16 0",
        )
    }
    // play.svg
    val PlayArrow: ImageVector by lazy {
        lineIcon("PlayArrow", autoMirror = false,
            filledPaths = setOf(0),
            "M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z",
        )
    }
    // list-music.svg
    val PlaylistPlay: ImageVector by lazy {
        lineIcon("PlaylistPlay", autoMirror = true,
            filledPaths = emptySet(),
            "M16 5H3",
            "M11 12H3",
            "M11 19H3",
            "M21 16V5",
            "M15 16a3 3 0 1 0 6 0a3 3 0 1 0 -6 0",
        )
    }
    // repeat.svg
    val Repeat: ImageVector by lazy {
        lineIcon("Repeat", autoMirror = false,
            filledPaths = emptySet(),
            "m17 2 4 4-4 4",
            "M3 11v-1a4 4 0 0 1 4-4h14",
            "m7 22-4-4 4-4",
            "M21 13v1a4 4 0 0 1-4 4H3",
        )
    }
    // repeat-1.svg
    val RepeatOne: ImageVector by lazy {
        lineIcon("RepeatOne", autoMirror = false,
            filledPaths = emptySet(),
            "m17 2 4 4-4 4",
            "M3 11v-1a4 4 0 0 1 4-4h14",
            "m7 22-4-4 4-4",
            "M21 13v1a4 4 0 0 1-4 4H3",
            "M11 10h1v4",
        )
    }
    // search.svg
    val Search: ImageVector by lazy {
        lineIcon("Search", autoMirror = false,
            filledPaths = emptySet(),
            "m21 21-4.34-4.34",
            "M3 11a8 8 0 1 0 16 0a8 8 0 1 0 -16 0",
        )
    }
    // shuffle.svg
    val Shuffle: ImageVector by lazy {
        lineIcon("Shuffle", autoMirror = false,
            filledPaths = emptySet(),
            "m18 14 4 4-4 4",
            "m18 2 4 4-4 4",
            "M2 18h1.973a4 4 0 0 0 3.3-1.7l5.454-8.6a4 4 0 0 1 3.3-1.7H22",
            "M2 6h1.972a4 4 0 0 1 3.6 2.2",
            "M22 18h-6.041a4 4 0 0 1-3.3-1.8l-.359-.45",
        )
    }
    // skip-forward.svg
    val SkipNext: ImageVector by lazy {
        lineIcon("SkipNext", autoMirror = false,
            filledPaths = setOf(1),
            "M21 4v16",
            "M6.029 4.285A2 2 0 0 0 3 6v12a2 2 0 0 0 3.029 1.715l9.997-5.998a2 2 0 0 0 .003-3.432z",
        )
    }
    // skip-back.svg
    val SkipPrevious: ImageVector by lazy {
        lineIcon("SkipPrevious", autoMirror = false,
            filledPaths = setOf(0),
            "M17.971 4.285A2 2 0 0 1 21 6v12a2 2 0 0 1-3.029 1.715l-9.997-5.998a2 2 0 0 1-.003-3.432z",
            "M3 20V4",
        )
    }
    // volume-1.svg
    val VolumeDown: ImageVector by lazy {
        lineIcon("VolumeDown", autoMirror = true,
            filledPaths = emptySet(),
            "M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z",
            "M16 9a5 5 0 0 1 0 6",
        )
    }
    // volume-2.svg
    val VolumeUp: ImageVector by lazy {
        lineIcon("VolumeUp", autoMirror = true,
            filledPaths = emptySet(),
            "M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z",
            "M16 9a5 5 0 0 1 0 6",
            "M19.364 18.364a9 9 0 0 0 0-12.728",
        )
    }
    // wifi-off.svg
    val WifiOff: ImageVector by lazy {
        lineIcon("WifiOff", autoMirror = false,
            filledPaths = emptySet(),
            "M12 20h.01",
            "M8.5 16.429a5 5 0 0 1 7 0",
            "M5 12.859a10 10 0 0 1 5.17-2.69",
            "M19 12.859a10 10 0 0 0-2.007-1.523",
            "M2 8.82a15 15 0 0 1 4.177-2.643",
            "M22 8.82a15 15 0 0 0-11.288-3.764",
            "m2 2 20 20",
        )
    }
}

private fun lineIcon(
    name: String,
    autoMirror: Boolean,
    filledPaths: Set<Int>,
    vararg paths: String,
): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror,
    ).apply {
        paths.forEachIndexed { index, data ->
            addPath(
                pathData = PathParser().parsePathString(data).toNodes(),
                fill = if (index in filledPaths) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
