package com.kamyarps.tuneslink

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.graphics.get
import kotlinx.coroutines.flow.distinctUntilChanged


@Composable
internal fun LibraryBrowseScreen(
    state: TunesLinkUiState,
    viewModel: TunesLinkViewModel,
    modifier: Modifier,
) {
    val browse = state.browse
    val browseIdentity = browse.selectedCollection?.let { "${it.kind.name}:${it.id}" }
        ?: browse.kind?.name
        ?: "root"
    val listState = key(browseIdentity) { rememberLazyListState() }
    val showingTracks = browse.kind == LibraryBrowseKind.Songs || browse.selectedCollection != null

    LaunchedEffect(listState, browse.collections.size, browse.tracks.size, showingTracks) {
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            (visible.firstOrNull()?.index ?: 0) to (visible.lastOrNull()?.index ?: 0)
        }
            .distinctUntilChanged()
            .collect { (firstVisibleIndex, lastVisibleIndex) ->
                val lastDataIndex = if (showingTracks) browse.tracks.lastIndex else browse.collections.lastIndex
                if (firstVisibleIndex <= 6) viewModel.loadPreviousBrowse()
                if (lastVisibleIndex >= lastDataIndex - 6) viewModel.loadMoreBrowse()
            }
    }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (browse.canNavigateUp) {
                IconButton(onClick = { viewModel.navigateUpLibrary() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back))
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Text(
                browse.selectedCollection?.title ?: browse.kind?.let { it.displayName() }
                    ?: stringResource(R.string.library),
                style = MaterialTheme.typography.headlineLarge,
                color = TunesLinkTheme.colors.primaryText,
                modifier = Modifier.weight(1f).semantics { heading() },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ComputerConnectionAction(state.bridgeName, state.connection, viewModel::showConnectionDetails)
        }

        when {
            browse.kind == null -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.library_categories),
                        style = MaterialTheme.typography.labelMedium,
                        color = TunesLinkTheme.colors.secondaryText,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                items(LibraryBrowseKind.entries.size) { index ->
                    val kind = LibraryBrowseKind.entries[index]
                    LibraryCategoryRow(kind, onClick = { viewModel.openLibraryKind(kind) })
                }
            }
            browse.isLoading -> ContentState(
                stringResource(R.string.loading_library),
                stringResource(R.string.loading_library_detail),
                loading = true,
                modifier = Modifier.fillMaxSize(),
            )
            browse.error != null && browse.collections.isEmpty() && browse.tracks.isEmpty() -> ContentState(
                stringResource(R.string.library_unavailable),
                browse.error,
                onRetry = {
                    val selected = browse.selectedCollection
                    if (selected != null) {
                        viewModel.openLibraryCollection(
                            LibraryCollectionUiState(
                                selected.id,
                                selected.title,
                                selected.subtitle,
                                0,
                                "",
                            ),
                        )
                    } else {
                        browse.kind.let(viewModel::openLibraryKind)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            showingTracks && browse.tracks.isEmpty() -> ContentState(
                stringResource(R.string.no_songs),
                stringResource(R.string.no_songs_detail),
                modifier = Modifier.fillMaxSize(),
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                item {
                    Text(
                        if (showingTracks) {
                            pluralStringResource(R.plurals.song_count, browse.total, browse.total)
                        } else {
                            pluralStringResource(R.plurals.result_count, browse.total, browse.total)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = TunesLinkTheme.colors.secondaryText,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                if (browse.isLoadingPrevious) {
                    item { LibraryPageProgress() }
                }
                if (showingTracks) {
                    itemsIndexed(browse.tracks, key = { _, track -> track.id }) { _, track ->
                        TrackRow(
                            track,
                            viewModel,
                            enabled = ConnectionAvailability.from(state.connection).controlsEnabled &&
                                !state.player.hasPendingConflict(PlaybackAction.PlayTrack),
                            pending = state.player.pending(PlaybackAction.PlayTrack) != null &&
                                state.player.trackId == track.id,
                            current = state.player.trackId == track.id,
                            playing = state.player.playing,
                            onClick = { viewModel.playTrack(track, browse.selectedCollection) },
                        )
                    }
                } else {
                    itemsIndexed(browse.collections, key = { _, collection -> collection.id }) { _, collection ->
                        LibraryCollectionRow(collection, viewModel) {
                            viewModel.openLibraryCollection(collection)
                        }
                    }
                }
                if (browse.isLoadingMore) {
                    item { LibraryPageProgress() }
                }
            }
        }
    }
}

@Composable
private fun LibraryCategoryRow(kind: LibraryBrowseKind, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(kind.icon(), null, tint = TunesLinkTheme.colors.accentText, modifier = Modifier.size(25.dp))
        Spacer(Modifier.width(16.dp))
        Text(
            kind.displayName(),
            style = MaterialTheme.typography.titleLarge,
            color = TunesLinkTheme.colors.primaryText,
            modifier = Modifier.weight(1f),
        )
        Icon(trailingChevronIcon(), null, tint = TunesLinkTheme.colors.secondaryText)
    }
}

@Composable
private fun LibraryCollectionRow(
    collection: LibraryCollectionUiState,
    viewModel: TunesLinkViewModel,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CollectionArtwork(collection, viewModel)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                collection.title,
                style = MaterialTheme.typography.bodyLarge,
                color = TunesLinkTheme.colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(
                    collection.subtitle,
                    pluralStringResource(R.plurals.song_count, collection.trackCount, collection.trackCount),
                ).filter(String::isNotBlank).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = TunesLinkTheme.colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(trailingChevronIcon(), null, tint = TunesLinkTheme.colors.secondaryText)
    }
}

@Composable
private fun CollectionArtwork(collection: LibraryCollectionUiState, viewModel: TunesLinkViewModel) {
    var artwork by remember(collection.artworkId) { mutableStateOf<Bitmap?>(null) }
    DisposableEffect(collection.artworkId) {
        val request = viewModel.requestArtwork(collection.artworkId, 128, object : BridgeClient.Result<Bitmap> {
            override fun success(value: Bitmap?) { artwork = value }
            override fun failure(message: String, unauthorized: Boolean) = Unit
        })
        onDispose(request::cancel)
    }
    ArtworkSurface(
        artwork,
        if (artwork != null) stringResource(R.string.artwork_for, collection.title) else null,
        Modifier.size(TunesLinkSizes.compactArtwork),
    )
}

@Composable
private fun LibraryBrowseKind.displayName(): String = when (this) {
    LibraryBrowseKind.Playlists -> stringResource(R.string.playlists)
    LibraryBrowseKind.Artists -> stringResource(R.string.artists)
    LibraryBrowseKind.Albums -> stringResource(R.string.albums)
    LibraryBrowseKind.Songs -> stringResource(R.string.songs)
    LibraryBrowseKind.Genres -> stringResource(R.string.genres)
}

private fun LibraryBrowseKind.icon() = when (this) {
    LibraryBrowseKind.Playlists -> Icons.AutoMirrored.Rounded.PlaylistPlay
    LibraryBrowseKind.Artists -> Icons.Rounded.Person
    LibraryBrowseKind.Albums -> Icons.Rounded.Album
    LibraryBrowseKind.Songs -> Icons.Rounded.MusicNote
    LibraryBrowseKind.Genres -> Icons.Rounded.Equalizer
}

@Composable
internal fun SearchScreen(state: TunesLinkUiState, viewModel: TunesLinkViewModel, modifier: Modifier) {
    val library = state.library
    val searchIdentity = library.loadedQuery?.trim()?.lowercase().orEmpty()
    val listState = key(searchIdentity) { rememberLazyListState() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(listState, library.items.size) {
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            (visible.firstOrNull()?.index ?: 0) to (visible.lastOrNull()?.index ?: 0)
        }
            .distinctUntilChanged()
            .collect { (firstVisibleIndex, lastVisibleIndex) ->
                if (firstVisibleIndex <= 6) viewModel.loadPrevious()
                if (lastVisibleIndex >= library.items.lastIndex - 6) {
                    viewModel.loadMore()
                }
            }
        }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.search),
                style = MaterialTheme.typography.headlineLarge,
                color = TunesLinkTheme.colors.primaryText,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (!library.searchActive) {
                ComputerConnectionAction(state.bridgeName, state.connection, viewModel::showConnectionDetails)
            } else {
                TunesLinkTonalAction(stringResource(R.string.cancel), {
                    focusManager.clearFocus()
                    viewModel.cancelSearch()
                })
            }
        }
        OutlinedTextField(
            value = library.editingQuery,
            onValueChange = viewModel::updateSearchQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .onFocusChanged { viewModel.setSearchActive(it.isFocused) },
            placeholder = { Text(stringResource(R.string.search_library)) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (library.editingQuery.isNotEmpty()) {
                    IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                        Icon(Icons.Rounded.Clear, stringResource(R.string.clear_search))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = tunesLinkTextFieldColors(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        )
        Spacer(Modifier.height(8.dp))
        when {
            library.isRefreshing && library.items.isEmpty() -> ContentState(
                stringResource(R.string.loading_library),
                stringResource(R.string.songs_from_itunes_detail),
                loading = true,
                modifier = Modifier.fillMaxSize(),
            )
            library.error != null && library.items.isEmpty() -> ContentState(
                stringResource(R.string.library_unavailable),
                library.error,
                onRetry = viewModel::refreshLibrary,
                modifier = Modifier.fillMaxSize(),
            )
            library.loadedQuery != null && library.items.isEmpty() -> ContentState(
                if (library.loadedQuery.isEmpty()) {
                    stringResource(R.string.search_your_music)
                } else {
                    stringResource(R.string.no_songs_found)
                },
                if (library.loadedQuery.isEmpty()) {
                    stringResource(R.string.search_your_music_detail)
                } else {
                    stringResource(R.string.no_songs_found_detail)
                },
                modifier = Modifier.fillMaxSize(),
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                item {
                    Text(
                        pluralStringResource(R.plurals.result_count, library.total, library.total),
                        style = MaterialTheme.typography.labelMedium,
                        color = TunesLinkTheme.colors.secondaryText,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                if (library.isLoadingPrevious) {
                    item { LibraryPageProgress() }
                }
                itemsIndexed(library.items, key = { _, track -> track.id }) { _, track ->
                    TrackRow(
                        track,
                        viewModel = viewModel,
                        enabled = ConnectionAvailability.from(state.connection).controlsEnabled &&
                            !state.player.hasPendingConflict(PlaybackAction.PlayTrack),
                        pending = state.player.pending(PlaybackAction.PlayTrack) != null &&
                            state.player.trackId == track.id,
                        current = state.player.trackId == track.id,
                        playing = state.player.playing,
                        onClick = { viewModel.playTrack(track) },
                    )
                }
                if (library.isLoadingMore) {
                    item { LibraryPageProgress() }
                }
            }
        }
    }
}

@Composable
private fun LibraryPageProgress() {
    Box(Modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            color = TunesLinkTheme.colors.accentText,
            strokeWidth = 2.dp,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
internal fun ComputerConnectionAction(
    computer: String,
    connection: ConnectionState,
    onClick: () -> Unit,
) {
    val computerLabel = computer.ifBlank { stringResource(R.string.connection_default) }
    val availability = ConnectionAvailability.from(connection)
    val statusLabel = stringResource(availability.titleRes)
    val connected = connection is ConnectionState.Connected
    val connecting = connection is ConnectionState.Connecting
    val statusColor = when {
        connected -> TunesLinkTheme.colors.success
        connecting -> TunesLinkTheme.colors.secondaryText
        else -> TunesLinkTheme.colors.danger
    }
    val accessibleName = stringResource(
        R.string.connection_status_accessibility,
        computerLabel,
        statusLabel,
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(TunesLinkShapes.control))
            .clickable(
                role = Role.Button,
                onClickLabel = accessibleName,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = accessibleName
            }
            .height(TunesLinkSizes.minimumTarget)
            .padding(horizontal = TunesLinkSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (connected) Icons.Rounded.Computer else Icons.Rounded.WifiOff,
            null,
            tint = statusColor,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(TunesLinkSpacing.small))
        Column {
            Text(
                computerLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = if (connected) TunesLinkTheme.colors.secondaryText else statusColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!connected) {
                Text(
                    statusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    maxLines = 1,
                )
            }
        }
        Icon(trailingChevronIcon(), null, tint = TunesLinkTheme.colors.secondaryText, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun TrackArtwork(track: TrackUiState, viewModel: TunesLinkViewModel) {
    var artwork by remember(track.artworkId) { mutableStateOf<Bitmap?>(null) }
    DisposableEffect(track.artworkId) {
        val request = viewModel.requestArtwork(track.artworkId, 128, object : BridgeClient.Result<Bitmap> {
            override fun success(value: Bitmap?) {
                artwork = value
            }

            override fun failure(message: String, unauthorized: Boolean) = Unit
        })
        onDispose(request::cancel)
    }
    ArtworkSurface(
        artwork,
        if (artwork != null) stringResource(R.string.artwork_for, track.title) else null,
        Modifier.size(TunesLinkSizes.compactArtwork),
    )
}

@Composable
private fun trailingChevronIcon(): ImageVector =
    if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
        Icons.Rounded.ChevronLeft
    } else {
        Icons.Rounded.ChevronRight
    }

@Composable
private fun TrackRow(
    track: TrackUiState,
    viewModel: TunesLinkViewModel,
    enabled: Boolean,
    pending: Boolean,
    current: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
) {
    val startingPlaybackDescription = stringResource(R.string.starting_playback)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = stringResource(R.string.play_track, track.title),
                onClick = onClick,
            )
            .semantics { selected = current }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackArtwork(track, viewModel)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (current) TunesLinkTheme.colors.accentText else TunesLinkTheme.colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(track.artist, track.album).filter(String::isNotBlank).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = TunesLinkTheme.colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (pending) {
            CircularProgressIndicator(
                color = TunesLinkTheme.colors.accentText,
                strokeWidth = 2.dp,
                modifier = Modifier
                    .size(24.dp)
                    .semantics { contentDescription = startingPlaybackDescription },
            )
        } else if (current) {
            Icon(
                Icons.Rounded.Equalizer,
                contentDescription = stringResource(
                    if (playing) R.string.now_playing else R.string.current_track,
                ),
                tint = TunesLinkTheme.colors.accentText,
                modifier = Modifier.size(24.dp),
            )
        } else {
            Text(
                formatTime(track.duration),
                style = MaterialTheme.typography.labelMedium,
                color = TunesLinkTheme.colors.secondaryText,
            )
        }
    }
}
