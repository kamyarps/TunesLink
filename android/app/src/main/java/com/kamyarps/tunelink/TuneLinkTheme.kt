package com.kamyarps.tuneslink

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
internal data class TunesLinkColors(
    val canvas: Color,
    val surface: Color,
    val raisedSurface: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val separator: Color,
    val accentText: Color,
    val brandStart: Color,
    val brandEnd: Color,
    val onBrandText: Color,
    val success: Color,
    val danger: Color,
    val focusIndicator: Color,
)

internal object TunesLinkMotion {
    val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
    val EaseInOut = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)
    const val PressDown = 90
    const val PressRelease = 160
    const val SmallFeedback = 120
    const val ModalEnter = 220
    const val ModalExit = 160
    const val StatusCrossfade = 180
    const val AmbientColor = 260
    const val ArtworkCrossfade = 260
    const val AlbumDetailEnter = 220
    const val AlbumDetailExit = 150
    const val ReducedMotionFade = 160
    const val PlayerShared = 280
    const val PlayerSharedStiffness = 450f
}

@Immutable
internal data class TunesLinkMotionPolicy(
    val spatialEnabled: Boolean = true,
)

internal object TunesLinkSpacing {
    val small = 8.dp
    val medium = 12.dp
    val large = 16.dp
    val page = 24.dp
}

internal object TunesLinkShapes {
    val control = 12.dp
    val card = 22.dp
}

internal object TunesLinkSizes {
    val minimumTarget = 48.dp
    val compactArtwork = 48.dp
    val navigationRailWidth = 80.dp
    val readableContentMaxWidth = 760.dp
}

private fun composeColors(tokens: TunesLinkPaletteTokens) = TunesLinkColors(
    canvas = Color(tokens.canvas),
    surface = Color(tokens.surface),
    raisedSurface = Color(tokens.raisedSurface),
    primaryText = Color(tokens.primaryText),
    secondaryText = Color(tokens.secondaryText),
    separator = Color(tokens.separator),
    accentText = Color(tokens.accentText),
    brandStart = Color(tokens.brandStart),
    brandEnd = Color(tokens.brandEnd),
    onBrandText = Color(tokens.onBrandText),
    success = Color(tokens.success),
    danger = Color(tokens.danger),
    focusIndicator = Color(tokens.accentText),
)

private val DarkTunesLinkColors = composeColors(TunesLinkPalettes.Dark)
private val LightTunesLinkColors = composeColors(TunesLinkPalettes.Light)
private val HighContrastDarkTunesLinkColors = composeColors(TunesLinkPalettes.HighContrastDark)
private val HighContrastLightTunesLinkColors = composeColors(TunesLinkPalettes.HighContrastLight)

private val LocalTunesLinkColors = staticCompositionLocalOf { DarkTunesLinkColors }
private val LocalTunesLinkMotionPolicy = staticCompositionLocalOf { TunesLinkMotionPolicy() }

internal object TunesLinkTheme {
    val colors: TunesLinkColors
        @Composable @ReadOnlyComposable get() = LocalTunesLinkColors.current
    val motion: TunesLinkMotionPolicy
        @Composable @ReadOnlyComposable get() = LocalTunesLinkMotionPolicy.current
}

private val TunesLinkTypography = androidx.compose.material3.Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.25).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 25.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

@Composable
internal fun TunesLinkDesignTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    highContrastEnabled: Boolean = false,
    animationsEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = when {
        darkTheme && highContrastEnabled -> HighContrastDarkTunesLinkColors
        !darkTheme && highContrastEnabled -> HighContrastLightTunesLinkColors
        darkTheme -> DarkTunesLinkColors
        else -> LightTunesLinkColors
    }
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.accentText,
            onPrimary = colors.canvas,
            background = colors.canvas,
            onBackground = colors.primaryText,
            surface = colors.surface,
            onSurface = colors.primaryText,
            surfaceVariant = colors.raisedSurface,
            onSurfaceVariant = colors.secondaryText,
            outline = colors.separator,
            error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.accentText,
            onPrimary = Color.White,
            background = colors.canvas,
            onBackground = colors.primaryText,
            surface = colors.surface,
            onSurface = colors.primaryText,
            surfaceVariant = colors.raisedSurface,
            onSurfaceVariant = colors.secondaryText,
            outline = colors.separator,
            error = colors.danger,
        )
    }
    val motionPolicy = TunesLinkMotionPolicy(spatialEnabled = animationsEnabled)
    androidx.compose.runtime.CompositionLocalProvider(
        LocalTunesLinkColors provides colors,
        LocalTunesLinkMotionPolicy provides motionPolicy,
    ) {
        MaterialTheme(colorScheme = scheme, typography = TunesLinkTypography, content = content)
    }
}
