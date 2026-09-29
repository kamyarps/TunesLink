package com.kamyarps.tuneslink

import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.focusTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.graphics.get


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
    val showingTracks = browse.showingTracks
    val browseError = browse.error
    val collectionsListState = key(browse.albumParent?.id ?: browse.kind?.name ?: "root") {
        rememberLazyListState(browse.collectionScrollIndex, browse.collectionScrollOffset)
    }
    val tracksListState = key(browseIdentity) { rememberLazyListState() }
    val listState = if (showingTracks) tracksListState else collectionsListState
    val groupTracksByAlbum = browse.groupsTracksByAlbum()
    val trackRows = remember(browse.tracks, groupTracksByAlbum) {
        if (groupTracksByAlbum) libraryBrowseRows(browse.tracks) else emptyList()
    }

    LibraryPagination(listState, browse.visibleCursor,
        onPrevious = { viewModel.loadPreviousBrowse(browse.visibleTarget) },
        onNext = { viewModel.loadMoreBrowse(browse.visibleTarget) })

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (browse.canNavigateUp) {
                IconButton(onClick = { viewModel.navigateUpLibrary() }) {
                    Icon(TuneLinkIcons.ArrowBack, stringResource(R.string.back))
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Text(
                browse.selectedCollection?.title ?: browse.albumParent?.title ?: browse.kind?.let { it.displayName() }
                    ?: stringResource(R.string.library),
                style = MaterialTheme.typography.headlineLarge,
                color = TunesLinkTheme.colors.primaryText,
                modifier = Modifier.weight(1f).semantics { heading() },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(TunesLinkSpacing.small))
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
                items(LibraryBrowseKind.entries.size, contentType = { "category" }) { index ->
                    val kind = LibraryBrowseKind.entries[index]
                    LibraryCategoryRow(kind, onClick = { viewModel.openLibraryKind(kind) })
                }
            }
            browse.isLoading && browse.visibleItemsEmpty ->
                ContentState(
                    stringResource(R.string.loading_library),
                    stringResource(R.string.loading_library_detail),
                    loading = true,
                    modifier = Modifier.fillMaxSize(),
                )
            browseError != null && browse.visibleItemsEmpty -> ContentState(
                stringResource(R.string.library_unavailable),
                browseError,
                onRetry = { viewModel.retryBrowse(browse.visibleTarget) },
                modifier = Modifier.fillMaxSize(),
            )
            !showingTracks && browse.collections.isEmpty() -> {
                val empty = if (browse.albumParent != null) {
                    LibraryEmptyCopy(R.string.no_albums, R.string.no_albums_detail)
                } else {
                    browse.kind.emptyCopy()
                }
                ContentState(
                    stringResource(empty.title),
                    stringResource(empty.detail),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            showingTracks && browse.tracks.isEmpty() -> {
                val empty = if (browse.selectedCollection == null) {
                    LibraryBrowseKind.Songs.emptyCopy()
                } else {
                    LibraryEmptyCopy(R.string.no_songs, R.string.no_songs_detail)
                }
                ContentState(
                    stringResource(empty.title),
                    stringResource(empty.detail),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                if (browse.visibleCursor.windowStart == 0) item(contentType = "count") {
                    Text(
                        if (showingTracks) {
                            pluralStringResource(R.plurals.song_count, browse.total, browse.total)
                        } else {
                            libraryCountLabel(browse.kind, browse.total)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = TunesLinkTheme.colors.secondaryText,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                if (browse.isLoadingPrevious) {
                    item(contentType = "progress") { LibraryPageProgress() }
                }
                if (browse.visibleCursor.hasPrevious && browseError != null) {
                    item(key = "browse-previous-error", contentType = "error") {
                        LibraryPageError(browseError) { viewModel.retryBrowse(browse.visibleTarget) }
                    }
                }
                if (showingTracks && groupTracksByAlbum) {
                    items(
                        trackRows,
                        key = LibraryBrowseRow::key,
                        contentType = { row -> if (row is LibraryBrowseRow.AlbumHeading) "album-heading" else "track" },
                    ) { row ->
                        when (row) {
                            is LibraryBrowseRow.AlbumHeading ->
                                LibraryAlbumHeading(row, viewModel)
                            is LibraryBrowseRow.Song -> TrackRow(
                                row.track,
                                viewModel,
                                enabled = state.playbackControlsEnabled,
                                pending = state.player.pending(PlaybackAction.PlayTrack) != null &&
                                    state.player.trackId == row.track.id,
                                current = state.player.trackId == row.track.id,
                                playing = state.player.playing,
                                onClick = { viewModel.playTrack(row.track, browse.selectedCollection) },
                            )
                        }
                    }
                } else if (showingTracks) {
                    itemsIndexed(
                        browse.tracks,
                        key = { _, track -> track.id },
                        contentType = { _, _ -> "track" },
                    ) { _, track ->
                        TrackRow(
                            track,
                            viewModel,
                            enabled = state.playbackControlsEnabled,
                            pending = state.player.pending(PlaybackAction.PlayTrack) != null &&
                                state.player.trackId == track.id,
                            current = state.player.trackId == track.id,
                            playing = state.player.playing,
                            onClick = { viewModel.playTrack(track, browse.selectedCollection) },
                        )
                    }
                } else {
                    itemsIndexed(
                        browse.collections,
                        key = { _, collection -> collection.id },
                        contentType = { _, _ -> "collection" },
                    ) { _, collection ->
                        LibraryCollectionRow(collection, viewModel) {
                            viewModel.openPhoneLibraryCollection(collection,
                                listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                        }
                    }
                }
                item(key = "browse-page-error", contentType = "error") {
                    LibraryPageError(browseError) { viewModel.retryBrowse(browse.visibleTarget) }
                }
                if (browse.isLoadingMore) {
                    item(contentType = "progress") { LibraryPageProgress() }
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
private fun CollectionArtwork(collection: LibraryCollectionUiState, viewModel: TunesLinkViewModel) =
    BrowseArtwork(collection.artworkId, viewModel)

@Composable
private fun BrowseArtwork(artworkId: String, viewModel: TunesLinkViewModel) {
    val artwork = rememberLibraryArtwork(artworkId, 128, viewModel)
    // Row artwork is decorative: the row already speaks its title.
    ArtworkSurface(artwork, description = null, Modifier.size(TunesLinkSizes.compactArtwork))
}

@Composable
private fun LibraryAlbumHeading(
    heading: LibraryBrowseRow.AlbumHeading,
    viewModel: TunesLinkViewModel,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 4.dp)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrowseArtwork(heading.artworkId, viewModel)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                heading.album,
                style = MaterialTheme.typography.titleMedium,
                color = TunesLinkTheme.colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                pluralStringResource(R.plurals.song_count, heading.trackCount, heading.trackCount),
                style = MaterialTheme.typography.labelMedium,
                color = TunesLinkTheme.colors.secondaryText,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun LibraryBrowseKind.displayName(): String = when (this) {
    LibraryBrowseKind.Playlists -> stringResource(R.string.playlists)
    LibraryBrowseKind.Artists -> stringResource(R.string.artists)
    LibraryBrowseKind.Albums -> stringResource(R.string.albums)
    LibraryBrowseKind.Songs -> stringResource(R.string.songs)
    LibraryBrowseKind.Genres -> stringResource(R.string.genres)
}

internal data class LibraryEmptyCopy(val title: Int, val detail: Int)

/** Empty state for a whole library category (not a single collection). */
internal fun LibraryBrowseKind?.emptyCopy(): LibraryEmptyCopy = when (this) {
    LibraryBrowseKind.Playlists -> LibraryEmptyCopy(R.string.no_playlists, R.string.no_playlists_detail)
    LibraryBrowseKind.Artists -> LibraryEmptyCopy(R.string.no_artists, R.string.no_artists_detail)
    LibraryBrowseKind.Albums, null -> LibraryEmptyCopy(R.string.no_albums, R.string.no_albums_library_detail)
    LibraryBrowseKind.Songs -> LibraryEmptyCopy(R.string.no_songs, R.string.no_songs_library_detail)
    LibraryBrowseKind.Genres -> LibraryEmptyCopy(R.string.no_genres, R.string.no_genres_detail)
}

internal fun LibraryBrowseKind?.countPlural(): Int = when (this) {
    LibraryBrowseKind.Playlists -> R.plurals.playlist_count
    LibraryBrowseKind.Artists -> R.plurals.artist_count
    LibraryBrowseKind.Albums -> R.plurals.album_count
    LibraryBrowseKind.Songs -> R.plurals.song_count
    LibraryBrowseKind.Genres -> R.plurals.genre_count
    null -> R.plurals.result_count
}

@Composable
internal fun libraryCountLabel(kind: LibraryBrowseKind?, total: Int): String =
    pluralStringResource(kind.countPlural(), total, total)

private fun LibraryBrowseKind.icon() = when (this) {
    LibraryBrowseKind.Playlists -> TuneLinkIcons.PlaylistPlay
    LibraryBrowseKind.Artists -> TuneLinkIcons.Person
    LibraryBrowseKind.Albums -> TuneLinkIcons.Album
    LibraryBrowseKind.Songs -> TuneLinkIcons.MusicNote
    LibraryBrowseKind.Genres -> TuneLinkIcons.Equalizer
}

@Composable
internal fun SearchScreen(state: TunesLinkUiState, viewModel: TunesLinkViewModel, modifier: Modifier) {
    val library = state.library
    val searchIdentity = library.loadedQuery?.trim()?.lowercase().orEmpty()
    val listState = key(searchIdentity) { rememberLazyListState() }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val dismissSearchFocus = {
        // Clearing all focus can return it to the first text field on Android 6.
        // Keep focus on the search container while dismissing its input method.
        searchFocusRequester.requestFocus()
        keyboardController?.hide()
    }

    LibraryPagination(listState, library.pageCursor(),
        onPrevious = viewModel::loadPrevious, onNext = viewModel::loadMore)

    // The container is a focus parking spot (see dismissSearchFocus), not an accessibility stop.
    // imePadding keeps the last results scrollable above the keyboard under edge-to-edge.
    Column(modifier.focusRequester(searchFocusRequester).focusTarget().imePadding()) {
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
                    dismissSearchFocus()
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
            leadingIcon = { Icon(TuneLinkIcons.Search, contentDescription = null) },
            trailingIcon = {
                if (library.editingQuery.isNotEmpty()) {
                    IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                        Icon(TuneLinkIcons.Clear, stringResource(R.string.clear_search))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = tunesLinkTextFieldColors(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { dismissSearchFocus() }),
        )
        Spacer(Modifier.height(8.dp))
        when {
            library.isRefreshing && library.items.isEmpty() -> if (library.editingQuery.isBlank()) {
                ContentState(
                    stringResource(R.string.loading_library),
                    stringResource(R.string.songs_from_itunes_detail),
                    loading = true,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                ContentState(
                    stringResource(R.string.searching),
                    stringResource(R.string.searching_detail),
                    loading = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            library.awaitingConnection -> ContentState(
                stringResource(R.string.search_waiting_title),
                stringResource(R.string.search_waiting_detail),
                modifier = Modifier.fillMaxSize(),
            )
            library.error != null && library.items.isEmpty() -> ContentState(
                stringResource(R.string.library_unavailable),
                library.error,
                onRetry = viewModel::retrySearch,
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
                if (library.windowStart == 0) item(contentType = "count") {
                    Text(
                        pluralStringResource(R.plurals.result_count, library.total, library.total),
                        style = MaterialTheme.typography.labelMedium,
                        color = TunesLinkTheme.colors.secondaryText,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                if (library.isLoadingPrevious) {
                    item(contentType = "progress") { LibraryPageProgress() }
                }
                if (library.error != null && library.errorDirection == PageDirection.Previous) {
                    item(key = "search-previous-error", contentType = "error") {
                        LibraryPageError(library.error, viewModel::retrySearch)
                    }
                }
                itemsIndexed(
                    library.items,
                    key = { _, track -> track.id },
                    contentType = { _, _ -> "track" },
                ) { _, track ->
                    TrackRow(
                        track,
                        viewModel = viewModel,
                        enabled = state.playbackControlsEnabled,
                        pending = state.player.pending(PlaybackAction.PlayTrack) != null &&
                            state.player.trackId == track.id,
                        current = state.player.trackId == track.id,
                        playing = state.player.playing,
                        onClick = { viewModel.playTrack(track) },
                    )
                }
                if (library.errorDirection != PageDirection.Previous) {
                    item(key = "search-page-error", contentType = "error") {
                        LibraryPageError(library.error, viewModel::retrySearch)
                    }
                }
                if (library.isLoadingMore) {
                    item(contentType = "progress") { LibraryPageProgress() }
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
    val showDetails = stringResource(R.string.show_connection_details)
    Row(
        modifier = Modifier
            // Long PC names ("DESKTOP-7Q2M4KD") ellipsize instead of starving the page title.
            .widthIn(max = TunesLinkSizes.connectionChipMaxWidth)
            .clip(RoundedCornerShape(TunesLinkShapes.control))
            .clickable(
                role = Role.Button,
                onClickLabel = showDetails,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = accessibleName
            }
            .heightIn(min = TunesLinkSizes.minimumTarget)
            .padding(horizontal = TunesLinkSpacing.small, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (connected) TuneLinkIcons.Computer else TuneLinkIcons.WifiOff,
            null,
            tint = statusColor,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(TunesLinkSpacing.small))
        Column(Modifier.weight(1f, fill = false)) {
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
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(trailingChevronIcon(), null, tint = TunesLinkTheme.colors.secondaryText, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun TrackArtwork(track: TrackUiState, viewModel: TunesLinkViewModel) =
    BrowseArtwork(track.artworkId, viewModel)

@Composable
private fun trailingChevronIcon(): ImageVector =
    if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
        TuneLinkIcons.ChevronLeft
    } else {
        TuneLinkIcons.ChevronRight
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
    val emphasis = if (enabled) 1f else DISABLED_ROW_TEXT_ALPHA
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
                color = (if (current) TunesLinkTheme.colors.accentText else TunesLinkTheme.colors.primaryText)
                    .copy(alpha = emphasis),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(track.artist, track.album).filter(String::isNotBlank).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = TunesLinkTheme.colors.secondaryText.copy(alpha = emphasis),
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
            NowPlayingIndicator(
                playing = playing,
                description = stringResource(
                    if (playing) R.string.now_playing else R.string.current_track,
                ),
                tint = TunesLinkTheme.colors.accentText.copy(alpha = emphasis),
                modifier = Modifier.size(20.dp),
            )
        } else {
            val spoken = spokenDuration(track.duration)
            Text(
                formatTime(track.duration),
                style = MaterialTheme.typography.labelMedium.tabularNumerals(),
                color = TunesLinkTheme.colors.secondaryText.copy(alpha = emphasis),
                textAlign = TextAlign.End,
                modifier = Modifier.semantics { contentDescription = spoken },
            )
        }
    }
}
