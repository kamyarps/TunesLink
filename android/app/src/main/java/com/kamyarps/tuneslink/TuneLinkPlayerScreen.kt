package com.kamyarps.tuneslink

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.get
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
    topInset: Dp = 0.dp,
) {
    val player = state.player
    val haptic = LocalHapticFeedback.current
    val controlsEnabled = state.playbackControlsEnabled
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
    val artworkDescription = playerArtworkDescription(player)
    // The ambient wash is drawn full-bleed (under the status bar); content starts below it.
    BoxWithConstraints(modifier.fillMaxSize().background(background).padding(top = topInset)) {
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
                    artworkDescription,
                    Modifier.size(artworkSize),
                    cornerRadius = TunesLinkShapes.artworkLarge,
                    elevated = true,
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
                        artworkDescription,
                        Modifier
                            .padding(top = 4.dp)
                            .size(artworkSize),
                        cornerRadius = TunesLinkShapes.artworkLarge,
                        elevated = true,
                    )
                    PlayerDetails(
                        player,
                        viewModel,
                        haptic,
                        Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                        centerVertically = false,
                        controlsEnabled = controlsEnabled,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerDetails(
    player: PlayerUiState,
    viewModel: TunesLinkViewModel,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    modifier: Modifier,
    compactHeight: Boolean = false,
    centerVertically: Boolean = false,
    controlsEnabled: Boolean = true,
) {
    var volumeValue by remember { mutableFloatStateOf(player.volume.toFloat()) }
    var adjustingVolume by remember { mutableStateOf(false) }
    LaunchedEffect(player.volume, player.pending(PlaybackAction.Volume), adjustingVolume) {
        if (!adjustingVolume && player.pending(PlaybackAction.Volume) == null) {
            volumeValue = player.volume.toFloat()
        }
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (centerVertically) Arrangement.Center else Arrangement.Top,
    ) {
        if (!compactHeight) Spacer(Modifier.height(18.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(
                player.title.ifBlank { stringResource(R.string.nothing_playing) },
                style = MaterialTheme.typography.titleLarge,
                color = TunesLinkTheme.colors.primaryText,
                maxLines = if (compactHeight) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                playerSubtitle(player, includeAlbum = true, unavailableHint = false),
                style = MaterialTheme.typography.bodyLarge,
                color = TunesLinkTheme.colors.secondaryText,
                maxLines = if (compactHeight) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(if (compactHeight) 2.dp else 12.dp))
        PlaybackProgress(
            viewModel = viewModel,
            trackId = player.trackId,
            duration = player.duration,
            enabled = controlsEnabled && player.duration > 0,
            onSeek = { position, draggedTrackId ->
                viewModel.seek(position, draggedTrackId)
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            },
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
        )
        if (!compactHeight) Spacer(Modifier.height(8.dp))
        Row(
            Modifier.widthIn(max = 520.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShuffleToggle(
                player = player,
                enabled = controlsEnabled,
                size = TunesLinkSizes.minimumTarget,
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
            RepeatToggle(
                player = player,
                enabled = controlsEnabled,
                size = TunesLinkSizes.minimumTarget,
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
                onValueChange = {
                    adjustingVolume = true
                    volumeValue = it
                },
                onValueChangeFinished = {
                    adjustingVolume = false
                    viewModel.setVolume(volumeValue.toInt())
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                },
                valueRange = 0f..100f,
                lowEmphasis = true,
                enabled = controlsEnabled,
                semanticsLabel = stringResource(R.string.itunes_volume),
                semanticsState = stringResource(R.string.volume_state, volumeValue.toInt()),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = TunesLinkTheme.colors.secondaryText, modifier = Modifier.size(18.dp))
        }
        if (!player.iTunesAvailable) {
            Text(
                stringResource(R.string.open_itunes),
                style = MaterialTheme.typography.bodyMedium,
                color = TunesLinkTheme.colors.danger,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * The scrubber and its elapsed/remaining labels. It is the only UI that reads the interpolated
 * playback clock, so its 250 ms ticks recompose just these controls.
 *
 * @param inline true for the workspace strip: `0:42 ───●─── −2:36` on one row.
 */
@Composable
internal fun PlaybackProgress(
    viewModel: TunesLinkViewModel,
    trackId: String,
    duration: Double,
    enabled: Boolean,
    onSeek: (position: Double, trackId: String) -> Unit,
    modifier: Modifier = Modifier,
    inline: Boolean = false,
) {
    val position = rememberPlaybackPosition(viewModel)
    var seeking by remember(trackId) { mutableStateOf(false) }
    var seekValue by remember(trackId) { mutableFloatStateOf(0f) }
    // The song the finger started on. A drag that outlives its song must not seek the next one.
    // Unlike `seeking`, these survive a track change while the finger is still down.
    var dragging by remember { mutableStateOf(false) }
    var dragTrackId by remember { mutableStateOf(trackId) }
    val safeDuration = max(1f, duration.toFloat())
    val shown = if (seeking) {
        seekValue.toDouble()
    } else {
        position.value.coerceIn(0.0, max(0.0, duration))
    }
    val elapsedText = formatTime(shown)
    val remainingText = stringResource(R.string.remaining_time, formatTime(max(0.0, duration - shown)))
    val label = stringResource(R.string.song_position)
    val stateText = stringResource(R.string.song_position_state, elapsedText, formatTime(duration))
    val timeStyle = MaterialTheme.typography.labelMedium.tabularNumerals()
    val timeColor = TunesLinkTheme.colors.secondaryText
    val slider: @Composable (Modifier) -> Unit = { sliderModifier ->
        TunesLinkSlider(
            value = shown.toFloat().coerceIn(0f, safeDuration),
            onValueChange = {
                if (!dragging) dragTrackId = trackId
                dragging = true
                seeking = true
                seekValue = it
            },
            onValueChangeFinished = {
                dragging = false
                seeking = false
                // Hold the thumb where it was released until the bridge confirms the seek.
                if (dragTrackId == trackId) position.hold(seekValue.toDouble())
                onSeek(seekValue.toDouble(), dragTrackId)
            },
            valueRange = 0f..safeDuration,
            enabled = enabled,
            lowEmphasis = inline,
            semanticsLabel = label,
            semanticsState = stateText,
            modifier = sliderModifier,
        )
    }
    // The slider already speaks "1:23 of 3:18"; the visible labels would only repeat it.
    if (inline) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Text(elapsedText, style = timeStyle, color = timeColor, maxLines = 1,
                modifier = Modifier.clearAndSetSemantics {})
            slider(Modifier.weight(1f).height(24.dp).padding(horizontal = 8.dp))
            Text(remainingText, style = timeStyle, color = timeColor, maxLines = 1,
                modifier = Modifier.clearAndSetSemantics {})
        }
    } else {
        Column(modifier) {
            slider(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth()) {
                Text(elapsedText, style = timeStyle, color = timeColor,
                    modifier = Modifier.clearAndSetSemantics {})
                Spacer(Modifier.weight(1f))
                Text(remainingText, style = timeStyle, color = timeColor,
                    modifier = Modifier.clearAndSetSemantics {})
            }
        }
    }
}

@Composable
internal fun ShuffleToggle(
    player: PlayerUiState,
    enabled: Boolean,
    size: Dp,
    onClick: () -> Unit,
) {
    PlaybackModeToggle(
        icon = Icons.Rounded.Shuffle,
        description = stringResource(
            if (player.shuffleEnabled) R.string.turn_shuffle_off else R.string.turn_shuffle_on,
        ),
        stateDescription = stringResource(if (player.shuffleEnabled) R.string.playback_mode_on else R.string.playback_mode_off),
        selected = player.shuffleEnabled,
        enabled = enabled,
        size = size,
        onClick = onClick,
    )
}

@Composable
internal fun RepeatToggle(
    player: PlayerUiState,
    enabled: Boolean,
    size: Dp,
    onClick: () -> Unit,
) {
    PlaybackModeToggle(
        icon = if (player.repeatMode == RepeatMode.One) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
        description = when (player.repeatMode) {
            RepeatMode.Off -> stringResource(R.string.turn_repeat_all_on)
            RepeatMode.All -> stringResource(R.string.turn_repeat_one_on)
            RepeatMode.One -> stringResource(R.string.turn_repeat_off)
        },
        stateDescription = when (player.repeatMode) {
            RepeatMode.Off -> stringResource(R.string.playback_mode_off)
            RepeatMode.All -> stringResource(R.string.repeat_state_all)
            RepeatMode.One -> stringResource(R.string.repeat_state_one)
        },
        selected = player.repeatMode != RepeatMode.Off,
        enabled = enabled,
        size = size,
        onClick = onClick,
    )
}

/**
 * Shuffle/repeat: an active mode gets an accent-tinted rounded fill, not only an accent glyph,
 * so the state does not depend on distinguishing two similar colours.
 */
@Composable
private fun PlaybackModeToggle(
    icon: ImageVector,
    description: String,
    stateDescription: String,
    selected: Boolean,
    enabled: Boolean,
    size: Dp,
    onClick: () -> Unit,
) {
    val emphasis = if (enabled) 1f else DISABLED_CONTENT_ALPHA
    val indicator by animateColorAsState(
        targetValue = if (selected) {
            TunesLinkTheme.colors.accentText.copy(alpha = ACTIVE_INDICATOR_ALPHA * emphasis)
        } else {
            Color.Transparent
        },
        animationSpec = androidx.compose.animation.core.tween(
            TunesLinkMotion.SmallFeedback,
            easing = TunesLinkMotion.EaseOut,
        ),
        label = "Playback mode indicator",
    )
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(TunesLinkShapes.control))
            .background(indicator)
            .semantics {
                contentDescription = description
                this.stateDescription = stateDescription
                this.selected = selected
            },
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = (if (selected) TunesLinkTheme.colors.accentText else TunesLinkTheme.colors.secondaryText)
                .copy(alpha = emphasis),
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
    semanticsLabel: String? = null,
    semanticsState: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = tunesLinkSliderColors(lowEmphasis)
    // Merge the label and value into the slider's own node so TalkBack focuses one control that
    // reads "Song position, 1:23 of 3:18" and still offers its adjust actions.
    Box(
        modifier.semantics(mergeDescendants = true) {
            semanticsLabel?.let { contentDescription = it }
            semanticsState?.let { stateDescription = it }
        },
        propagateMinConstraints = true,
    ) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
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
}

internal fun formatTime(seconds: Double): String {
    val safe = max(0, seconds.toInt())
    val minutes = safe / 60
    val remainder = (safe % 60).toString().padStart(2, '0')
    if (minutes < 60) return "$minutes:$remainder"
    return "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:$remainder"
}

/** "3 minutes 18 seconds" for a row duration, instead of TalkBack reading "3:18" as a ratio. */
@Composable
internal fun spokenDuration(seconds: Double): String {
    val safe = max(0, seconds.toInt())
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val secs = safe % 60
    val secondsText = pluralStringResource(R.plurals.duration_seconds, secs, secs)
    val minutesText = pluralStringResource(R.plurals.duration_minutes, minutes, minutes)
    return when {
        hours > 0 -> stringResource(
            R.string.duration_three_parts,
            pluralStringResource(R.plurals.duration_hours, hours, hours),
            minutesText,
            secondsText,
        )
        minutes > 0 -> stringResource(R.string.duration_two_parts, minutesText, secondsText)
        else -> secondsText
    }
}

/** What the playback clock needs from a state update; the root ignores these ticks. */
internal data class PlaybackPositionSample(
    val trackId: String,
    val position: Double,
    val duration: Double,
    val playing: Boolean,
    /** A seek is waiting for confirmation: show its target and do not advance. */
    val held: Boolean,
    /** The bridge is streaming state; without it, extrapolating would drift unchecked. */
    val live: Boolean,
)

internal fun TunesLinkUiState.playbackSample(): PlaybackPositionSample = PlaybackPositionSample(
    trackId = player.trackId,
    position = player.position,
    duration = player.duration,
    playing = player.playing,
    held = player.pending(PlaybackAction.Position) != null,
    live = connection is ConnectionState.Connected && player.iTunesAvailable,
)

/** The root collects this: identical to the state except the ~750 ms playback position. */
internal fun TunesLinkUiState.withoutPlaybackClock(): TunesLinkUiState =
    if (player.position == 0.0) this else copy(player = player.copy(position = 0.0))

internal const val PLAYBACK_TICK_MS = 250L
internal const val PLAYBACK_SNAP_SECONDS = 1.0

/**
 * Interpolates the bridge's coarse position samples. While playing it advances locally from the
 * last anchor; a new sample only moves the anchor when it disagrees by more than
 * [PLAYBACK_SNAP_SECONDS] (or the track, pause state or a pending seek changes), so the clock
 * never stutters backwards by a sampling interval.
 */
internal class PlaybackClock {
    private var initialized = false
    private var trackId = ""
    private var anchorPosition = 0.0
    private var anchorAtMillis = 0L
    private var duration = 0.0
    var ticking = false
        private set

    fun accept(sample: PlaybackPositionSample, nowMillis: Long) {
        val predicted = positionAt(nowMillis)
        val continuous = initialized && trackId == sample.trackId && !sample.held &&
            abs(predicted - sample.position) <= PLAYBACK_SNAP_SECONDS
        anchorPosition = if (continuous) predicted else sample.position
        anchorAtMillis = nowMillis
        trackId = sample.trackId
        duration = sample.duration
        ticking = sample.playing && sample.live && !sample.held
        initialized = true
    }

    /** Freeze at a released scrub position until the bridge reports the seek. */
    fun hold(position: Double, nowMillis: Long) {
        anchorPosition = position
        anchorAtMillis = nowMillis
        ticking = false
    }

    fun positionAt(nowMillis: Long): Double {
        if (!ticking) return anchorPosition
        val advanced = anchorPosition + (nowMillis - anchorAtMillis).coerceAtLeast(0L) / 1000.0
        return if (duration > 0) min(advanced, duration) else advanced
    }
}

@Stable
internal class PlaybackPositionState {
    private val clock = PlaybackClock()
    private val displayed = mutableDoubleStateOf(0.0)
    val value: Double get() = displayed.doubleValue
    internal val ticking: Boolean get() = clock.ticking

    internal fun accept(sample: PlaybackPositionSample, nowMillis: Long) {
        clock.accept(sample, nowMillis)
        displayed.doubleValue = clock.positionAt(nowMillis)
    }

    internal fun refresh(nowMillis: Long) {
        displayed.doubleValue = clock.positionAt(nowMillis)
    }

    fun hold(position: Double) {
        clock.hold(position, SystemClock.elapsedRealtime())
        displayed.doubleValue = position
    }
}

@Composable
internal fun rememberPlaybackPosition(viewModel: TunesLinkViewModel): PlaybackPositionState {
    val samples = remember(viewModel) {
        viewModel.state.map(TunesLinkUiState::playbackSample).distinctUntilChanged()
    }
    val initial = remember(viewModel) { viewModel.state.value.playbackSample() }
    val sample by samples.collectAsStateWithLifecycle(initial)
    val positionState = remember(viewModel) {
        PlaybackPositionState().also { it.accept(initial, SystemClock.elapsedRealtime()) }
    }
    LaunchedEffect(positionState, sample) {
        positionState.accept(sample, SystemClock.elapsedRealtime())
        while (positionState.ticking) {
            delay(PLAYBACK_TICK_MS)
            // Align with a frame; the frame clock also pauses this loop while the app is hidden.
            withFrameMillis { }
            positionState.refresh(SystemClock.elapsedRealtime())
        }
    }
    return positionState
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
