package com.kamyarps.tuneslink

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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

@Composable
internal fun TabletTunesWorkspace(
    state: TunesLinkUiState,
    destination: TunesLinkDestination,
    viewModel: TunesLinkViewModel,
    modifier: Modifier = Modifier,
) {
    // The workspace shows Now Playing as the library workspace (transport lives in the header)
    // without navigating, and opens a default category only for display. Neither rewrites the
    // stored destination, so rotating back to the phone layout returns the user where they were.
    val needsDefaultKind = destination != TunesLinkDestination.Search && state.browse.kind == null
    LaunchedEffect(needsDefaultKind) {
        if (needsDefaultKind) viewModel.openWorkspaceDefaultKind()
    }
    val openedCollection = state.browse.selectedCollection != null
    LaunchedEffect(openedCollection) {
        if (openedCollection) viewModel.claimWorkspaceBrowse()
    }

    Column(modifier.background(TunesLinkTheme.colors.canvas)) {
        TabletWorkspaceHeader(state, destination, viewModel)
        HorizontalDivider(color = TunesLinkTheme.colors.separator)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val sidebarWidth = if (maxWidth < 800.dp) 148.dp else 204.dp
            Row(Modifier.fillMaxSize()) {
                TabletLibrarySidebar(
                    selectedKind = state.browse.kind,
                    selected = destination != TunesLinkDestination.Search,
                    width = sidebarWidth,
                    onSelect = { kind ->
                        viewModel.claimWorkspaceBrowse()
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
    val controlsEnabled = state.playbackControlsEnabled
    val density = LocalDensity.current
    val compactHeight = with(density) {
        LocalWindowInfo.current.containerSize.height.toDp() < 500.dp
    }
    val headerMinHeight = if (compactHeight) 72.dp else 82.dp
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .heightIn(min = headerMinHeight)
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
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = headerMinHeight)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.width(leftWidth),
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
                        controlsEnabled,
                        compact = compactWidth,
                        onClick = viewModel::previous,
                    )
                    TabletTransportButton(
                        if (state.player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        stringResource(if (state.player.playing) R.string.pause else R.string.play),
                        controlsEnabled,
                        emphasized = true,
                        compact = compactWidth,
                        onClick = viewModel::togglePlayback,
                    )
                    TabletTransportButton(
                        Icons.Rounded.SkipNext,
                        stringResource(R.string.next_song),
                        controlsEnabled,
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
                    viewModel = viewModel,
                    controlsEnabled = controlsEnabled,
                    compact = compactWidth,
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
    viewModel: TunesLinkViewModel,
    controlsEnabled: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val modeButtonSize = if (compact) 48.dp else 52.dp
    Row(
        modifier = modifier
            .heightIn(min = 62.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(TunesLinkTheme.colors.raisedSurface.copy(alpha = 0.72f))
            .padding(start = if (compact) 4.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkSurface(
            bitmap = player.artwork,
            description = playerArtworkDescription(player),
            modifier = Modifier.size(if (compact) 44.dp else 52.dp),
        )
        TabletNowPlayingHeader(
            player = player,
            viewModel = viewModel,
            controlsEnabled = controlsEnabled,
            compact = compact,
            modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShuffleToggle(
                player = player,
                enabled = controlsEnabled,
                size = modeButtonSize,
                onClick = viewModel::toggleShuffle,
            )
            RepeatToggle(
                player = player,
                enabled = controlsEnabled,
                size = modeButtonSize,
                onClick = viewModel::cycleRepeat,
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
            enabled = controlsEnabled,
            lowEmphasis = true,
            semanticsLabel = stringResource(R.string.itunes_volume),
            semanticsState = stringResource(R.string.volume_state, volumeValue.toInt()),
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
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
            .background(
                // Explicit colours override IconButton's disabled state; dim them ourselves.
                if (emphasized) {
                    TunesLinkTheme.colors.primaryText.copy(alpha = if (enabled) 1f else DISABLED_CONTENT_ALPHA)
                } else {
                    Color.Transparent
                },
            )
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
                tint = (if (emphasized) TunesLinkTheme.colors.canvas else TunesLinkTheme.colors.primaryText)
                    .copy(alpha = if (enabled) 1f else DISABLED_CONTENT_ALPHA),
                modifier = Modifier.size(if (emphasized) 28.dp else 26.dp),
            )
        }
    }
}

@Composable
private fun TabletNowPlayingHeader(
    player: PlayerUiState,
    viewModel: TunesLinkViewModel,
    controlsEnabled: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    // A column (not an overlaid box) so larger text pushes the scrubber down instead of under it.
    Column(
        modifier.padding(horizontal = if (compact) 6.dp else 12.dp, vertical = 2.dp),
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
            playerSubtitle(player, includeAlbum = true, unavailableHint = true),
            style = MaterialTheme.typography.labelMedium,
            color = TunesLinkTheme.colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        PlaybackProgress(
            viewModel = viewModel,
            trackId = player.trackId,
            duration = player.duration,
            enabled = controlsEnabled && player.duration > 0,
            onSeek = { position, draggedTrackId ->
                viewModel.seek(position, draggedTrackId)
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            },
            inline = true,
            modifier = Modifier.fillMaxWidth(),
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
    val showDetails = stringResource(R.string.show_connection_details)
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(52.dp).semantics {
            contentDescription = description
            onClick(label = showDetails) {
                onClick()
                true
            }
        },
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
            cursor = state.browse.tracksCursor,
            onLoadPrevious = { viewModel.loadPreviousBrowse(LibraryBrowseTarget.Tracks) },
            onLoadMore = { viewModel.loadMoreBrowse(LibraryBrowseTarget.Tracks) },
            state = state,
            viewModel = viewModel,
            onRetry = { viewModel.openLibraryKind(LibraryBrowseKind.Songs) },
            emptyCopy = LibraryBrowseKind.Songs.emptyCopy(),
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
    LibraryPagination(gridState, browse.collectionsCursor,
        onPrevious = { viewModel.loadPreviousBrowse(LibraryBrowseTarget.Collections) },
        onNext = { viewModel.loadMoreBrowse(LibraryBrowseTarget.Collections) })
    when {
        browse.collectionsCursor.isLoading && browse.collections.isEmpty() -> ContentState(
            stringResource(R.string.loading_library),
            stringResource(R.string.loading_library_detail),
            loading = true,
            modifier = Modifier.fillMaxSize(),
        )
        browse.collectionsCursor.error != null && browse.collections.isEmpty() -> ContentState(
            stringResource(R.string.library_unavailable),
            browse.collectionsCursor.error,
            onRetry = { viewModel.openLibraryKind(LibraryBrowseKind.Albums) },
            modifier = Modifier.fillMaxSize(),
        )
        browse.collections.isEmpty() && !browse.collectionsCursor.isBusy -> {
            val empty = if (browse.albumParent != null) {
                LibraryEmptyCopy(R.string.no_albums, R.string.no_albums_detail)
            } else {
                LibraryBrowseKind.Albums.emptyCopy()
            }
            ContentState(
                stringResource(empty.title),
                stringResource(empty.detail),
                modifier = Modifier.fillMaxSize(),
            )
        }
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
                if (browse.collectionsCursor.windowStart == 0) item(
                    key = "albums-heading",
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = "heading",
                ) {
                    TabletPaneHeading(
                        browse.albumParent?.title ?: stringResource(R.string.albums),
                        libraryCountLabel(LibraryBrowseKind.Albums, browse.collectionsCursor.total),
                    )
                }
                if (browse.collectionsCursor.isLoadingPrevious) {
                    item(
                        key = "albums-previous-progress",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = "progress",
                    ) { TabletProgress() }
                }
                if (browse.collectionsCursor.hasPrevious && browse.collectionsCursor.error != null) {
                    item(
                        key = "albums-previous-error",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = "error",
                    ) {
                        LibraryPageError(browse.collectionsCursor.error) {
                            viewModel.retryBrowse(LibraryBrowseTarget.Collections)
                        }
                    }
                }
                browse.collections.forEachIndexed { index, collection ->
                    item(key = collection.id, contentType = "album") {
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
                                contentType = "album-detail",
                            ) {
                                TabletExpandedAlbum(
                                    selected = selected,
                                    artworkId = browse.collections
                                        .firstOrNull { it.id == selected.id }?.artworkId.orEmpty(),
                                    tracks = browse.tracks,
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
                item(key = "albums-page-error", span = { GridItemSpan(maxLineSpan) }, contentType = "error") {
                    LibraryPageError(browse.collectionsCursor.error) {
                        viewModel.retryBrowse(LibraryBrowseTarget.Collections)
                    }
                }
                if (browse.collectionsCursor.isLoadingMore) {
                    item(key = "album-progress", span = { GridItemSpan(maxLineSpan) }, contentType = "progress") {
                        TabletProgress()
                    }
                }
            }
        }
    }
}

@Composable
private fun TabletPaneHeading(title: String, countLabel: String, detail: String = "") {
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
            countLabel,
            style = MaterialTheme.typography.bodyMedium.tabularNumerals(),
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
            viewModel = viewModel,
            maxSize = 384,
            cornerRadius = TunesLinkShapes.artworkMedium,
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
                        viewModel = viewModel,
                        maxSize = 512,
                        cornerRadius = TunesLinkShapes.artworkMedium,
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
                    Box(Modifier.weight(0.66f).height(420.dp)) {
                        TabletSongsPane(
                            title = selected.title,
                            tracks = tracks,
                            cursor = state.browse.tracksCursor,
                            onLoadPrevious = { viewModel.loadPreviousBrowse(LibraryBrowseTarget.Tracks) },
                            onLoadMore = { viewModel.loadMoreBrowse(LibraryBrowseTarget.Tracks) },
                            onRetry = { viewModel.retryBrowse(LibraryBrowseTarget.Tracks) },
                            state = state,
                            viewModel = viewModel,
                            collection = selected,
                            showHeading = false,
                            emptyCopy = LibraryEmptyCopy(R.string.no_songs, R.string.no_songs_detail),
                        )
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
    LibraryPagination(listState, browse.collectionsCursor,
        onPrevious = { viewModel.loadPreviousBrowse(LibraryBrowseTarget.Collections) },
        onNext = { viewModel.loadMoreBrowse(LibraryBrowseTarget.Collections) })
    val collectionsEmpty = browse.collections.isEmpty() && !browse.collectionsCursor.isBusy &&
        browse.collectionsCursor.error == null
    if (collectionsEmpty) {
        val empty = kind.emptyCopy()
        ContentState(
            stringResource(empty.title),
            stringResource(empty.detail),
            modifier = Modifier.fillMaxSize(),
        )
        return
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
                browse.collectionsCursor.isLoading && browse.collections.isEmpty() -> TabletProgress()
                browse.collectionsCursor.error != null && browse.collections.isEmpty() -> ContentState(
                    stringResource(R.string.library_unavailable),
                    browse.collectionsCursor.error,
                    onRetry = { viewModel.openLibraryKind(kind) },
                    modifier = Modifier.fillMaxSize(),
                )
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    if (browse.collectionsCursor.isLoadingPrevious) item(contentType = "progress") { TabletProgress() }
                    if (browse.collectionsCursor.hasPrevious && browse.collectionsCursor.error != null) {
                        item(key = "collections-previous-error", contentType = "error") {
                            LibraryPageError(browse.collectionsCursor.error) {
                                viewModel.retryBrowse(LibraryBrowseTarget.Collections)
                            }
                        }
                    }
                    items(browse.collections, key = { it.id }, contentType = { "collection" }) { collection ->
                        TabletCollectionListRow(
                            collection = collection,
                            selected = browse.selectedCollection?.id == collection.id,
                            viewModel = viewModel,
                            onClick = { viewModel.openLibraryCollection(collection) },
                        )
                    }
                    item(key = "collections-page-error", contentType = "error") {
                        LibraryPageError(browse.collectionsCursor.error) {
                            viewModel.retryBrowse(LibraryBrowseTarget.Collections)
                        }
                    }
                    if (browse.collectionsCursor.isLoadingMore) item(contentType = "progress") { TabletProgress() }
                }
            }
        }
        VerticalDivider(color = TunesLinkTheme.colors.separator)
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val selected = browse.selectedCollection
            when {
                selected == null -> ContentState(
                    kind.tabletLabel(),
                    stringResource(kind.selectionHint()),
                    modifier = Modifier.fillMaxSize(),
                )
                browse.tracksCursor.isLoading && browse.tracks.isEmpty() -> ContentState(
                    stringResource(R.string.loading_library),
                    stringResource(R.string.loading_library_detail),
                    loading = true,
                    modifier = Modifier.fillMaxSize(),
                )
                // A playlist is shown in the order it was arranged, which is also the order the
                // bridge plays it in. Only artists and genres are grouped into albums.
                !browse.groupsTracksByAlbum() -> TabletSongsPane(
                    title = selected.title,
                    tracks = browse.tracks,
                    cursor = browse.tracksCursor,
                    onLoadPrevious = { viewModel.loadPreviousBrowse(LibraryBrowseTarget.Tracks) },
                    onRetry = { viewModel.retryBrowse(LibraryBrowseTarget.Tracks) },
                    onLoadMore = { viewModel.loadMoreBrowse(LibraryBrowseTarget.Tracks) },
                    state = state,
                    viewModel = viewModel,
                    collection = selected,
                    emptyCopy = LibraryEmptyCopy(R.string.no_songs, R.string.no_songs_detail),
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
    val albumLabel = stringResource(R.string.album)
    val albums = remember(state.browse.tracks) { libraryBrowseAlbums(state.browse.tracks) }
    val currentTrackId = state.player.trackId
    val playing = state.player.playing
    val enabled = state.playbackControlsEnabled
    val listState = key(selected.id) { rememberLazyListState() }
    LibraryPagination(listState, state.browse.tracksCursor,
        onPrevious = { viewModel.loadPreviousBrowse(LibraryBrowseTarget.Tracks) },
        onNext = { viewModel.loadMoreBrowse(LibraryBrowseTarget.Tracks) })
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (state.browse.tracksCursor.windowStart == 0) item(contentType = "heading") {
            TabletPaneHeading(
                title = selected.title,
                countLabel = pluralStringResource(
                    R.plurals.song_count,
                    state.browse.tracksCursor.total,
                    state.browse.tracksCursor.total,
                ),
                detail = selected.subtitle,
            )
        }
        if (state.browse.tracksCursor.isLoadingPrevious) item(contentType = "progress") { TabletProgress() }
        if (state.browse.tracksCursor.error != null) item(key = "grouped-page-error", contentType = "error") {
            LibraryPageError(state.browse.tracksCursor.error) { viewModel.retryBrowse(LibraryBrowseTarget.Tracks) }
        }
        if (state.browse.tracks.isEmpty() && state.browse.tracksCursor.error == null) item(contentType = "empty") {
            Text(stringResource(R.string.no_songs), color = TunesLinkTheme.colors.secondaryText)
        }
        items(albums, key = { it.heading.key }, contentType = { "album-group" }) { album ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                TabletArtwork(
                    artworkId = album.heading.artworkId,
                    viewModel = viewModel,
                    maxSize = 256,
                    cornerRadius = TunesLinkShapes.artworkMedium,
                    modifier = Modifier.size(132.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        album.heading.album.ifBlank { albumLabel },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = TunesLinkTheme.colors.primaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    album.songs.forEach { song ->
                        TabletTrackRow(
                            track = song.track,
                            position = song.position,
                            current = currentTrackId == song.track.id,
                            playing = playing,
                            enabled = enabled,
                            onPlay = { viewModel.playTrack(song.track, selected) },
                            showColumns = false,
                        )
                    }
                }
            }
        }
        if (state.browse.tracksCursor.error != null &&
            (state.browse.tracksCursor.hasMore || state.browse.tracksCursor.hasPrevious)
        ) item(key = "grouped-next-error", contentType = "error") {
            LibraryPageError(state.browse.tracksCursor.error) { viewModel.retryBrowse(LibraryBrowseTarget.Tracks) }
        }
        if (state.browse.tracksCursor.isLoadingMore) item(contentType = "progress") { TabletProgress() }
    }
}

@Composable
private fun TabletSongsPane(
    title: String,
    tracks: List<TrackUiState>,
    cursor: LibraryPageCursor,
    onLoadPrevious: () -> Unit,
    onLoadMore: () -> Unit,
    state: TunesLinkUiState,
    viewModel: TunesLinkViewModel,
    onRetry: () -> Unit,
    emptyCopy: LibraryEmptyCopy,
    collection: SelectedLibraryCollection? = null,
    listIdentity: String = collection?.id.orEmpty(),
    showHeading: Boolean = true,
    loadingCopy: LibraryEmptyCopy = LibraryEmptyCopy(R.string.loading_library, R.string.loading_library_detail),
    countPlural: Int = R.plurals.song_count,
) {
    val listState = key(listIdentity) { rememberLazyListState() }
    LibraryPagination(listState, cursor, onLoadPrevious, onLoadMore)
    val error = cursor.error
    val currentTrackId = state.player.trackId
    val playing = state.player.playing
    val enabled = state.playbackControlsEnabled
    when {
        cursor.isLoading && tracks.isEmpty() -> ContentState(
            stringResource(loadingCopy.title),
            stringResource(loadingCopy.detail),
            loading = true,
            modifier = Modifier.fillMaxSize(),
        )
        error != null && tracks.isEmpty() -> ContentState(
            stringResource(R.string.library_unavailable),
            error,
            onRetry = onRetry,
            modifier = Modifier.fillMaxSize(),
        )
        tracks.isEmpty() -> ContentState(
            stringResource(emptyCopy.title),
            stringResource(emptyCopy.detail),
            modifier = Modifier.fillMaxSize(),
        )
        else -> BoxWithConstraints(Modifier.fillMaxSize()) {
            val showFullColumns = showHeading && maxWidth >= 620.dp
            Column(Modifier.fillMaxSize()) {
                if (showHeading) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                        TabletPaneHeading(title, pluralStringResource(countPlural, cursor.total, cursor.total))
                    }
                    Box(Modifier.padding(horizontal = if (showFullColumns) 20.dp else 12.dp)) {
                        TabletSongTableHeader(showFullColumns)
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(
                        horizontal = if (showFullColumns) 20.dp else 12.dp,
                        vertical = 16.dp,
                    ),
                ) {
                    if (cursor.isLoadingPrevious) item(contentType = "progress") { TabletProgress() }
                    if (cursor.hasPrevious && error != null) item(key = "songs-previous-error", contentType = "error") {
                        LibraryPageError(error, onRetry)
                    }
                    itemsIndexed(
                        tracks,
                        key = { _, track -> track.id },
                        contentType = { _, _ -> "track" },
                    ) { index, track ->
                        TabletTrackRow(
                            track = track,
                            position = track.trackNumber.takeIf { it > 0 } ?: cursor.windowStart + index + 1,
                            current = currentTrackId == track.id,
                            playing = playing,
                            enabled = enabled,
                            onPlay = { viewModel.playTrack(track, collection) },
                            showColumns = showFullColumns,
                            showMetadataUnderTitle = showHeading && !showFullColumns,
                            striped = index % 2 == 1,
                        )
                    }
                    item(key = "songs-page-error", contentType = "error") { LibraryPageError(error, onRetry) }
                    if (cursor.isLoadingMore || cursor.isLoading) item(contentType = "progress") { TabletProgress() }
                }
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
            color = TunesLinkTheme.colors.secondaryText, textAlign = TextAlign.End,
            modifier = Modifier.width(62.dp))
    }
}

/**
 * Rows take only what they display, so a playback update re-renders the current row rather
 * than every row in the table.
 */
@Composable
private fun TabletTrackRow(
    track: TrackUiState,
    position: Int,
    current: Boolean,
    playing: Boolean,
    enabled: Boolean,
    onPlay: () -> Unit,
    showColumns: Boolean,
    showMetadataUnderTitle: Boolean = false,
    striped: Boolean = false,
) {
    val emphasis = if (enabled) 1f else DISABLED_ROW_TEXT_ALPHA
    val secondary = TunesLinkTheme.colors.secondaryText.copy(alpha = emphasis)
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
                onClick = onPlay,
            )
            .semantics { selected = current }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(42.dp), contentAlignment = Alignment.CenterStart) {
            if (current) {
                NowPlayingIndicator(
                    playing = playing,
                    description = stringResource(if (playing) R.string.now_playing else R.string.current_track),
                    tint = TunesLinkTheme.colors.accentText.copy(alpha = emphasis),
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Text(
                    position.toString(),
                    style = MaterialTheme.typography.bodyMedium.tabularNumerals(),
                    color = secondary,
                )
            }
        }
        Column(Modifier.weight(if (showColumns) 2f else 1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = (if (current) TunesLinkTheme.colors.accentText else TunesLinkTheme.colors.primaryText)
                    .copy(alpha = emphasis),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showMetadataUnderTitle) {
                Text(
                    listOf(track.artist, track.album).filter(String::isNotBlank).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showColumns) {
            Text(
                track.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1.1f).padding(start = 12.dp),
            )
            Text(
                track.album,
                style = MaterialTheme.typography.bodyMedium,
                color = secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1.1f).padding(start = 12.dp),
            )
        }
        val spoken = spokenDuration(track.duration)
        Text(
            formatTime(track.duration),
            style = MaterialTheme.typography.labelMedium.tabularNumerals(),
            color = secondary,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier
                .width(62.dp)
                .padding(start = 8.dp)
                .semantics { contentDescription = spoken },
        )
    }
}

@Composable
private fun TabletSearchPane(state: TunesLinkUiState, viewModel: TunesLinkViewModel) {
    val library = state.library
    // Results must stay scrollable above the keyboard under edge-to-edge.
    Box(Modifier.fillMaxSize().imePadding()) {
        when {
            library.editingQuery.isBlank() -> ContentState(
                stringResource(R.string.search_your_music),
                stringResource(R.string.search_your_music_detail),
                modifier = Modifier.fillMaxSize(),
            )
            library.awaitingConnection -> ContentState(
                stringResource(R.string.search_waiting_title),
                stringResource(R.string.search_waiting_detail),
                modifier = Modifier.fillMaxSize(),
            )
            else -> TabletSongsPane(
                title = stringResource(R.string.search_results),
                tracks = library.items,
                cursor = library.pageCursor(),
                listIdentity = library.loadedQuery.orEmpty(),
                onLoadPrevious = viewModel::loadPrevious,
                onLoadMore = viewModel::loadMore,
                state = state,
                viewModel = viewModel,
                onRetry = viewModel::retrySearch,
                emptyCopy = LibraryEmptyCopy(R.string.no_songs_found, R.string.no_songs_found_detail),
                loadingCopy = LibraryEmptyCopy(R.string.searching, R.string.searching_detail),
                countPlural = R.plurals.result_count,
            )
        }
    }
}

/** Grid and list artwork is decorative; the card or row already speaks its title. */
@Composable
private fun TabletArtwork(
    artworkId: String,
    viewModel: TunesLinkViewModel,
    maxSize: Int,
    modifier: Modifier = Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp = TunesLinkShapes.artworkSmall,
) {
    val artwork = rememberLibraryArtwork(artworkId, maxSize, viewModel)
    ArtworkSurface(
        bitmap = artwork,
        description = null,
        modifier = modifier,
        cornerRadius = cornerRadius,
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

private fun LibraryBrowseKind.selectionHint(): Int = when (this) {
    LibraryBrowseKind.Artists -> R.string.select_artist_hint
    LibraryBrowseKind.Genres -> R.string.select_genre_hint
    LibraryBrowseKind.Playlists,
    LibraryBrowseKind.Albums,
    LibraryBrowseKind.Songs,
    -> R.string.select_playlist_hint
}

private fun LibraryBrowseKind.tabletIcon(): ImageVector = when (this) {
    LibraryBrowseKind.Playlists -> Icons.AutoMirrored.Rounded.PlaylistPlay
    LibraryBrowseKind.Artists -> Icons.Rounded.Person
    LibraryBrowseKind.Albums -> Icons.Rounded.Album
    LibraryBrowseKind.Songs -> Icons.Rounded.MusicNote
    LibraryBrowseKind.Genres -> Icons.Rounded.Equalizer
}
