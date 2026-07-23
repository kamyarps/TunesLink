package com.kamyarps.tuneslink

internal data class TunesLinkPaletteTokens(
    val canvas: Int,
    val surface: Int,
    val raisedSurface: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val separator: Int,
    val accentText: Int,
    val brandStart: Int,
    val brandEnd: Int,
    val onBrandText: Int,
    val success: Int,
    val danger: Int,
)

internal object TunesLinkPalettes {
    val Dark = TunesLinkPaletteTokens(
        canvas = 0xFF09090B.toInt(),
        surface = 0xFF141416.toInt(),
        raisedSurface = 0xFF222226.toInt(),
        primaryText = 0xFFF7F7FA.toInt(),
        secondaryText = 0xFF98989F.toInt(),
        separator = 0xFF34343A.toInt(),
        accentText = 0xFFFF86A3.toInt(),
        brandStart = 0xFFFF5B84.toInt(),
        brandEnd = 0xFFB69CFF.toInt(),
        onBrandText = 0xFF09090B.toInt(),
        success = 0xFF5AD978.toInt(),
        danger = 0xFFFF6B65.toInt(),
    )

    val Light = TunesLinkPaletteTokens(
        canvas = 0xFFFAFAFC.toInt(),
        surface = 0xFFFFFFFF.toInt(),
        raisedSurface = 0xFFF1F1F5.toInt(),
        primaryText = 0xFF151518.toInt(),
        secondaryText = 0xFF56565E.toInt(),
        separator = 0xFFD7D7DD.toInt(),
        accentText = 0xFFB91C45.toInt(),
        brandStart = 0xFFFF5B84.toInt(),
        brandEnd = 0xFFB69CFF.toInt(),
        onBrandText = 0xFF09090B.toInt(),
        success = 0xFF147A34.toInt(),
        danger = 0xFFB42318.toInt(),
    )

    val HighContrastDark = TunesLinkPaletteTokens(
        canvas = 0xFF000000.toInt(),
        surface = 0xFF0A0A0A.toInt(),
        raisedSurface = 0xFF1C1C1E.toInt(),
        primaryText = 0xFFFFFFFF.toInt(),
        secondaryText = 0xFFD1D1D6.toInt(),
        separator = 0xFF8E8E93.toInt(),
        accentText = 0xFFFFB3C4.toInt(),
        brandStart = 0xFFFFB3C4.toInt(),
        brandEnd = 0xFFD7C6FF.toInt(),
        onBrandText = 0xFF000000.toInt(),
        success = 0xFF83F28F.toInt(),
        danger = 0xFFFFB0AA.toInt(),
    )

    val HighContrastLight = TunesLinkPaletteTokens(
        canvas = 0xFFFFFFFF.toInt(),
        surface = 0xFFFFFFFF.toInt(),
        raisedSurface = 0xFFE5E5EA.toInt(),
        primaryText = 0xFF000000.toInt(),
        secondaryText = 0xFF3A3A3C.toInt(),
        separator = 0xFF636366.toInt(),
        accentText = 0xFF8E0038.toInt(),
        brandStart = 0xFFFF7A9D.toInt(),
        brandEnd = 0xFFC5AEFF.toInt(),
        onBrandText = 0xFF000000.toInt(),
        success = 0xFF006B2D.toInt(),
        danger = 0xFF8A0011.toInt(),
    )
}
