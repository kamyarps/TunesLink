package com.kamyarps.tuneslink

import android.content.ClipData
import android.graphics.Bitmap
import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val LocalTunesLinkSharedTransitionScope =
    staticCompositionLocalOf<SharedTransitionScope?> { null }

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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun TunesLinkSharedTransitionRoot(content: @Composable () -> Unit) {
    SharedTransitionLayout {
        CompositionLocalProvider(LocalTunesLinkSharedTransitionScope provides this) {
            content()
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.tunesLinkPlayerSharedElement(
    key: String,
    visibilityScope: AnimatedVisibilityScope,
): Modifier {
    val sharedScope = LocalTunesLinkSharedTransitionScope.current
    if (sharedScope == null || !TunesLinkTheme.motion.spatialEnabled) return this
    val state = with(sharedScope) { rememberSharedContentState(key) }
    return with(sharedScope) {
        this@tunesLinkPlayerSharedElement.sharedElement(
            sharedContentState = state,
            animatedVisibilityScope = visibilityScope,
            boundsTransform = { _, _ ->
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    // This critically damped response settles at the 280 ms player contract.
                    stiffness = TunesLinkMotion.PlayerSharedStiffness,
                )
            },
        )
    }
}

@Composable
internal fun TunesLinkPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val motion = TunesLinkTheme.motion
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
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
        enabled = enabled,
        interactionSource = source,
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 15.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = TunesLinkTheme.colors.onBrandText,
            disabledContainerColor = TunesLinkTheme.colors.raisedSurface,
            disabledContentColor = TunesLinkTheme.colors.secondaryText,
        ),
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .TunesLinkFocusBorder(
                focused = focused,
                color = TunesLinkTheme.colors.focusIndicator,
                shape = RoundedCornerShape(16.dp),
            )
            .scale(scale)
            .background(
                brush = if (enabled) {
                    Brush.linearGradient(listOf(TunesLinkTheme.colors.brandStart, TunesLinkTheme.colors.brandEnd))
                } else {
                    Brush.linearGradient(listOf(TunesLinkTheme.colors.raisedSurface, TunesLinkTheme.colors.raisedSurface))
                },
                shape = RoundedCornerShape(16.dp),
            )
            .heightIn(min = 52.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
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
    val contentColor = color.copy(alpha = if (enabled) 1f else 0.38f)
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
internal fun ArtworkSurface(
    bitmap: Bitmap?,
    description: String?,
    modifier: Modifier = Modifier,
) {
    val duration = if (TunesLinkTheme.motion.spatialEnabled) {
        TunesLinkMotion.ArtworkCrossfade
    } else {
        TunesLinkMotion.ReducedMotionFade
    }
    Crossfade(
        targetState = bitmap,
        animationSpec = tween(duration, easing = TunesLinkMotion.EaseInOut),
        label = "Artwork transition",
        modifier = modifier
            .clip(RectangleShape)
            .background(TunesLinkTheme.colors.raisedSurface),
    ) { art ->
        if (art == null) {
            Image(
                painter = painterResource(R.drawable.default_artwork),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
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
            Icons.Rounded.SkipPrevious,
            previousDescription,
            onPrevious,
            large = false,
            compact = compact,
            enabled = enabled && !player.hasPendingConflict(PlaybackAction.Previous),
        )
        TransportButton(
            if (player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            if (player.playing) pauseDescription else playDescription,
            onPlayPause,
            large = true,
            compact = compact,
            enabled = enabled && !player.hasPendingConflict(PlaybackAction.PlayPause),
        )
        TransportButton(
            Icons.Rounded.SkipNext,
            nextDescription,
            onNext,
            large = false,
            compact = compact,
            enabled = enabled && !player.hasPendingConflict(PlaybackAction.Next),
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
) {
    val compactLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val motion = TunesLinkTheme.motion
    val miniEnter = if (motion.spatialEnabled) {
        EnterTransition.None
    } else {
        fadeIn(tween(TunesLinkMotion.ReducedMotionFade, easing = TunesLinkMotion.EaseOut))
    }
    val miniExit = if (motion.spatialEnabled) {
        ExitTransition.None
    } else {
        fadeOut(tween(TunesLinkMotion.ReducedMotionFade, easing = TunesLinkMotion.EaseOut))
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(TunesLinkTheme.colors.surface.copy(alpha = 0.91f))
            .navigationBarsPadding(),
    ) {
        if (compactLandscape && (showMiniPlayer || showNavigation)) {
            Row(
                Modifier.fillMaxWidth().height(80.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedVisibility(
                    visible = showMiniPlayer,
                    enter = miniEnter,
                    exit = miniExit,
                    modifier = Modifier.weight(1f),
                ) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        MiniPlayerContent(
                            player = player,
                            onOpenPlayer = onOpenPlayer,
                            onPlayPause = onPlayPause,
                            controlsEnabled = controlsEnabled,
                            compact = true,
                            modifier = Modifier
                                .widthIn(max = TunesLinkSizes.readableContentMaxWidth)
                                .fillMaxWidth(),
                            visibilityScope = this@AnimatedVisibility,
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
            AnimatedVisibility(
                visible = showMiniPlayer,
                enter = miniEnter,
                exit = miniExit,
            ) {
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
                        visibilityScope = this@AnimatedVisibility,
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
    visibilityScope: AnimatedVisibilityScope,
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
            if (player.artwork != null) stringResource(R.string.artwork_for, player.title) else null,
            Modifier
                .size(if (compact) 40.dp else 48.dp)
                .tunesLinkPlayerSharedElement("player-artwork", visibilityScope),
        )
        Spacer(Modifier.width(12.dp))
        Column(
            Modifier
                .weight(1f)
                .tunesLinkPlayerSharedElement("player-metadata", visibilityScope),
        ) {
            Text(
                player.title.ifBlank { stringResource(R.string.nothing_playing) },
                style = MaterialTheme.typography.bodyLarge,
                color = TunesLinkTheme.colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                player.artist.ifBlank { stringResource(R.string.open_itunes_on_computer) },
                style = MaterialTheme.typography.bodyMedium,
                color = TunesLinkTheme.colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onPlayPause,
            enabled = controlsEnabled && !player.hasPendingConflict(PlaybackAction.PlayPause),
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
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = TunesLinkTheme.colors.primaryText,
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
            Icons.Rounded.LibraryMusic,
            stringResource(R.string.library),
            destination == TunesLinkDestination.Library,
            onLibrary,
            Modifier.weight(1f),
        )
        DestinationAction(
            Icons.Rounded.Album,
            visibleNowPlayingLabel,
            destination == TunesLinkDestination.NowPlaying,
            onPlayer,
            Modifier.weight(1f),
            accessibilityLabel = nowPlayingLabel,
        )
        DestinationAction(
            Icons.Rounded.Search,
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
) {
    val showLabels = navigationRailShowsLabels(LocalDensity.current.fontScale)
    NavigationRail(
        modifier = modifier.selectableGroup(),
        containerColor = TunesLinkTheme.colors.surface.copy(alpha = 0.91f),
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        RailDestinationAction(
            icon = Icons.Rounded.LibraryMusic,
            label = stringResource(R.string.library),
            selected = destination == TunesLinkDestination.Library,
            onClick = onLibrary,
            showLabel = showLabels,
        )
        RailDestinationAction(
            icon = Icons.Rounded.Album,
            label = stringResource(R.string.now_playing),
            selected = destination == TunesLinkDestination.NowPlaying,
            onClick = onPlayer,
            showLabel = showLabels,
        )
        RailDestinationAction(
            icon = Icons.Rounded.Search,
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
            { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else {
            null
        },
        alwaysShowLabel = showLabel,
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
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = TunesLinkTheme.colors.secondaryText,
        )
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            TunesLinkTonalAction(stringResource(R.string.try_again), onRetry)
        }
    }
}

@Composable
internal fun CopyAction(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    var copied by remember(value) { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val copiedLabel = stringResource(R.string.copied)
    val feedbackDuration = if (TunesLinkTheme.motion.spatialEnabled) {
        TunesLinkMotion.SmallFeedback
    } else {
        TunesLinkMotion.ReducedMotionFade
    }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_200)
            copied = false
        }
    }
    Row(
        modifier = modifier
            .heightIn(min = TunesLinkSizes.minimumTarget)
            .onFocusChanged { focused = it.isFocused }
            .TunesLinkFocusBorder(
                focused = focused,
                color = TunesLinkTheme.colors.focusIndicator,
                shape = RoundedCornerShape(12.dp),
            )
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button) {
                coroutineScope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, value)))
                    copied = true
                }
            }
            .padding(12.dp)
            .semantics { if (copied) liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = copied,
            transitionSpec = {
                fadeIn(tween(feedbackDuration, easing = TunesLinkMotion.EaseOut)) togetherWith
                    fadeOut(tween(feedbackDuration, easing = TunesLinkMotion.EaseOut))
            },
            label = "Copy feedback",
        ) { success ->
            Icon(
                if (success) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                contentDescription = null,
                tint = if (success) TunesLinkTheme.colors.success else TunesLinkTheme.colors.secondaryText,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (copied) copiedLabel else label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (copied) TunesLinkTheme.colors.success else TunesLinkTheme.colors.secondaryText,
        )
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
        containerColor = TunesLinkTheme.colors.canvas,
        contentColor = TunesLinkTheme.colors.primaryText,
        bottomBar = bottomBar,
    ) { padding ->
        Box(Modifier.fillMaxSize().background(TunesLinkTheme.colors.canvas)) {
            content(padding)
        }
    }
}
