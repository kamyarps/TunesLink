package com.kamyarps.tuneslink

import android.graphics.Bitmap
import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private fun Modifier.TunesLinkFocusBorder(
    focused: Boolean,
    color: Color,
    shape: Shape,
    restingColor: Color? = null,
): Modifier = when {
    focused -> border(width = 2.dp, color = color, shape = shape)
    restingColor != null -> border(width = 1.dp, color = restingColor, shape = shape)
    else -> this
}

@Composable
internal fun TunesLinkPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    loading: Boolean = false,
) {
    val motion = TunesLinkTheme.motion
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    // While work is in progress the button keeps its brand fill and shows a spinner, so it reads
    // as "busy" rather than "unavailable". It still ignores taps.
    val active = enabled || loading
    val shape = RoundedCornerShape(TunesLinkShapes.primaryButton)
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled && motion.spatialEnabled) 0.97f else 1f,
        animationSpec = tween(
            durationMillis = if (pressed) TunesLinkMotion.PressDown else TunesLinkMotion.PressRelease,
            easing = TunesLinkMotion.EaseOut,
        ),
        label = "Primary press",
    )
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        interactionSource = source,
        shape = shape,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 15.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = TunesLinkTheme.colors.onBrandText,
            disabledContainerColor = if (loading) Color.Transparent else TunesLinkTheme.colors.raisedSurface,
            disabledContentColor = if (loading) {
                TunesLinkTheme.colors.onBrandText
            } else {
                TunesLinkTheme.colors.secondaryText
            },
        ),
        modifier = modifier
            .scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .TunesLinkFocusBorder(
                focused = focused,
                color = TunesLinkTheme.colors.focusIndicator,
                shape = shape,
            )
            .background(
                brush = if (active) {
                    Brush.linearGradient(listOf(TunesLinkTheme.colors.brandStart, TunesLinkTheme.colors.brandEnd))
                } else {
                    Brush.linearGradient(listOf(TunesLinkTheme.colors.raisedSurface, TunesLinkTheme.colors.raisedSurface))
                },
                shape = shape,
            )
            .heightIn(min = TunesLinkSizes.primaryButtonMinHeight),
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = TunesLinkTheme.colors.onBrandText,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The quieter companion to [TunesLinkPrimaryButton]: same height and corner radius, so a pair of
 * actions reads as one set, with a hairline outline instead of the brand fill.
 */
@Composable
internal fun TunesLinkSecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    color: Color = TunesLinkTheme.colors.primaryText,
) {
    val motion = TunesLinkTheme.motion
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(TunesLinkShapes.primaryButton)
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled && motion.spatialEnabled) 0.97f else 1f,
        animationSpec = tween(
            durationMillis = if (pressed) TunesLinkMotion.PressDown else TunesLinkMotion.PressRelease,
            easing = TunesLinkMotion.EaseOut,
        ),
        label = "Secondary press",
    )
    val contentColor = color.copy(alpha = if (enabled) 1f else DISABLED_CONTENT_ALPHA)
    Row(
        modifier = modifier
            .scale(scale)
            .heightIn(min = TunesLinkSizes.primaryButtonMinHeight)
            .onFocusChanged { focused = it.isFocused }
            .TunesLinkFocusBorder(
                focused = focused,
                color = TunesLinkTheme.colors.focusIndicator,
                shape = shape,
                restingColor = TunesLinkTheme.colors.separator,
            )
            .clip(shape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = source,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyLarge, color = contentColor, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun TunesLinkTonalAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = TunesLinkTheme.colors.primaryText,
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val contentColor = color.copy(alpha = if (enabled) 1f else DISABLED_CONTENT_ALPHA)
    Row(
        modifier = modifier
            .heightIn(min = TunesLinkSizes.minimumTarget)
            .onFocusChanged { focused = it.isFocused }
            .TunesLinkFocusBorder(
                focused = focused,
                color = TunesLinkTheme.colors.focusIndicator,
                shape = RoundedCornerShape(12.dp),
                restingColor = TunesLinkTheme.colors.separator,
            )
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyLarge, color = contentColor, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun playerSubtitle(
    player: PlayerUiState,
    includeAlbum: Boolean,
    unavailableHint: Boolean,
): String {
    playerIdleSubtitle(player, unavailableHint)?.let { return stringResource(it) }
    if (!playerHasTrack(player)) return ""
    val parts = if (includeAlbum) listOf(player.artist, player.album) else listOf(player.artist)
    return parts.filter(String::isNotBlank).joinToString(" · ")
        .ifBlank { stringResource(R.string.unknown_artist) }
}

/**
 * The Now Playing description for the player's artwork. While a new track's artwork is still
 * loading, the previous bitmap stays visible; it must not be announced as the new track's art.
 */
@Composable
internal fun playerArtworkDescription(player: PlayerUiState): String? =
    if (player.artworkIsCurrent) {
        stringResource(R.string.artwork_for, player.title)
    } else {
        null
    }

@Composable
internal fun ArtworkSurface(
    bitmap: Bitmap?,
    description: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = TunesLinkShapes.artworkSmall,
    elevated: Boolean = false,
) {
    val duration = if (TunesLinkTheme.motion.spatialEnabled) {
        TunesLinkMotion.ArtworkCrossfade
    } else {
        TunesLinkMotion.ReducedMotionFade
    }
    val shape = RoundedCornerShape(cornerRadius)
    // Crossfade only animates changes after first composition, so artwork seeded from the memory
    // cache appears immediately instead of fading in from the placeholder on every scroll.
    Crossfade(
        targetState = bitmap,
        animationSpec = tween(duration, easing = TunesLinkMotion.EaseInOut),
        label = "Artwork transition",
        modifier = modifier
            .then(
                if (elevated) {
                    Modifier.shadow(
                        elevation = 18.dp,
                        shape = shape,
                        ambientColor = Color.Black.copy(alpha = 0.18f),
                        spotColor = Color.Black.copy(alpha = 0.28f),
                    )
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .background(TunesLinkTheme.colors.raisedSurface),
    ) { art ->
        if (art == null) {
            ArtworkPlaceholder(Modifier.fillMaxSize())
        } else {
            Image(
                bitmap = art.asImageBitmap(),
                contentDescription = description,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ArtworkPlaceholder(modifier: Modifier) {
    val colors = TunesLinkTheme.colors
    if (!colors.isDark) {
        Image(
            painter = painterResource(R.drawable.default_artwork),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
        return
    }
    // The pastel bitmap glares against the dark canvas; draw a quiet tinted tile instead.
    Box(
        modifier
            .background(colors.raisedSurface)
            .background(
                Brush.linearGradient(
                    listOf(colors.brandStart.copy(alpha = 0.14f), colors.brandEnd.copy(alpha = 0.14f)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            TuneLinkIcons.MusicNote,
            contentDescription = null,
            tint = colors.secondaryText.copy(alpha = 0.72f),
            modifier = Modifier.fillMaxSize(0.42f),
        )
    }
}

@Composable
internal fun TransportCluster(
    player: PlayerUiState,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    enabled: Boolean = true,
) {
    val previousDescription = stringResource(R.string.previous_song)
    val pauseDescription = stringResource(R.string.pause)
    val playDescription = stringResource(R.string.play)
    val nextDescription = stringResource(R.string.next_song)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportButton(
            TuneLinkIcons.SkipPrevious,
            previousDescription,
            onPrevious,
            large = false,
            compact = compact,
            enabled = enabled,
        )
        TransportButton(
            if (player.playing) TuneLinkIcons.Pause else TuneLinkIcons.PlayArrow,
            if (player.playing) pauseDescription else playDescription,
            onPlayPause,
            large = true,
            compact = compact,
            enabled = enabled,
        )
        TransportButton(
            TuneLinkIcons.SkipNext,
            nextDescription,
            onNext,
            large = false,
            compact = compact,
            enabled = enabled,
        )
    }
}

@Composable
private fun TransportButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    large: Boolean,
    compact: Boolean,
    enabled: Boolean = true,
) {
    val motion = TunesLinkTheme.motion
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (pressed && enabled && motion.spatialEnabled) 0.97f else 1f,
        tween(if (pressed) TunesLinkMotion.PressDown else TunesLinkMotion.PressRelease, easing = TunesLinkMotion.EaseOut),
        label = "Transport press",
    )
    Box(
        modifier = Modifier
            .size(
                when {
                    large && compact -> 58.dp
                    large -> 68.dp
                    compact -> 48.dp
                    else -> 52.dp
                },
            )
            .scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .TunesLinkFocusBorder(
                focused = focused,
                color = TunesLinkTheme.colors.focusIndicator,
                shape = CircleShape,
            )
            .clip(CircleShape)
            .background(
                if (large) TunesLinkTheme.colors.primaryText.copy(alpha = if (enabled) 1f else 0.38f)
                else Color.Transparent,
            )
            .semantics(mergeDescendants = true) { contentDescription = description }
            .clickable(enabled = enabled, interactionSource = source, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val iconTransition = if (motion.spatialEnabled) {
            fadeIn(tween(TunesLinkMotion.SmallFeedback, easing = TunesLinkMotion.EaseOut)) togetherWith
                fadeOut(tween(TunesLinkMotion.PressDown, easing = TunesLinkMotion.EaseOut))
        } else {
            fadeIn(tween(TunesLinkMotion.ReducedMotionFade)) togetherWith
                fadeOut(tween(TunesLinkMotion.ReducedMotionFade))
        }
        AnimatedContent(
            targetState = icon,
            transitionSpec = { iconTransition },
            label = "Transport icon",
        ) { displayedIcon ->
            Icon(
                displayedIcon,
                contentDescription = null,
                tint = (if (large) TunesLinkTheme.colors.canvas else TunesLinkTheme.colors.primaryText)
                    .copy(alpha = if (enabled) 1f else 0.38f),
                modifier = Modifier.size(if (large) 34.dp else 30.dp),
            )
        }
    }
}

@Composable
internal fun UnifiedPlayerBar(
    player: PlayerUiState,
    destination: TunesLinkDestination,
    onOpenPlayer: () -> Unit,
    onLibrary: () -> Unit,
    onPlayer: () -> Unit,
    onSearch: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
    showMiniPlayer: Boolean = true,
    controlsEnabled: Boolean = true,
    showNavigation: Boolean = true,
    insetStart: Boolean = true,
) {
    val compactLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Keep the chrome clear of the navigation bar and, in landscape, the camera cutout.
    val insetSides = if (insetStart) {
        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
    } else {
        WindowInsetsSides.End + WindowInsetsSides.Bottom
    }
    Column(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.systemBars.union(WindowInsets.displayCutout).only(insetSides),
            ),
    ) {
        // Remove the footer in the same composition as the destination change.
        // AnimatedVisibility can retain it (and its space) for child animations.
        if (compactLandscape && (showMiniPlayer || showNavigation)) {
            Row(
                Modifier.fillMaxWidth().height(80.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showMiniPlayer) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        MiniPlayerContent(
                            player = player,
                            onOpenPlayer = onOpenPlayer,
                            onPlayPause = onPlayPause,
                            controlsEnabled = controlsEnabled,
                            compact = true,
                            modifier = Modifier
                                .widthIn(max = TunesLinkSizes.readableContentMaxWidth)
                                .fillMaxWidth(),
                        )
                    }
                }
                if (showNavigation) {
                    DestinationActions(
                        destination = destination,
                        onLibrary = onLibrary,
                        onPlayer = onPlayer,
                        onSearch = onSearch,
                        modifier = Modifier.width(300.dp).fillMaxHeight(),
                    )
                }
            }
        } else {
            if (showMiniPlayer) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    MiniPlayerContent(
                        player = player,
                        onOpenPlayer = onOpenPlayer,
                        onPlayPause = onPlayPause,
                        controlsEnabled = controlsEnabled,
                        compact = false,
                        modifier = Modifier
                            .widthIn(max = TunesLinkSizes.readableContentMaxWidth)
                            .fillMaxWidth(),
                    )
                }
            }
            if (showNavigation) {
                DestinationActions(
                    destination = destination,
                    onLibrary = onLibrary,
                    onPlayer = onPlayer,
                    onSearch = onSearch,
                    modifier = Modifier.fillMaxWidth().height(80.dp),
                )
            }
        }
    }
}

@Composable
private fun MiniPlayerContent(
    player: PlayerUiState,
    onOpenPlayer: () -> Unit,
    onPlayPause: () -> Unit,
    controlsEnabled: Boolean,
    compact: Boolean,
    modifier: Modifier,
) {
    val openNowPlaying = stringResource(R.string.open_now_playing)
    val playPauseDescription = stringResource(if (player.playing) R.string.pause else R.string.play)
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .heightIn(min = TunesLinkSizes.minimumTarget)
            .onFocusChanged { focused = it.isFocused }
            .TunesLinkFocusBorder(
                focused = focused,
                color = TunesLinkTheme.colors.focusIndicator,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(role = Role.Button, onClickLabel = openNowPlaying, onClick = onOpenPlayer)
            .padding(horizontal = 16.dp, vertical = if (compact) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkSurface(
            player.artwork,
            playerArtworkDescription(player),
            Modifier.size(if (compact) 40.dp else 48.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                player.title.ifBlank { stringResource(R.string.nothing_playing) },
                style = MaterialTheme.typography.bodyLarge,
                color = TunesLinkTheme.colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                playerSubtitle(player, includeAlbum = false, unavailableHint = true),
                style = MaterialTheme.typography.bodyMedium,
                color = TunesLinkTheme.colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onPlayPause,
            enabled = controlsEnabled,
            modifier = Modifier.size(48.dp).semantics {
                contentDescription = playPauseDescription
            },
        ) {
            AnimatedContent(
                targetState = player.playing,
                transitionSpec = {
                    fadeIn(tween(TunesLinkMotion.SmallFeedback, easing = TunesLinkMotion.EaseOut)) togetherWith
                        fadeOut(tween(TunesLinkMotion.PressDown, easing = TunesLinkMotion.EaseOut))
                },
                label = "Player bar play pause",
            ) { playing ->
                Icon(
                    if (playing) TuneLinkIcons.Pause else TuneLinkIcons.PlayArrow,
                    contentDescription = null,
                    // An explicit tint overrides IconButton's disabled colour; dim it ourselves.
                    tint = TunesLinkTheme.colors.primaryText.copy(
                        alpha = if (controlsEnabled) 1f else DISABLED_CONTENT_ALPHA,
                    ),
                )
            }
        }
    }
}

@Composable
private fun DestinationActions(
    destination: TunesLinkDestination,
    onLibrary: () -> Unit,
    onPlayer: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier,
) {
    val fontScale = LocalDensity.current.fontScale
    val nowPlayingLabel = stringResource(R.string.now_playing)
    val visibleNowPlayingLabel = if (usesCompactPlayerNavigationLabel(fontScale)) {
        stringResource(R.string.player)
    } else {
        nowPlayingLabel
    }
    NavigationBar(
        modifier = modifier.selectableGroup(),
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        DestinationAction(
            TuneLinkIcons.LibraryMusic,
            stringResource(R.string.library),
            destination == TunesLinkDestination.Library,
            onLibrary,
            Modifier.weight(1f),
        )
        DestinationAction(
            TuneLinkIcons.Album,
            visibleNowPlayingLabel,
            destination == TunesLinkDestination.NowPlaying,
            onPlayer,
            Modifier.weight(1f),
            accessibilityLabel = nowPlayingLabel,
        )
        DestinationAction(
            TuneLinkIcons.Search,
            stringResource(R.string.search),
            destination == TunesLinkDestination.Search,
            onSearch,
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun RowScope.DestinationAction(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    accessibilityLabel: String = label,
) {
    var focused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val iconScale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.93f
            selected -> 1.04f
            else -> 1f
        },
        animationSpec = if (TunesLinkTheme.motion.spatialEnabled) {
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium,
            )
        } else {
            tween(TunesLinkMotion.ReducedMotionFade, easing = TunesLinkMotion.EaseOut)
        },
        label = "Destination icon scale",
    )
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp).scale(iconScale),
            )
        },
        label = {
            Text(
                label,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        alwaysShowLabel = true,
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = TunesLinkTheme.colors.accentText,
            selectedTextColor = TunesLinkTheme.colors.accentText,
            indicatorColor = TunesLinkTheme.colors.accentText.copy(alpha = 0.12f),
            unselectedIconColor = TunesLinkTheme.colors.secondaryText,
            unselectedTextColor = TunesLinkTheme.colors.secondaryText,
            disabledIconColor = TunesLinkTheme.colors.secondaryText.copy(alpha = 0.38f),
            disabledTextColor = TunesLinkTheme.colors.secondaryText.copy(alpha = 0.38f),
        ),
        interactionSource = interactionSource,
        modifier = modifier
            .heightIn(min = TunesLinkSizes.minimumTarget)
            .onFocusChanged { focused = it.isFocused }
            .TunesLinkFocusBorder(
                focused = focused,
                color = TunesLinkTheme.colors.focusIndicator,
                shape = RoundedCornerShape(16.dp),
            )
            .semantics {
                this.selected = selected
                contentDescription = accessibilityLabel
            },
    )
}

@Composable
internal fun TunesLinkDestinationRail(
    destination: TunesLinkDestination,
    onLibrary: () -> Unit,
    onPlayer: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
) {
    val showLabels = navigationRailShowsLabels(LocalDensity.current.fontScale)
    NavigationRail(
        modifier = modifier.selectableGroup(),
        containerColor = TunesLinkTheme.colors.surface.copy(alpha = 0.91f),
        // The rail's surface runs under the status bar; its items start below it.
        windowInsets = WindowInsets(top = topInset),
    ) {
        RailDestinationAction(
            icon = TuneLinkIcons.LibraryMusic,
            label = stringResource(R.string.library),
            selected = destination == TunesLinkDestination.Library,
            onClick = onLibrary,
            showLabel = showLabels,
        )
        RailDestinationAction(
            icon = TuneLinkIcons.Album,
            label = stringResource(R.string.now_playing),
            selected = destination == TunesLinkDestination.NowPlaying,
            onClick = onPlayer,
            showLabel = showLabels,
        )
        RailDestinationAction(
            icon = TuneLinkIcons.Search,
            label = stringResource(R.string.search),
            selected = destination == TunesLinkDestination.Search,
            onClick = onSearch,
            showLabel = showLabels,
        )
    }
}

@Composable
private fun RailDestinationAction(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    showLabel: Boolean,
) {
    var focused by remember { mutableStateOf(false) }
    NavigationRailItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        label = if (showLabel) {
            {
                Text(
                    label,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            null
        },
        alwaysShowLabel = showLabel,
        // Match the bottom bar's accent treatment rather than stock Material purple.
        colors = NavigationRailItemDefaults.colors(
            selectedIconColor = TunesLinkTheme.colors.accentText,
            selectedTextColor = TunesLinkTheme.colors.accentText,
            indicatorColor = TunesLinkTheme.colors.accentText.copy(alpha = 0.12f),
            unselectedIconColor = TunesLinkTheme.colors.secondaryText,
            unselectedTextColor = TunesLinkTheme.colors.secondaryText,
            disabledIconColor = TunesLinkTheme.colors.secondaryText.copy(alpha = DISABLED_CONTENT_ALPHA),
            disabledTextColor = TunesLinkTheme.colors.secondaryText.copy(alpha = DISABLED_CONTENT_ALPHA),
        ),
        modifier = Modifier
            .heightIn(min = TunesLinkSizes.minimumTarget)
            .onFocusChanged { focused = it.isFocused }
            .TunesLinkFocusBorder(
                focused = focused,
                color = TunesLinkTheme.colors.focusIndicator,
                shape = RoundedCornerShape(16.dp),
            )
            .semantics {
                this.selected = selected
                contentDescription = label
            },
    )
}

internal fun navigationRailShowsLabels(fontScale: Float): Boolean = fontScale < 1.3f

internal fun usesCompactPlayerNavigationLabel(fontScale: Float): Boolean = fontScale >= 1.3f

/**
 * Marks the current song in a list: three bars that move while playing (only when system
 * animations are on) and rest when paused. A Pause glyph here would read as a button.
 * The animation is read in the draw phase, so it never recomposes the row.
 */
@Composable
internal fun NowPlayingIndicator(
    playing: Boolean,
    description: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val labelled = modifier.semantics { contentDescription = description }
    if (playing && TunesLinkTheme.motion.spatialEnabled) {
        val transition = rememberInfiniteTransition(label = "Now playing bars")
        val phase = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1_100, easing = LinearEasing)),
            label = "Now playing bar phase",
        )
        Canvas(labelled) {
            drawNowPlayingBars(tint) { index ->
                0.3f + 0.7f * abs(sin((phase.value + index * 0.31f) * PI)).toFloat()
            }
        }
    } else {
        Canvas(labelled) { drawNowPlayingBars(tint) { index -> RestingBarHeights[index] } }
    }
}

private val RestingBarHeights = floatArrayOf(0.55f, 0.9f, 0.7f)

private fun DrawScope.drawNowPlayingBars(color: Color, height: (Int) -> Float) {
    // Three bars and two equal gaps span the width.
    val barWidth = size.width / 5f
    repeat(3) { index ->
        val barHeight = size.height * height(index).coerceIn(0.2f, 1f)
        drawRoundRect(
            color = color,
            topLeft = Offset(index * barWidth * 2f, size.height - barHeight),
            size = Size(barWidth, barHeight),
            cornerRadius = CornerRadius(barWidth / 2f),
        )
    }
}

@Composable
internal fun ContentState(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = TunesLinkTheme.colors.accentText,
                strokeWidth = 2.dp,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.height(18.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = TunesLinkTheme.colors.primaryText,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = TunesLinkTheme.colors.secondaryText,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            TunesLinkTonalAction(stringResource(R.string.try_again), onRetry)
        }
    }
}

@Composable
internal fun TunesLinkScaffold(
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable BoxScope.(PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        // System bars plus the display cutout: in landscape the camera cutout would otherwise sit
        // over the rail, workspace header and list leading edge. The keyboard is handled by the
        // screens that scroll (imePadding) because edge-to-edge disables adjustResize.
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
        containerColor = TunesLinkTheme.colors.canvas,
        contentColor = TunesLinkTheme.colors.primaryText,
        bottomBar = bottomBar,
    ) { padding ->
        Box(Modifier.fillMaxSize().background(TunesLinkTheme.colors.canvas)) {
            content(padding)
        }
    }
}
