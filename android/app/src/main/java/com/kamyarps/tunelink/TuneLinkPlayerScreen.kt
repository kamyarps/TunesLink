package com.kamyarps.tuneslink

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.core.graphics.get
import kotlin.math.max

internal enum class NowPlayingLayoutMode { Vertical, CompactHorizontal, ExpandedHorizontal }

internal fun nowPlayingLayoutMode(
    widthDp: Float,
    heightDp: Float,
    fontScale: Float,
): NowPlayingLayoutMode = when {
    fontScale >= 1.5f -> NowPlayingLayoutMode.Vertical
    widthDp >= 720f && heightDp >= 480f -> NowPlayingLayoutMode.ExpandedHorizontal
    widthDp >= 600f && heightDp < 480f -> NowPlayingLayoutMode.CompactHorizontal
    else -> NowPlayingLayoutMode.Vertical
}

internal fun nowPlayingArtworkSizeDp(
    widthDp: Float,
    heightDp: Float,
    outerPaddingDp: Float,
    mode: NowPlayingLayoutMode,
    fontScale: Float,
): Float {
    val widthAvailable = max(1f, widthDp - outerPaddingDp * 2f)
    return when (mode) {
        NowPlayingLayoutMode.Vertical -> {
            val minimum = if (heightDp < 480f) 128f else 160f
            val heightFraction = when {
                fontScale >= 1.5f -> 0.34f
                widthDp >= 520f && heightDp >= 800f -> 0.52f
                heightDp < 560f -> 0.38f
                else -> 0.43f
            }
            minOf(widthAvailable, max(minimum, heightDp * heightFraction), 520f)
        }
        NowPlayingLayoutMode.CompactHorizontal ->
            minOf(widthDp * 0.34f, max(1f, heightDp - 24f), 320f)
        NowPlayingLayoutMode.ExpandedHorizontal ->
            minOf(widthDp * 0.42f, max(1f, heightDp - 64f), 520f)
    }
}


@Composable
internal fun NowPlayingScreen(
    state: TunesLinkUiState,
    viewModel: TunesLinkViewModel,
    modifier: Modifier,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope,
) {
    val player = state.player
    val haptic = LocalHapticFeedback.current
    val controlsEnabled = ConnectionAvailability.from(state.connection).controlsEnabled && player.iTunesAvailable
    val ambient = remember(player.artwork) { player.artwork?.let(::averageArtworkColor) }
    val animatedAmbient by animateColorAsState(
        targetValue = ambient ?: TunesLinkTheme.colors.canvas,
        animationSpec = androidx.compose.animation.core.tween(
            if (TunesLinkTheme.motion.spatialEnabled) TunesLinkMotion.AmbientColor else TunesLinkMotion.ModalExit,
            easing = TunesLinkMotion.EaseInOut,
        ),
        label = "Artwork ambient color",
    )
    val background = Brush.verticalGradient(
        listOf(animatedAmbient.copy(alpha = if (ambient == null) 0f else 0.14f), TunesLinkTheme.colors.canvas),
    )
    BoxWithConstraints(modifier.fillMaxSize().background(background)) {
        val fontScale = LocalDensity.current.fontScale
        val layoutMode = nowPlayingLayoutMode(maxWidth.value, maxHeight.value, fontScale)
        val compactHorizontal = layoutMode == NowPlayingLayoutMode.CompactHorizontal
        val horizontal = layoutMode != NowPlayingLayoutMode.Vertical
        val outerPadding = if (maxWidth >= 600.dp) 32.dp else 24.dp
        val artworkSize = nowPlayingArtworkSizeDp(
            maxWidth.value,
            maxHeight.value,
            outerPadding.value,
            layoutMode,
            fontScale,
        ).dp
        if (horizontal) {
            Row(
                Modifier.fillMaxSize().padding(
                    horizontal = if (compactHorizontal) 20.dp else outerPadding,
                    vertical = if (compactHorizontal) 12.dp else 24.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(if (compactHorizontal) 24.dp else 40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtworkSurface(
                    player.artwork,
                    if (player.artwork != null) stringResource(R.string.artwork_for, player.title) else null,
                    Modifier
                        .size(artworkSize)
                        .tunesLinkPlayerSharedElement("player-artwork", animatedVisibilityScope),
                )
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.now_playing),
                            style = if (compactHorizontal) {
                                MaterialTheme.typography.titleLarge
                            } else {
                                MaterialTheme.typography.headlineLarge
                            },
                            color = TunesLinkTheme.colors.primaryText,
                            modifier = Modifier.weight(1f).semantics { heading() },
                        )
                        ComputerConnectionAction(
                            state.bridgeName,
                            state.connection,
                            viewModel::showConnectionDetails,
                        )
                    }
                    PlayerDetails(
                        player,
                        viewModel,
                        haptic,
                        Modifier.weight(1f).fillMaxWidth(),
                        metadataModifier = Modifier.tunesLinkPlayerSharedElement(
                            "player-metadata",
                            animatedVisibilityScope,
                        ),
                        compactHeight = compactHorizontal,
                        centerVertically = true,
                        controlsEnabled = controlsEnabled,
                    )
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(start = outerPadding, top = 8.dp, end = outerPadding),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.now_playing),
                        style = MaterialTheme.typography.titleLarge,
                        color = TunesLinkTheme.colors.primaryText,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    ComputerConnectionAction(
                        state.bridgeName,
                        state.connection,
                        viewModel::showConnectionDetails,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ArtworkSurface(
                        player.artwork,
                        if (player.artwork != null) stringResource(R.string.artwork_for, player.title) else null,
                        Modifier
                            .size(artworkSize)
                            .tunesLinkPlayerSharedElement("player-artwork", animatedVisibilityScope),
                    )
                    PlayerDetails(
                        player,
                        viewModel,
                        haptic,
                        Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                        metadataModifier = Modifier.tunesLinkPlayerSharedElement(
                            "player-metadata",
                            animatedVisibilityScope,
                        ),
                        centerVertically = false,
                        controlsEnabled = controlsEnabled,
                    )
                }
            }
        }
    }
}

private fun averageArtworkColor(bitmap: Bitmap): Color {
    var red = 0L
    var green = 0L
    var blue = 0L
    var samples = 0
    val xStep = max(1, bitmap.width / 6)
    val yStep = max(1, bitmap.height / 6)
    var y = yStep / 2
    while (y < bitmap.height) {
        var x = xStep / 2
        while (x < bitmap.width) {
            val pixel = bitmap[x, y]
            red += android.graphics.Color.red(pixel)
            green += android.graphics.Color.green(pixel)
            blue += android.graphics.Color.blue(pixel)
            samples++
            x += xStep
        }
        y += yStep
    }
    if (samples == 0) return Color.Transparent
    return Color(
        red = (red / samples) / 255f,
        green = (green / samples) / 255f,
        blue = (blue / samples) / 255f,
    )
}

@Composable
private fun PlayerDetails(
    player: PlayerUiState,
    viewModel: TunesLinkViewModel,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    modifier: Modifier,
    metadataModifier: Modifier = Modifier,
    compactHeight: Boolean = false,
    centerVertically: Boolean = false,
    controlsEnabled: Boolean = true,
) {
    var seekValue by remember(player.trackId, player.duration) {
        mutableFloatStateOf(player.position.toFloat())
    }
    var volumeValue by remember { mutableFloatStateOf(player.volume.toFloat()) }
    LaunchedEffect(player.position, player.pending(PlaybackAction.Position)) {
        if (player.pending(PlaybackAction.Position) == null) seekValue = player.position.toFloat()
    }
    LaunchedEffect(player.volume, player.pending(PlaybackAction.Volume)) {
        if (player.pending(PlaybackAction.Volume) == null) volumeValue = player.volume.toFloat()
    }
    val seekDescription = stringResource(
        R.string.track_position_accessibility,
        formatTime(seekValue.toDouble()),
        formatTime(player.duration),
    )
    val volumeDescription = stringResource(
        R.string.itunes_volume_accessibility,
        volumeValue.toInt(),
    )
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (centerVertically) Arrangement.Center else Arrangement.Top,
    ) {
        if (!compactHeight) Spacer(Modifier.height(18.dp))
        Column(metadataModifier.fillMaxWidth()) {
            Text(
                player.title.ifBlank { stringResource(R.string.nothing_playing) },
                style = MaterialTheme.typography.titleLarge,
                color = TunesLinkTheme.colors.primaryText,
                maxLines = if (compactHeight) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(
                    player.artist.ifBlank { stringResource(R.string.open_itunes_on_computer) },
                    player.album,
                ).filter(String::isNotBlank).joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge,
                color = TunesLinkTheme.colors.secondaryText,
                maxLines = if (compactHeight) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(if (compactHeight) 2.dp else 12.dp))
        TunesLinkSlider(
            value = seekValue.coerceIn(0f, max(1f, player.duration.toFloat())),
            onValueChange = { seekValue = it },
            onValueChangeFinished = {
                viewModel.seek(seekValue.toDouble())
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            },
            valueRange = 0f..max(1f, player.duration.toFloat()),
            enabled = controlsEnabled && player.duration > 0 &&
                !player.hasPendingConflict(PlaybackAction.Position),
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().semantics {
                contentDescription = seekDescription
            },
        )
        Row(Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
            Text(formatTime(seekValue.toDouble()), style = MaterialTheme.typography.labelMedium, color = TunesLinkTheme.colors.secondaryText)
            Spacer(Modifier.weight(1f))
            Text("−${formatTime(max(0.0, player.duration - seekValue.toDouble()))}", style = MaterialTheme.typography.labelMedium, color = TunesLinkTheme.colors.secondaryText)
        }
        if (!compactHeight) Spacer(Modifier.height(8.dp))
        Row(
            Modifier.widthIn(max = 520.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlaybackModeButton(
                icon = Icons.Rounded.Shuffle,
                description = stringResource(
                    if (player.shuffleEnabled) R.string.turn_shuffle_off else R.string.turn_shuffle_on,
                ),
                selected = player.shuffleEnabled,
                enabled = controlsEnabled && !player.hasPendingConflict(PlaybackAction.Shuffle),
                onClick = viewModel::toggleShuffle,
            )
            TransportCluster(
                player,
                onPrevious = viewModel::previous,
                onPlayPause = viewModel::togglePlayback,
                onNext = viewModel::next,
                modifier = Modifier.weight(1f),
                compact = compactHeight,
                enabled = controlsEnabled,
            )
            PlaybackModeButton(
                icon = if (player.repeatMode == RepeatMode.One) {
                    Icons.Rounded.RepeatOne
                } else {
                    Icons.Rounded.Repeat
                },
                description = when (player.repeatMode) {
                    RepeatMode.Off -> stringResource(R.string.turn_repeat_all_on)
                    RepeatMode.All -> stringResource(R.string.turn_repeat_one_on)
                    RepeatMode.One -> stringResource(R.string.turn_repeat_off)
                },
                selected = player.repeatMode != RepeatMode.Off,
                enabled = controlsEnabled && !player.hasPendingConflict(PlaybackAction.Repeat),
                onClick = viewModel::cycleRepeat,
            )
        }
        Spacer(Modifier.height(if (compactHeight) 2.dp else 16.dp))
        Row(
            Modifier.widthIn(max = 520.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Rounded.VolumeDown, null, tint = TunesLinkTheme.colors.secondaryText, modifier = Modifier.size(18.dp))
            TunesLinkSlider(
                value = volumeValue,
                onValueChange = { volumeValue = it },
                onValueChangeFinished = {
                    viewModel.setVolume(volumeValue.toInt())
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                },
                valueRange = 0f..100f,
                lowEmphasis = true,
                enabled = controlsEnabled && !player.hasPendingConflict(PlaybackAction.Volume),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics {
                    contentDescription = volumeDescription
                },
            )
            Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = TunesLinkTheme.colors.secondaryText, modifier = Modifier.size(18.dp))
        }
        if (!player.iTunesAvailable) {
            Text(
                stringResource(R.string.open_itunes),
                style = MaterialTheme.typography.bodyMedium,
                color = TunesLinkTheme.colors.danger,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        if (player.commandError != null) {
            Text(
                player.commandError,
                style = MaterialTheme.typography.bodyMedium,
                color = TunesLinkTheme.colors.danger,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
    }
}

@Composable
private fun PlaybackModeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(TunesLinkSizes.minimumTarget).semantics {
            contentDescription = description
        },
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) TunesLinkTheme.colors.accentText else TunesLinkTheme.colors.secondaryText,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun TunesLinkSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)?,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    lowEmphasis: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = tunesLinkSliderColors(lowEmphasis)
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        interactionSource = interactionSource,
        valueRange = valueRange,
        thumb = {
            SliderDefaults.Thumb(
                interactionSource = interactionSource,
                colors = colors,
                enabled = enabled,
                thumbSize = DpSize(if (lowEmphasis) 14.dp else 16.dp, if (lowEmphasis) 14.dp else 16.dp),
            )
        },
        track = { sliderState ->
            SliderDefaults.Track(
                sliderState = sliderState,
                modifier = Modifier.height(if (lowEmphasis) 3.dp else 4.dp),
                colors = colors,
                enabled = enabled,
                drawStopIndicator = null,
                thumbTrackGapSize = 0.dp,
                trackInsideCornerSize = 0.dp,
            )
        },
    )
}

internal fun formatTime(seconds: Double): String {
    val safe = max(0, seconds.toInt())
    return "${safe / 60}:${(safe % 60).toString().padStart(2, '0')}"
}
