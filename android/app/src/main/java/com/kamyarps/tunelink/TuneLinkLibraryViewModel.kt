package com.kamyarps.tuneslink

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun TunesLinkViewModel.setSearchActive(active: Boolean) {
    mutableState.update { it.copy(library = it.library.copy(searchActive = active)) }
}

internal fun TunesLinkViewModel.cancelSearch() {
    setSearchActive(false)
    updateSearchQuery("")
}

internal fun TunesLinkViewModel.updateSearchQuery(value: String) {
    savedStateHandle[TunesLinkViewModel.KEY_EDITING_QUERY] = value
    editingQuery.value = value
    mutableState.update { it.copy(library = it.library.copy(editingQuery = value)) }
}

internal fun TunesLinkViewModel.refreshLibrary() = loadLibrary(refresh = true)

internal fun TunesLinkViewModel.loadMore() {
    val library = mutableState.value.library
    if (!library.hasMore || library.isLoadingMore || library.isLoadingPrevious || library.isRefreshing) return
    val query = library.loadedQuery ?: return
    val generation = libraryGeneration
    mutableState.update { it.copy(library = it.library.copy(isLoadingMore = true, error = null)) }
    libraryRequest.cancel()
    val offset = library.windowStart + library.items.size
    libraryRequest = repository.getLibrary(query, offset, TunesLinkViewModel.PAGE_SIZE,
        libraryResult(query, generation, replace = false))
}

internal fun TunesLinkViewModel.loadPrevious() {
    val library = mutableState.value.library
    if (!library.hasPrevious || library.isLoadingMore || library.isLoadingPrevious || library.isRefreshing) return
    val query = library.loadedQuery ?: return
    val offset = (library.windowStart - TunesLinkViewModel.PAGE_SIZE).coerceAtLeast(0)
    val limit = library.windowStart - offset
    if (limit <= 0) return
    val generation = libraryGeneration
    mutableState.update { it.copy(library = it.library.copy(isLoadingPrevious = true, error = null)) }
    libraryRequest.cancel()
    libraryRequest = repository.getLibrary(query, offset, limit,
        libraryResult(query, generation, replace = false))
}

internal fun TunesLinkViewModel.openLibraryKind(kind: LibraryBrowseKind) {
    browseRequest.cancel()
    browseGeneration++
    val generation = browseGeneration
    mutableState.update {
        it.copy(
            browse = LibraryBrowseUiState(
                kind = kind,
                isLoading = true,
            ),
        )
    }
    if (kind == LibraryBrowseKind.Songs) {
        browseRequest = repository.getLibrary("", 0, TunesLinkViewModel.PAGE_SIZE,
            browseTracksResult(generation, replace = true))
    } else {
        browseRequest = repository.getCollections(
            kind.wireValue,
            "",
            0,
            TunesLinkViewModel.PAGE_SIZE,
            browseCollectionsResult(generation, replace = true),
        )
    }
}

internal fun TunesLinkViewModel.openLibraryCollection(collection: LibraryCollectionUiState) {
    val kind = mutableState.value.browse.kind ?: return
    if (kind == LibraryBrowseKind.Songs) return
    browseRequest.cancel()
    browseGeneration++
    val generation = browseGeneration
    mutableState.update {
        it.copy(
            browse = it.browse.copy(
                selectedCollection = SelectedLibraryCollection(
                    kind,
                    collection.id,
                    collection.title,
                    collection.subtitle,
                    it.browse.total,
                    it.browse.hasMore,
                    it.browse.windowStart,
                    it.browse.revision,
                ),
                tracks = emptyList(),
                total = 0,
                hasMore = false,
                hasPrevious = false,
                windowStart = 0,
                revision = "",
                isLoading = true,
                isLoadingMore = false,
                isLoadingPrevious = false,
                error = null,
            ),
        )
    }
    browseRequest = repository.getCollectionTracks(
        kind.wireValue,
        collection.id,
        "",
        0,
        TunesLinkViewModel.PAGE_SIZE,
        browseTracksResult(generation, replace = true),
    )
}

internal fun TunesLinkViewModel.navigateUpLibrary(): Boolean {
    val browse = mutableState.value.browse
    if (!browse.canNavigateUp) return false
    browseRequest.cancel()
    browseGeneration++
    mutableState.update {
        it.copy(
            browse = if (browse.selectedCollection != null) {
                browse.copy(
                    selectedCollection = null,
                    tracks = emptyList(),
                    total = browse.selectedCollection.parentTotal,
                    hasMore = browse.selectedCollection.parentHasMore,
                    hasPrevious = browse.selectedCollection.parentWindowStart > 0,
                    windowStart = browse.selectedCollection.parentWindowStart,
                    revision = browse.selectedCollection.parentRevision,
                    isLoading = false,
                    isLoadingMore = false,
                    isLoadingPrevious = false,
                    error = null,
                )
            } else {
                LibraryBrowseUiState()
            },
        )
    }
    return true
}

internal fun TunesLinkViewModel.loadMoreBrowse() {
    val browse = mutableState.value.browse
    if (!browse.hasMore || browse.isLoading || browse.isLoadingMore || browse.isLoadingPrevious) return
    val kind = browse.kind ?: return
    val generation = browseGeneration
    mutableState.update { it.copy(browse = it.browse.copy(isLoadingMore = true, error = null)) }
    val selected = browse.selectedCollection
    when {
        selected != null -> browseRequest = repository.getCollectionTracks(
            kind.wireValue,
            selected.id,
            "",
            browse.windowStart + browse.tracks.size,
            TunesLinkViewModel.PAGE_SIZE,
            browseTracksResult(generation, replace = false),
        )
        kind == LibraryBrowseKind.Songs -> browseRequest = repository.getLibrary(
            "",
            browse.windowStart + browse.tracks.size,
            TunesLinkViewModel.PAGE_SIZE,
            browseTracksResult(generation, replace = false),
        )
        else -> browseRequest = repository.getCollections(
            kind.wireValue,
            "",
            browse.windowStart + browse.collections.size,
            TunesLinkViewModel.PAGE_SIZE,
            browseCollectionsResult(generation, replace = false),
        )
    }
}

internal fun TunesLinkViewModel.loadPreviousBrowse() {
    val browse = mutableState.value.browse
    if (!browse.hasPrevious || browse.isLoading || browse.isLoadingMore || browse.isLoadingPrevious) return
    val kind = browse.kind ?: return
    val offset = (browse.windowStart - TunesLinkViewModel.PAGE_SIZE).coerceAtLeast(0)
    val limit = browse.windowStart - offset
    if (limit <= 0) return
    val generation = browseGeneration
    mutableState.update { it.copy(browse = it.browse.copy(isLoadingPrevious = true, error = null)) }
    val selected = browse.selectedCollection
    browseRequest.cancel()
    browseRequest = when {
        selected != null -> repository.getCollectionTracks(
            kind.wireValue, selected.id, "", offset, limit,
            browseTracksResult(generation, replace = false),
        )
        kind == LibraryBrowseKind.Songs -> repository.getLibrary(
            "", offset, limit, browseTracksResult(generation, replace = false),
        )
        else -> repository.getCollections(
            kind.wireValue, "", offset, limit,
            browseCollectionsResult(generation, replace = false),
        )
    }
}

internal fun TunesLinkViewModel.playTrack(
    track: TrackUiState,
    collection: SelectedLibraryCollection? = null,
) {
    val previous = mutableState.value.player
    if (previous.hasPendingConflict(PlaybackAction.PlayTrack)) return
    val mutation = pendingMutation(
        action = PlaybackAction.PlayTrack,
        previous = previous,
        expectedTrackId = track.id,
    )
    mutableState.update {
        it.copy(
            player = previous.copy(
                playing = true,
                title = track.title,
                artist = track.artist,
                album = track.album,
                duration = track.duration,
                position = 0.0,
                artworkId = track.artworkId,
                trackId = track.id,
                artworkState = if (track.artworkId.isBlank()) {
                    ArtworkLoadState.Missing
                } else {
                    ArtworkLoadState.Loading(previous.artwork)
                },
                pendingMutations = previous.pendingMutations + (PlaybackAction.PlayTrack to mutation),
                commandError = null,
            ),
        )
    }
    loadArtwork(track.artworkId)
    scheduleMutationReconciliation(mutation)
    repository.playTrack(
        track.id,
        collection?.kind?.wireValue.orEmpty(),
        collection?.id.orEmpty(),
        commandResult(
            mutation = mutation,
            rollback = previous,
            failureRes = R.string.could_not_play,
            failureArguments = listOf(track.title),
        ),
    )
}


internal fun TunesLinkViewModel.commitSearch(query: String) {
    libraryRequest.cancel()
    val normalized = query.trim()
    if (mutableState.value.connection !is ConnectionState.Connected) {
        mutableState.update {
            it.copy(
                library = it.library.copy(
                    loadedQuery = null,
                    isRefreshing = false,
                    isLoadingMore = false,
                ),
            )
        }
        return
    }
    libraryGeneration++
    if (normalized.isEmpty()) {
        mutableState.update {
            it.copy(
                library = it.library.copy(
                    items = emptyList(),
                    total = 0,
                    hasMore = false,
                    hasPrevious = false,
                    windowStart = 0,
                    revision = "",
                    loadedQuery = "",
                    isRefreshing = false,
                    isLoadingMore = false,
                    isLoadingPrevious = false,
                    error = null,
                ),
            )
        }
        return
    }
    val generation = libraryGeneration
    mutableState.update {
        it.copy(
            library = it.library.copy(
                loadedQuery = normalized,
                isRefreshing = true,
                isLoadingMore = false,
                error = null,
            ),
        )
    }
    libraryRequest = repository.getLibrary(normalized, 0, TunesLinkViewModel.PAGE_SIZE,
        libraryResult(normalized, generation, replace = true))
}

internal fun TunesLinkViewModel.loadLibrary(refresh: Boolean) {
    val library = mutableState.value.library
    if (!refresh && library.loadedQuery != null) return
    commitSearch(library.editingQuery)
}

internal fun TunesLinkViewModel.libraryResult(query: String, generation: Int, replace: Boolean) =
    object : BridgeClient.Result<BridgeClient.LibraryPage> {
        override fun success(value: BridgeClient.LibraryPage) {
            val current = mutableState.value.library
            if (generation != libraryGeneration || current.loadedQuery != query) return
            val converted = value.items.map {
                it.toUiState()
            }
            mutableState.update { state ->
                val revisionChanged = state.library.revision.isNotBlank()
                    && value.revision.isNotBlank() && state.library.revision != value.revision
                val window = mergePageWindow(
                    state.library.items,
                    state.library.windowStart,
                    converted,
                    value.offset,
                    replace || revisionChanged,
                    TunesLinkViewModel.MAX_LIBRARY_WINDOW_ITEMS,
                )
                state.copy(
                    library = state.library.copy(
                        items = window.items,
                        windowStart = window.startOffset,
                        revision = if (value.revision.isNotBlank() || replace || revisionChanged) {
                            value.revision
                        } else state.library.revision,
                        total = value.total,
                        hasMore = window.startOffset + window.items.size < value.total,
                        hasPrevious = window.startOffset > 0,
                        isRefreshing = false,
                        isLoadingMore = false,
                        isLoadingPrevious = false,
                        error = null,
                    ),
                )
            }
            announcePlural(R.plurals.result_count, value.total, listOf(value.total))
        }

        override fun failure(message: String, unauthorized: Boolean) {
            if (generation != libraryGeneration) return
            mutableState.update {
                it.copy(
                    library = it.library.copy(
                        isRefreshing = false,
                        isLoadingMore = false,
                        isLoadingPrevious = false,
                        error = localizedFailure(message, R.string.error_library_unavailable),
                    ),
                )
            }
        }
    }

internal fun TunesLinkViewModel.browseCollectionsResult(generation: Int, replace: Boolean) =
    object : BridgeClient.Result<BridgeClient.LibraryCollectionPage> {
        override fun success(value: BridgeClient.LibraryCollectionPage) {
            if (generation != browseGeneration) return
            val converted = value.items.map {
                LibraryCollectionUiState(
                    it.id,
                    it.title,
                    it.subtitle,
                    it.trackCount,
                    it.artworkId,
                )
            }
            mutableState.update { state ->
                val revisionChanged = state.browse.revision.isNotBlank()
                    && value.revision.isNotBlank() && state.browse.revision != value.revision
                val window = mergePageWindow(
                    state.browse.collections,
                    state.browse.windowStart,
                    converted,
                    value.offset,
                    replace || revisionChanged,
                    TunesLinkViewModel.MAX_LIBRARY_WINDOW_ITEMS,
                )
                state.copy(
                    browse = state.browse.copy(
                        collections = window.items,
                        windowStart = window.startOffset,
                        revision = if (value.revision.isNotBlank() || replace || revisionChanged) {
                            value.revision
                        } else state.browse.revision,
                        total = value.total,
                        hasMore = window.startOffset + window.items.size < value.total,
                        hasPrevious = window.startOffset > 0,
                        isLoading = false,
                        isLoadingMore = false,
                        isLoadingPrevious = false,
                        error = null,
                    ),
                )
            }
        }

        override fun failure(message: String, unauthorized: Boolean) {
            if (generation != browseGeneration) return
            mutableState.update {
                it.copy(
                    browse = it.browse.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        isLoadingPrevious = false,
                        error = localizedFailure(message, R.string.error_library_unavailable),
                    ),
                )
            }
        }
    }

internal fun TunesLinkViewModel.browseTracksResult(generation: Int, replace: Boolean) =
    object : BridgeClient.Result<BridgeClient.LibraryPage> {
        override fun success(value: BridgeClient.LibraryPage) {
            if (generation != browseGeneration) return
            val converted = value.items.map { it.toUiState() }
            mutableState.update { state ->
                val revisionChanged = state.browse.revision.isNotBlank()
                    && value.revision.isNotBlank() && state.browse.revision != value.revision
                val window = mergePageWindow(
                    state.browse.tracks,
                    state.browse.windowStart,
                    converted,
                    value.offset,
                    replace || revisionChanged,
                    TunesLinkViewModel.MAX_LIBRARY_WINDOW_ITEMS,
                )
                state.copy(
                    browse = state.browse.copy(
                        tracks = window.items,
                        windowStart = window.startOffset,
                        revision = if (value.revision.isNotBlank() || replace || revisionChanged) {
                            value.revision
                        } else state.browse.revision,
                        total = value.total,
                        hasMore = window.startOffset + window.items.size < value.total,
                        hasPrevious = window.startOffset > 0,
                        isLoading = false,
                        isLoadingMore = false,
                        isLoadingPrevious = false,
                        error = null,
                    ),
                )
            }
        }

        override fun failure(message: String, unauthorized: Boolean) {
            if (generation != browseGeneration) return
            mutableState.update {
                it.copy(
                    browse = it.browse.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        isLoadingPrevious = false,
                        error = localizedFailure(message, R.string.error_library_unavailable),
                    ),
                )
            }
        }
    }

private fun BridgeClient.LibraryTrack.toUiState() = TrackUiState(
    id,
    title,
    artist,
    album,
    duration,
    artworkId,
    trackNumber,
    discNumber,
)

internal data class PageWindow<T>(val items: List<T>, val startOffset: Int)

internal fun <T> mergePageWindow(
    existing: List<T>,
    existingStart: Int,
    incoming: List<T>,
    incomingStart: Int,
    replace: Boolean,
    maximumItems: Int,
): PageWindow<T> {
    require(maximumItems > 0)
    if (replace || existing.isEmpty()) {
        val kept = incoming.take(maximumItems)
        return PageWindow(kept, incomingStart.coerceAtLeast(0))
    }
    if (incoming.isEmpty()) return PageWindow(existing, existingStart)

    val safeExistingStart = existingStart.coerceAtLeast(0)
    val safeIncomingStart = incomingStart.coerceAtLeast(0)
    val unionStart = minOf(safeExistingStart, safeIncomingStart)
    val unionEnd = maxOf(safeExistingStart + existing.size, safeIncomingStart + incoming.size)
    val slots = MutableList<T?>(unionEnd - unionStart) { null }
    existing.forEachIndexed { index, item -> slots[safeExistingStart - unionStart + index] = item }
    incoming.forEachIndexed { index, item -> slots[safeIncomingStart - unionStart + index] = item }
    if (slots.any { it == null }) {
        val kept = incoming.take(maximumItems)
        return PageWindow(kept, safeIncomingStart)
    }
    @Suppress("UNCHECKED_CAST")
    val merged = slots as List<T>
    if (merged.size <= maximumItems) return PageWindow(merged, unionStart)

    return if (safeIncomingStart >= safeExistingStart) {
        val drop = merged.size - maximumItems
        PageWindow(merged.drop(drop), unionStart + drop)
    } else {
        PageWindow(merged.take(maximumItems), unionStart)
    }
}
