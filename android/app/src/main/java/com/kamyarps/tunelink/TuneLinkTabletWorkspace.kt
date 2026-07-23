package com.kamyarps.tuneslink

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.max
import kotlin.math.min

@Composable
internal fun TabletTunesWorkspace(
    state: TunesLinkUiState,
    destination: TunesLinkDestination,
    viewModel: TunesLinkViewModel,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(destination, state.browse.kind) {
        if (destination == TunesLinkDestination.NowPlaying) {
            viewModel.navigate(TunesLinkDestination.Library)
        }
        if (destination != TunesLinkDestination.Search && state.browse.kind == null) {
            viewModel.openLibraryKind(LibraryBrowseKind.Albums)
        }
    }

    Column(modifier.background(TunesLinkTheme.colors.canvas)) {
        TabletWorkspaceHeader(state, destination, viewModel)
        HorizontalDivider(color = TunesLinkTheme.colors.separator)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val sidebarWidth = if (maxWidth < 800.dp) 148.dp else 204.dp
            Row(Modifier.fillMaxSize()) {
                TabletLibrarySidebar(
                    selectedKind = state.browse.kind,
                    selected = destination == TunesLinkDestination.Library,
                    width = sidebarWidth,
                    onSelect = { kind ->
                        viewModel.cancelSearch()
                        viewModel.navigate(TunesLinkDestination.Library)
                        viewModel.openLibraryKind(kind)
                    },
                )
                VerticalDivider(color = TunesLinkTheme.colors.separator)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (destination) {
                        TunesLinkDestination.Library -> TabletLibraryPane(state, viewModel)
                        TunesLinkDestination.Search -> TabletSearchPane(state, viewModel)
                        TunesLinkDestination.NowPlaying -> TabletLibraryPane(state, viewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun TabletWorkspaceHeader(
    state: TunesLinkUiState,
    destination: TunesLinkDestination,
    viewModel: TunesLinkViewModel,
) {
    val controlsEnabled = ConnectionAvailability.from(state.connection).controlsEnabled
    val density = LocalDensity.current
    val compactHeight = with(density) {
        LocalWindowInfo.current.containerSize.height.toDp() < 500.dp
    }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(if (compactHeight) 72.dp else 82.dp)
            .background(TunesLinkTheme.colors.surface.copy(alpha = 0.96f))
            .padding(horizontal = 12.dp),
    ) {
        val compactWidth = maxWidth < 800.dp
        val leftWidth = if (compactWidth) 152.dp else 168.dp
        val rightWidth = when {
            maxWidth >= 900.dp -> 330.dp
            compactWidth -> 188.dp
            else -> 230.dp
        }
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.width(leftWidth).fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TabletTransportButton(
                        Icons.Rounded.SkipPrevious,
                        stringResource(R.string.previous_song),
                        controlsEnabled && !state.player.hasPendingConflict(PlaybackAction.Previous),
                        compact = compactWidth,
                        onClick = viewModel::previous,
                    )
                    TabletTransportButton(
                        if (state.player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        stringResource(if (state.player.playing) R.string.pause else R.string.play),
                        controlsEnabled && !state.player.hasPendingConflict(PlaybackAction.PlayPause),
                        emphasized = true,
                        compact = compactWidth,
                        onClick = viewModel::togglePlayback,
                    )
                    TabletTransportButton(
                        Icons.Rounded.SkipNext,
                        stringResource(R.string.next_song),
                        controlsEnabled && !state.player.hasPendingConflict(PlaybackAction.Next),
                        compact = compactWidth,
                        onClick = viewModel::next,
                    )
                }
                TabletVolumeControl(
                    player = state.player,
                    controlsEnabled = controlsEnabled,
                    onVolumeChange = viewModel::setVolume,
                    modifier = Modifier.fillMaxWidth().height(if (compactHeight) 20.dp else 22.dp),
                )
            }
            Row(
                Modifier.weight(1f).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TabletNowPlayingSurface(
                    player = state.player,
                    controlsEnabled = controlsEnabled,
                    compact = compactWidth,
                    onSeek = viewModel::seek,
                    onShuffle = viewModel::toggleShuffle,
                    onRepeat = viewModel::cycleRepeat,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                Modifier.width(rightWidth),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = state.library.editingQuery,
                    onValueChange = {
                        if (destination != TunesLinkDestination.Search) {
                            viewModel.navigate(TunesLinkDestination.Search)
                        }
                        viewModel.updateSearchQuery(it)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp)
                        .onFocusChanged {
                            viewModel.setSearchActive(it.isFocused)
                            if (it.isFocused && destination != TunesLinkDestination.Search) {
                                viewModel.navigate(TunesLinkDestination.Search)
                            }
                        },
                    placeholder = { Text(stringResource(R.string.search)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = if (state.library.editingQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                Icon(Icons.Rounded.Clear, stringResource(R.string.clear_search))
                            }
                        }
                    } else null,
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = tunesLinkTextFieldColors(),
                )
                TabletConnectionButton(state, viewModel::showConnectionDetails)
            }
        }
    }
}

@Composable
private fun TabletNowPlayingSurface(
    player: PlayerUiState,
    controlsEnabled: Boolean,
    compact: Boolean,
    onSeek: (Double) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(62.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(TunesLinkTheme.colors.raisedSurface.copy(alpha = 0.72f))
            .padding(start = if (compact) 4.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkSurface(
            bitmap = player.artwork,
            description = player.artwork?.let {
                stringResource(R.string.artwork_for, player.title)
            },
            modifier = Modifier.size(if (compact) 44.dp else 52.dp),
        )
        TabletNowPlayingHeader(
            player = player,
            controlsEnabled = controlsEnabled,
            compact = compact,
            onSeek = onSeek,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TabletPlaybackModeButton(
                icon = Icons.Rounded.Shuffle,
                description = stringResource(
                    if (player.shuffleEnabled) {
                        R.string.turn_shuffle_off
                    } else {
                        R.string.turn_shuffle_on
                    },
                ),
                selected = player.shuffleEnabled,
                enabled = controlsEnabled &&
                    !player.hasPendingConflict(PlaybackAction.Shuffle),
                compact = compact,
                onClick = onShuffle,
            )
            TabletPlaybackModeButton(
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
                enabled = controlsEnabled &&
                    !player.hasPendingConflict(PlaybackAction.Repeat),
                compact = compact,
                onClick = onRepeat,
            )
        }
    }
}

@Composable
private fun TabletVolumeControl(
    player: PlayerUiState,
    controlsEnabled: Boolean,
    onVolumeChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    var volumeValue by remember { mutableFloatStateOf(player.volume.toFloat()) }
    var adjusting by remember { mutableStateOf(false) }
    LaunchedEffect(player.volume, player.pending(PlaybackAction.Volume), adjusting) {
        if (!adjusting && player.pending(PlaybackAction.Volume) == null) {
            volumeValue = player.volume.toFloat()
        }
    }
    val volumeDescription = stringResource(
        R.string.itunes_volume_accessibility,
        volumeValue.toInt(),
    )
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Rounded.VolumeDown,
            contentDescription = null,
            tint = TunesLinkTheme.colors.secondaryText,
            modifier = Modifier.size(16.dp),
        )
        TunesLinkSlider(
            value = volumeValue.coerceIn(0f, 100f),
            onValueChange = {
                adjusting = true
                volumeValue = it
            },
            onValueChangeFinished = {
                adjusting = false
                onVolumeChange(volumeValue.toInt())
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            },
            valueRange = 0f..100f,
            enabled = controlsEnabled && !player.hasPendingConflict(PlaybackAction.Volume),
            lowEmphasis = true,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .semantics { contentDescription = volumeDescription },
        )
    }
}

@Composable
private fun TabletTransportButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    emphasized: Boolean = false,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(if (compact) 48.dp else 52.dp)
            .clip(CircleShape)
            .background(if (emphasized) TunesLinkTheme.colors.primaryText else Color.Transparent)
            .semantics { contentDescription = description },
    ) {
        val iconTransition = if (TunesLinkTheme.motion.spatialEnabled) {
            fadeIn(tween(TunesLinkMotion.SmallFeedback, easing = TunesLinkMotion.EaseOut)) +
                scaleIn(
                    tween(TunesLinkMotion.SmallFeedback, easing = TunesLinkMotion.EaseOut),
                    initialScale = 0.88f,
                ) togetherWith
                fadeOut(tween(TunesLinkMotion.PressDown, easing = TunesLinkMotion.EaseOut)) +
                scaleOut(
                    tween(TunesLinkMotion.PressDown, easing = TunesLinkMotion.EaseOut),
                    targetScale = 0.88f,
                )
        } else {
            fadeIn(tween(TunesLinkMotion.ReducedMotionFade)) togetherWith
                fadeOut(tween(TunesLinkMotion.ReducedMotionFade))
        }
        AnimatedContent(
            targetState = icon,
            transitionSpec = { iconTransition },
            label = "Tablet transport icon",
        ) { displayedIcon ->
            Icon(
                displayedIcon,
                null,
                tint = if (emphasized) TunesLinkTheme.colors.canvas else TunesLinkTheme.colors.primaryText,
                modifier = Modifier.size(if (emphasized) 28.dp else 26.dp),
            )
        }
    }
}

@Composable
private fun TabletPlaybackModeButton(
    icon: ImageVector,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val size = if (compact) 48.dp else 52.dp
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) {
                    TunesLinkTheme.colors.accentText.copy(alpha = 0.16f)
                } else {
                    Color.Transparent
                },
            )
            .semantics {
                contentDescription = description
                this.selected = selected
            },
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) {
                TunesLinkTheme.colors.accentText
            } else {
                TunesLinkTheme.colors.secondaryText
            },
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun TabletNowPlayingHeader(
    player: PlayerUiState,
    controlsEnabled: Boolean,
    compact: Boolean,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    var seekValue by remember(player.trackId, player.duration) {
        mutableFloatStateOf(player.position.toFloat())
    }
    var seeking by remember(player.trackId) { mutableStateOf(false) }
    LaunchedEffect(player.position, player.pending(PlaybackAction.Position), seeking) {
        if (!seeking && player.pending(PlaybackAction.Position) == null) {
            seekValue = player.position.toFloat()
        }
    }
    val duration = max(1f, player.duration.toFloat())
    val seekDescription = stringResource(
        R.string.track_position_accessibility,
        formatTime(seekValue.toDouble()),
        formatTime(player.duration),
    )
    Box(modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = if (compact) 6.dp else 12.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                player.title.ifBlank { stringResource(R.string.nothing_playing) },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = TunesLinkTheme.colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(player.artist, player.album).filter(String::isNotBlank).joinToString(" · ")
                    .ifBlank { stringResource(R.string.open_itunes_on_computer) },
                style = MaterialTheme.typography.labelMedium,
                color = TunesLinkTheme.colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TunesLinkSlider(
            value = seekValue.coerceIn(0f, duration),
            onValueChange = {
                seeking = true
                seekValue = it
            },
            onValueChangeFinished = {
                seeking = false
                onSeek(seekValue.toDouble())
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            },
            valueRange = 0f..duration,
            enabled = controlsEnabled && player.duration > 0 &&
                !player.hasPendingConflict(PlaybackAction.Position),
            lowEmphasis = true,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(24.dp)
                .padding(horizontal = 10.dp)
                .semantics { contentDescription = seekDescription },
        )
    }
}

@Composable
private fun TabletConnectionButton(state: TunesLinkUiState, onClick: () -> Unit) {
    val connected = state.connection is ConnectionState.Connected
    val availability = ConnectionAvailability.from(state.connection)
    val description = stringResource(
        R.string.connection_status_accessibility,
        state.bridgeName.ifBlank { stringResource(R.string.connection_default) },
        stringResource(availability.titleRes),
    )
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(52.dp).semantics { contentDescription = description },
    ) {
        Box {
            Icon(
                if (connected) Icons.Rounded.Computer else Icons.Rounded.WifiOff,
                null,
                tint = if (connected) TunesLinkTheme.colors.secondaryText else TunesLinkTheme.colors.danger,
                modifier = Modifier.size(24.dp),
            )
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (connected) TunesLinkTheme.colors.success else TunesLinkTheme.colors.danger),
            )
        }
    }
}

@Composable
private fun TabletLibrarySidebar(
    selectedKind: LibraryBrowseKind?,
    selected: Boolean,
    width: androidx.compose.ui.unit.Dp,
    onSelect: (LibraryBrowseKind) -> Unit,
) {
    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            .background(TunesLinkTheme.colors.surface.copy(alpha = 0.72f))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 16.dp),
    ) {
        Text(
            stringResource(R.string.library),
            style = MaterialTheme.typography.labelLarge,
            color = TunesLinkTheme.colors.secondaryText,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        LibraryBrowseKind.entries.forEach { kind ->
            TabletSidebarItem(
                kind = kind,
                selected = selected && selectedKind == kind,
                onClick = { onSelect(kind) },
            )
        }
    }
}

@Composable
private fun TabletSidebarItem(kind: LibraryBrowseKind, selected: Boolean, onClick: () -> Unit) {
    val label = kind.tabletLabel()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = TunesLinkSizes.minimumTarget)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) TunesLinkTheme.colors.accentText.copy(alpha = 0.16f)
                else Color.Transparent,
            )
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics {
                this.selected = selected
                contentDescription = label
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            kind.tabletIcon(),
            null,
            tint = if (selected) TunesLinkTheme.colors.accentText else TunesLinkTheme.colors.secondaryText,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) TunesLinkTheme.colors.primaryText else TunesLinkTheme.colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TabletLibraryPane(state: TunesLinkUiState, viewModel: TunesLinkViewModel) {
    when (state.browse.kind) {
        LibraryBrowseKind.Albums -> TabletAlbumsPane(state, viewModel)
        LibraryBrowseKind.Songs -> TabletSongsPane(
            title = stringResource(R.string.songs),
            tracks = state.browse.tracks,
            total = state.browse.total,
            loading = state.browse.isLoading,
            error = state.browse.error,
            hasMore = state.browse.hasMore,
            onLoadMore = viewModel::loadMoreBrowse,
            state = state,
            viewModel = viewModel,
        )
        LibraryBrowseKind.Artists,
        LibraryBrowseKind.Genres,
        LibraryBrowseKind.Playlists -> TabletCollectionMasterDetail(state, viewModel)
        null -> ContentState(
            stringResource(R.string.loading_library),
            stringResource(R.string.loading_library_detail),
            loading = true,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun TabletAlbumsPane(state: TunesLinkUiState, viewModel: TunesLinkViewModel) {
    val browse = state.browse
    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState, browse.collections.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (last >= gridState.layoutInfo.totalItemsCount - 6) viewModel.loadMoreBrowse() }
    }
    when {
        browse.isLoading && browse.collections.isEmpty() -> ContentState(
            stringResource(R.string.loading_library),
            stringResource(R.string.loading_library_detail),
            loading = true,
            modifier = Modifier.fillMaxSize(),
        )
        browse.error != null && browse.collections.isEmpty() -> ContentState(
            stringResource(R.string.library_unavailable),
            browse.error,
            onRetry = { viewModel.openLibraryKind(LibraryBrowseKind.Albums) },
            modifier = Modifier.fillMaxSize(),
        )
        else -> BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val compactHeight = with(density) {
                LocalWindowInfo.current.containerSize.height.toDp() < 500.dp
            }
            val contentPadding = if (compactHeight) 12.dp else 24.dp
            val targetCellWidth = if (compactHeight) 108.dp else 174.dp
            val columnCount = ((maxWidth - contentPadding * 2) / targetCellWidth)
                .toInt().coerceAtLeast(2)
            val selectedIndex = browse.collections.indexOfFirst {
                it.id == browse.selectedCollection?.id
            }
            val detailAfterIndex = if (selectedIndex >= 0) {
                min(
                    browse.collections.lastIndex,
                    ((selectedIndex / columnCount) + 1) * columnCount - 1,
                )
            } else -1
            LazyVerticalGrid(
                columns = GridCells.Fixed(columnCount),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(contentPadding),
                horizontalArrangement = Arrangement.spacedBy(if (compactHeight) 12.dp else 18.dp),
                verticalArrangement = Arrangement.spacedBy(if (compactHeight) 12.dp else 20.dp),
            ) {
                item(key = "albums-heading", span = { GridItemSpan(maxLineSpan) }) {
                    TabletPaneHeading(
                        stringResource(R.string.albums),
                        browse.selectedCollection?.parentTotal ?: browse.total,
                    )
                }
                browse.collections.forEachIndexed { index, collection ->
                    item(key = collection.id) {
                        TabletAlbumCard(
                            collection = collection,
                            selected = browse.selectedCollection?.id == collection.id,
                            viewModel = viewModel,
                            compact = compactHeight,
                            onClick = {
                                if (browse.selectedCollection?.id == collection.id) {
                                    viewModel.navigateUpLibrary()
                                } else {
                                    viewModel.openLibraryCollection(collection)
                                }
                            },
                        )
                    }
                    if (index == detailAfterIndex) {
                        browse.selectedCollection?.let { selected ->
                            item(
                                key = "album-detail:${selected.id}",
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                TabletExpandedAlbum(
                                    selected = selected,
                                    artworkId = browse.collections
                                        .firstOrNull { it.id == selected.id }?.artworkId.orEmpty(),
                                    tracks = browse.tracks,
                                    loading = browse.isLoading,
                                    state = state,
                                    viewModel = viewModel,
                                    onCollapse = { viewModel.navigateUpLibrary() },
                                    modifier = if (TunesLinkTheme.motion.spatialEnabled) {
                                        Modifier.animateItem(
                                            fadeInSpec = tween(
                                                TunesLinkMotion.AlbumDetailEnter,
                                                easing = TunesLinkMotion.EaseOut,
                                            ),
                                            placementSpec = spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = Spring.StiffnessMediumLow,
                                            ),
                                            fadeOutSpec = tween(
                                                TunesLinkMotion.AlbumDetailExit,
                                                easing = TunesLinkMotion.EaseOut,
                                            ),
                                        )
                                    } else {
                                        Modifier.animateItem(
                                            fadeInSpec = tween(TunesLinkMotion.ReducedMotionFade),
                                            placementSpec = null,
                                            fadeOutSpec = tween(TunesLinkMotion.ReducedMotionFade),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
                if (browse.isLoadingMore) {
                    item(key = "album-progress", span = { GridItemSpan(maxLineSpan) }) {
                        TabletProgress()
                    }
                }
            }
        }
    }
}

@Composable
private fun TabletPaneHeading(title: String, total: Int, detail: String = "") {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
                color = TunesLinkTheme.colors.primaryText,
                modifier = Modifier.semantics { heading() },
            )
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TunesLinkTheme.colors.secondaryText,
                )
            }
        }
        Text(
            pluralStringResource(R.plurals.result_count, total, total),
            style = MaterialTheme.typography.bodyMedium,
            color = TunesLinkTheme.colors.secondaryText,
        )
    }
}

@Composable
private fun TabletAlbumCard(
    collection: LibraryCollectionUiState,
    selected: Boolean,
    viewModel: TunesLinkViewModel,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val actionLabel = if (selected) {
        stringResource(R.string.collapse_album)
    } else {
        stringResource(R.string.expand_album, collection.title)
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && TunesLinkTheme.motion.spatialEnabled) 0.985f else 1f,
        animationSpec = tween(
            if (pressed) TunesLinkMotion.PressDown else TunesLinkMotion.PressRelease,
            easing = TunesLinkMotion.EaseOut,
        ),
        label = "Album card press",
    )
    val background by animateColorAsState(
        targetValue = if (selected) {
            TunesLinkTheme.colors.accentText.copy(alpha = 0.13f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(TunesLinkMotion.SmallFeedback, easing = TunesLinkMotion.EaseOut),
        label = "Album selection",
    )
    Column(
        Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .clickable(
                role = Role.Button,
                onClickLabel = actionLabel,
                interactionSource = interactionSource,
                onClick = onClick,
            )
            .padding(8.dp),
    ) {
        TabletArtwork(
            artworkId = collection.artworkId,
            title = collection.title,
            viewModel = viewModel,
            maxSize = 384,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            collection.title,
            style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = TunesLinkTheme.colors.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            collection.subtitle.ifBlank {
                pluralStringResource(R.plurals.song_count, collection.trackCount, collection.trackCount)
            },
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
            color = TunesLinkTheme.colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TabletExpandedAlbum(
    selected: SelectedLibraryCollection,
    artworkId: String,
    tracks: List<TrackUiState>,
    loading: Boolean,
    state: TunesLinkUiState,
    viewModel: TunesLinkViewModel,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val revealState = remember(selected.id) {
        MutableTransitionState(false).apply { targetState = true }
    }
    val enter = if (TunesLinkTheme.motion.spatialEnabled) {
        fadeIn(tween(TunesLinkMotion.AlbumDetailEnter, easing = TunesLinkMotion.EaseOut)) +
            scaleIn(
                animationSpec = tween(
                    TunesLinkMotion.AlbumDetailEnter,
                    easing = TunesLinkMotion.EaseOut,
                ),
                initialScale = 0.985f,
                transformOrigin = TransformOrigin(0.5f, 0f),
            )
    } else {
        fadeIn(tween(TunesLinkMotion.ReducedMotionFade, easing = TunesLinkMotion.EaseOut))
    }
    AnimatedVisibility(
        visibleState = revealState,
        enter = enter,
        exit = fadeOut(tween(TunesLinkMotion.AlbumDetailExit, easing = TunesLinkMotion.EaseOut)),
        modifier = modifier,
    ) {
        Surface(
            color = TunesLinkTheme.colors.raisedSurface.copy(alpha = 0.64f),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(Modifier.fillMaxWidth().padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Column(Modifier.widthIn(min = 180.dp, max = 260.dp).weight(0.34f)) {
                    TabletArtwork(
                        artworkId = artworkId,
                        title = selected.title,
                        viewModel = viewModel,
                        maxSize = 512,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        selected.title,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = TunesLinkTheme.colors.primaryText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (selected.subtitle.isNotBlank()) {
                        Text(
                            selected.subtitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = TunesLinkTheme.colors.accentText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TunesLinkTonalAction(
                        label = stringResource(R.string.collapse_album),
                        onClick = onCollapse,
                        icon = Icons.Rounded.ExpandLess,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                    Column(Modifier.weight(0.66f)) {
                        if (loading) {
                            TabletProgress()
                        } else {
                            tracks.forEachIndexed { index, track ->
                                TabletTrackRow(
                                    track = track,
                                    position = track.trackNumber.takeIf { it > 0 } ?: index + 1,
                                    state = state,
                                    viewModel = viewModel,
                                    showColumns = false,
                                    collection = selected,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabletCollectionMasterDetail(state: TunesLinkUiState, viewModel: TunesLinkViewModel) {
    val browse = state.browse
    val kind = browse.kind ?: return
    val listState = rememberLazyListState()
    LaunchedEffect(listState, browse.collections.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (last >= browse.collections.lastIndex - 5) viewModel.loadMoreBrowse() }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val masterWidth = if (maxWidth < 700.dp) 196.dp else 280.dp
        Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(masterWidth).fillMaxHeight()) {
            Text(
                kind.tabletLabel(),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
                color = TunesLinkTheme.colors.primaryText,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp).semantics { heading() },
            )
            HorizontalDivider(color = TunesLinkTheme.colors.separator)
            when {
                browse.isLoading && browse.collections.isEmpty() -> TabletProgress()
                browse.error != null && browse.collections.isEmpty() -> ContentState(
                    stringResource(R.string.library_unavailable),
                    browse.error,
                    onRetry = { viewModel.openLibraryKind(kind) },
                    modifier = Modifier.fillMaxSize(),
                )
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(browse.collections, key = { it.id }) { collection ->
                        TabletCollectionListRow(
                            collection = collection,
                            selected = browse.selectedCollection?.id == collection.id,
                            viewModel = viewModel,
                            onClick = { viewModel.openLibraryCollection(collection) },
                        )
                    }
                    if (browse.isLoadingMore) item { TabletProgress() }
                }
            }
        }
        VerticalDivider(color = TunesLinkTheme.colors.separator)
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val selected = browse.selectedCollection
            when {
                selected == null -> ContentState(
                    kind.tabletLabel(),
                    stringResource(R.string.albums_and_songs),
                    modifier = Modifier.fillMaxSize(),
                )
                browse.isLoading && browse.tracks.isEmpty() -> ContentState(
                    stringResource(R.string.loading_library),
                    stringResource(R.string.loading_library_detail),
                    loading = true,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> TabletGroupedCollectionDetail(selected, state, viewModel)
            }
        }
        }
    }
}

@Composable
private fun TabletCollectionListRow(
    collection: LibraryCollectionUiState,
    selected: Boolean,
    viewModel: TunesLinkViewModel,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp)
            .background(if (selected) TunesLinkTheme.colors.accentText.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
            .semantics { this.selected = selected },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabletArtwork(
            artworkId = collection.artworkId,
            title = collection.title,
            viewModel = viewModel,
            maxSize = 128,
            modifier = Modifier.size(46.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                collection.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = TunesLinkTheme.colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(
                    collection.subtitle,
                    pluralStringResource(R.plurals.song_count, collection.trackCount, collection.trackCount),
                ).filter(String::isNotBlank).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = TunesLinkTheme.colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TabletGroupedCollectionDetail(
    selected: SelectedLibraryCollection,
    state: TunesLinkUiState,
    viewModel: TunesLinkViewModel,
) {
    val groups = state.browse.tracks.groupBy { it.album.ifBlank { stringResource(R.string.album) } }
    val listState = rememberLazyListState()
    LaunchedEffect(listState, state.browse.tracks.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (last >= listState.layoutInfo.totalItemsCount - 4) viewModel.loadMoreBrowse() }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            TabletPaneHeading(
                title = selected.title,
                total = state.browse.total,
                detail = selected.subtitle,
            )
        }
        groups.forEach { (album, tracks) ->
            item(key = "album:$album") {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    TabletArtwork(
                        artworkId = tracks.firstOrNull()?.artworkId.orEmpty(),
                        title = album,
                        viewModel = viewModel,
                        maxSize = 256,
                        modifier = Modifier.size(132.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            album,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = TunesLinkTheme.colors.primaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        tracks.forEachIndexed { index, track ->
                            TabletTrackRow(
                                track = track,
                                position = track.trackNumber.takeIf { it > 0 } ?: index + 1,
                                state = state,
                                viewModel = viewModel,
                                showColumns = false,
                                collection = selected,
                            )
                        }
                    }
                }
            }
        }
        if (state.browse.isLoadingMore) item { TabletProgress() }
    }
}

@Composable
private fun TabletSongsPane(
    title: String,
    tracks: List<TrackUiState>,
    total: Int,
    loading: Boolean,
    error: String?,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    state: TunesLinkUiState,
    viewModel: TunesLinkViewModel,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(listState, tracks.size, hasMore) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (hasMore && last >= tracks.lastIndex - 6) onLoadMore() }
    }
    when {
        loading && tracks.isEmpty() -> ContentState(
            stringResource(R.string.loading_library),
            stringResource(R.string.loading_library_detail),
            loading = true,
            modifier = Modifier.fillMaxSize(),
        )
        error != null && tracks.isEmpty() -> ContentState(
            stringResource(R.string.library_unavailable),
            error,
            modifier = Modifier.fillMaxSize(),
        )
        tracks.isEmpty() -> ContentState(
            stringResource(R.string.no_songs),
            stringResource(R.string.no_songs_detail),
            modifier = Modifier.fillMaxSize(),
        )
        else -> BoxWithConstraints(Modifier.fillMaxSize()) {
            val showFullColumns = maxWidth >= 620.dp
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = if (showFullColumns) 20.dp else 12.dp,
                    vertical = 16.dp,
                ),
            ) {
                item { TabletPaneHeading(title, total) }
                stickyHeader { TabletSongTableHeader(showFullColumns) }
                itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                    TabletTrackRow(
                        track = track,
                        position = track.trackNumber.takeIf { it > 0 } ?: index + 1,
                        state = state,
                        viewModel = viewModel,
                        showColumns = showFullColumns,
                        showMetadataUnderTitle = !showFullColumns,
                        striped = index % 2 == 1,
                    )
                }
                if (loading) item { TabletProgress() }
            }
        }
    }
}

@Composable
private fun TabletSongTableHeader(showFullColumns: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(TunesLinkTheme.colors.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("#", style = MaterialTheme.typography.labelMedium, color = TunesLinkTheme.colors.secondaryText,
            modifier = Modifier.width(42.dp))
        Text(stringResource(R.string.song), style = MaterialTheme.typography.labelMedium,
            color = TunesLinkTheme.colors.secondaryText, modifier = Modifier.weight(2f))
        if (showFullColumns) {
            Text(stringResource(R.string.artist), style = MaterialTheme.typography.labelMedium,
                color = TunesLinkTheme.colors.secondaryText, modifier = Modifier.weight(1.1f))
            Text(stringResource(R.string.album), style = MaterialTheme.typography.labelMedium,
                color = TunesLinkTheme.colors.secondaryText, modifier = Modifier.weight(1.1f))
        }
        Text(stringResource(R.string.time), style = MaterialTheme.typography.labelMedium,
            color = TunesLinkTheme.colors.secondaryText, modifier = Modifier.width(62.dp))
    }
}

@Composable
private fun TabletTrackRow(
    track: TrackUiState,
    position: Int,
    state: TunesLinkUiState,
    viewModel: TunesLinkViewModel,
    showColumns: Boolean,
    collection: SelectedLibraryCollection? = null,
    showMetadataUnderTitle: Boolean = false,
    striped: Boolean = false,
) {
    val current = state.player.trackId == track.id
    val enabled = ConnectionAvailability.from(state.connection).controlsEnabled &&
        !state.player.hasPendingConflict(PlaybackAction.PlayTrack)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = TunesLinkSizes.minimumTarget)
            .clip(RoundedCornerShape(if (showColumns) 4.dp else 10.dp))
            .background(
                when {
                    current -> TunesLinkTheme.colors.accentText.copy(alpha = 0.14f)
                    striped -> TunesLinkTheme.colors.raisedSurface.copy(alpha = 0.5f)
                    else -> Color.Transparent
                },
            )
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = stringResource(R.string.play_track, track.title),
                onClick = { viewModel.playTrack(track, collection) },
            )
            .semantics { selected = current }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(42.dp), contentAlignment = Alignment.CenterStart) {
            if (current) {
                Icon(
                    if (state.player.playing) Icons.Rounded.Equalizer else Icons.Rounded.Pause,
                    stringResource(if (state.player.playing) R.string.now_playing else R.string.current_track),
                    tint = TunesLinkTheme.colors.accentText,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Text(
                    position.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TunesLinkTheme.colors.secondaryText,
                )
            }
        }
        Column(Modifier.weight(if (showColumns) 2f else 1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (current) TunesLinkTheme.colors.accentText else TunesLinkTheme.colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showMetadataUnderTitle) {
                Text(
                    listOf(track.artist, track.album).filter(String::isNotBlank).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = TunesLinkTheme.colors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showColumns) {
            Text(
                track.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = TunesLinkTheme.colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1.1f).padding(start = 12.dp),
            )
            Text(
                track.album,
                style = MaterialTheme.typography.bodyMedium,
                color = TunesLinkTheme.colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1.1f).padding(start = 12.dp),
            )
        }
        Text(
            formatTime(track.duration),
            style = MaterialTheme.typography.labelMedium,
            color = TunesLinkTheme.colors.secondaryText,
            modifier = Modifier.width(62.dp).padding(start = 8.dp),
        )
    }
}

@Composable
private fun TabletSearchPane(state: TunesLinkUiState, viewModel: TunesLinkViewModel) {
    val library = state.library
    when {
        library.editingQuery.isBlank() -> ContentState(
            stringResource(R.string.search_your_music),
            stringResource(R.string.search_your_music_detail),
            modifier = Modifier.fillMaxSize(),
        )
        else -> TabletSongsPane(
            title = stringResource(R.string.search_results),
            tracks = library.items,
            total = library.total,
            loading = library.isRefreshing || library.isLoadingMore,
            error = library.error,
            hasMore = library.hasMore,
            onLoadMore = viewModel::loadMore,
            state = state,
            viewModel = viewModel,
        )
    }
}

@Composable
private fun TabletArtwork(
    artworkId: String,
    title: String,
    viewModel: TunesLinkViewModel,
    maxSize: Int,
    modifier: Modifier = Modifier,
) {
    var artwork by remember(artworkId) { mutableStateOf<Bitmap?>(null) }
    DisposableEffect(artworkId, maxSize) {
        val request = viewModel.requestArtwork(artworkId, maxSize, object : BridgeClient.Result<Bitmap> {
            override fun success(value: Bitmap?) { artwork = value }
            override fun failure(message: String, unauthorized: Boolean) = Unit
        })
        onDispose(request::cancel)
    }
    ArtworkSurface(
        bitmap = artwork,
        description = if (artwork != null) stringResource(R.string.artwork_for, title) else null,
        modifier = modifier,
    )
}

@Composable
private fun TabletProgress() {
    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            color = TunesLinkTheme.colors.accentText,
            strokeWidth = 2.dp,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun LibraryBrowseKind.tabletLabel(): String = when (this) {
    LibraryBrowseKind.Playlists -> stringResource(R.string.playlists)
    LibraryBrowseKind.Artists -> stringResource(R.string.artists)
    LibraryBrowseKind.Albums -> stringResource(R.string.albums)
    LibraryBrowseKind.Songs -> stringResource(R.string.songs)
    LibraryBrowseKind.Genres -> stringResource(R.string.genres)
}

private fun LibraryBrowseKind.tabletIcon(): ImageVector = when (this) {
    LibraryBrowseKind.Playlists -> Icons.AutoMirrored.Rounded.PlaylistPlay
    LibraryBrowseKind.Artists -> Icons.Rounded.Person
    LibraryBrowseKind.Albums -> Icons.Rounded.Album
    LibraryBrowseKind.Songs -> Icons.Rounded.MusicNote
    LibraryBrowseKind.Genres -> Icons.Rounded.Equalizer
}
