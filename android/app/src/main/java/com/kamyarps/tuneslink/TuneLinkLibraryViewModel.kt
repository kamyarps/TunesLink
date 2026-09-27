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

/** The search retry action; kept for existing callers. */
internal fun TunesLinkViewModel.refreshLibrary() = retrySearch()

/**
 * Retries what failed: the page that could not load (keeping the visible window), or the whole
 * search when nothing is shown yet. The result count is not announced again for the same query.
 */
internal fun TunesLinkViewModel.retrySearch() {
    val library = mutableState.value.library
    when {
        library.items.isEmpty() || library.loadedQuery == null -> commitSearch(library.editingQuery)
        library.errorDirection == PageDirection.Next -> loadMore()
        library.errorDirection == PageDirection.Previous -> loadPrevious()
        else -> commitSearch(library.editingQuery)
    }
}

internal fun TunesLinkViewModel.loadMore() {
    val library = mutableState.value.library
    if (!library.hasMore || library.isLoadingMore || library.isLoadingPrevious || library.isRefreshing) return
    val query = library.loadedQuery ?: return
    val generation = libraryGeneration
    mutableState.update {
        it.copy(library = it.library.copy(isLoadingMore = true, error = null, errorDirection = null))
    }
    libraryRequest.cancel()
    val offset = library.windowStart + library.items.size
    libraryRequest = repository.getLibrary(query, offset, TunesLinkViewModel.PAGE_SIZE,
        libraryResult(query, generation, replace = false, requestedOffset = offset,
            direction = PageDirection.Next))
}

internal fun TunesLinkViewModel.loadPrevious() {
    val library = mutableState.value.library
    if (!library.hasPrevious || library.isLoadingMore || library.isLoadingPrevious || library.isRefreshing) return
    val query = library.loadedQuery ?: return
    val offset = (library.windowStart - TunesLinkViewModel.PAGE_SIZE).coerceAtLeast(0)
    val limit = library.windowStart - offset
    if (limit <= 0) return
    val generation = libraryGeneration
    mutableState.update {
        it.copy(library = it.library.copy(isLoadingPrevious = true, error = null, errorDirection = null))
    }
    libraryRequest.cancel()
    libraryRequest = repository.getLibrary(query, offset, limit,
        libraryResult(query, generation, replace = false, requestedOffset = offset,
            direction = PageDirection.Previous))
}

internal fun TunesLinkViewModel.openLibraryKind(kind: LibraryBrowseKind) {
    browseCollectionsRequest.cancel()
    browseTracksRequest.cancel()
    browseCollectionsGeneration++
    browseTracksGeneration++
    val tracksOnly = kind == LibraryBrowseKind.Songs
    mutableState.update {
        it.copy(
            browse = LibraryBrowseUiState(
                kind = kind,
                collectionsCursor = LibraryPageCursor(isLoading = !tracksOnly),
                tracksCursor = LibraryPageCursor(isLoading = tracksOnly),
            ),
        )
    }
    if (tracksOnly) {
        browseTracksRequest = repository.getLibrary("", 0, TunesLinkViewModel.PAGE_SIZE,
            browseTracksResult(browseTracksGeneration, replace = true))
    } else {
        browseCollectionsRequest = repository.getCollections(
            kind.wireValue,
            "",
            0,
            TunesLinkViewModel.PAGE_SIZE,
            browseCollectionsResult(browseCollectionsGeneration, replace = true),
        )
    }
}

internal fun TunesLinkViewModel.openPhoneLibraryCollection(
    collection: LibraryCollectionUiState,
    scrollIndex: Int,
    scrollOffset: Int,
) {
    val browse = mutableState.value.browse
    val kind = browse.kind ?: return
    if (kind != LibraryBrowseKind.Artists && kind != LibraryBrowseKind.Genres) {
        openLibraryCollection(collection)
        return
    }
    browseCollectionsRequest.cancel()
    browseTracksRequest.cancel()
    browseCollectionsGeneration++
    browseTracksGeneration++
    mutableState.update {
        it.copy(browse = browse.copy(collectionScrollIndex = scrollIndex,
            collectionScrollOffset = scrollOffset).openAlbums(collection))
    }
    retryBrowse(LibraryBrowseTarget.Collections)
}

internal fun LibraryBrowseUiState.openAlbums(collection: LibraryCollectionUiState) = LibraryBrowseUiState(
    kind = LibraryBrowseKind.Albums,
    albumParent = SelectedLibraryCollection(requireNotNull(kind), collection.id, collection.title, collection.subtitle),
    parentBrowse = copy(collectionsCursor = collectionsCursor.copy(
        isLoading = false, isLoadingMore = false, isLoadingPrevious = false)),
)

internal fun TunesLinkViewModel.openLibraryCollection(collection: LibraryCollectionUiState) {
    val kind = mutableState.value.browse.kind ?: return
    if (kind == LibraryBrowseKind.Songs) return
    browseTracksRequest.cancel()
    val generation = ++browseTracksGeneration
    mutableState.update {
        it.copy(
            browse = it.browse.copy(
                selectedCollection = SelectedLibraryCollection(
                    kind,
                    collection.id,
                    collection.title,
                    collection.subtitle,
                ),
                tracks = emptyList(),
                tracksCursor = LibraryPageCursor(isLoading = true),
            ),
        )
    }
    browseTracksRequest = repository.getCollectionTracks(
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
    browseTracksRequest.cancel()
    browseTracksGeneration++
    if (browse.selectedCollection == null) {
        browseCollectionsRequest.cancel()
        browseCollectionsGeneration++
        mutableState.update { it.copy(browse = browse.parentBrowse ?: LibraryBrowseUiState()) }
        return true
    }
    mutableState.update {
        it.copy(
            browse = it.browse.copy(
                selectedCollection = null,
                tracks = emptyList(),
                tracksCursor = LibraryPageCursor(),
            ),
        )
    }
    return true
}

internal fun TunesLinkViewModel.loadMoreBrowse(target: LibraryBrowseTarget) {
    val browse = mutableState.value.browse
    val kind = browse.kind ?: return
    val cursor = browse.cursor(target)
    if (!cursor.hasMore || cursor.isBusy) return
    val loaded = if (target == LibraryBrowseTarget.Tracks) {
        browse.tracks.size
    } else {
        browse.collections.size
    }
    requestBrowsePage(target, kind, browse.selectedCollection, cursor.windowStart + loaded,
        TunesLinkViewModel.PAGE_SIZE, cursor.copy(isLoadingMore = true, error = null))
}

internal fun TunesLinkViewModel.loadPreviousBrowse(target: LibraryBrowseTarget) {
    val browse = mutableState.value.browse
    val kind = browse.kind ?: return
    val cursor = browse.cursor(target)
    if (!cursor.hasPrevious || cursor.isBusy) return
    val offset = (cursor.windowStart - TunesLinkViewModel.PAGE_SIZE).coerceAtLeast(0)
    val limit = cursor.windowStart - offset
    if (limit <= 0) return
    requestBrowsePage(target, kind, browse.selectedCollection, offset, limit,
        cursor.copy(isLoadingPrevious = true, error = null))
}

internal fun TunesLinkViewModel.retryBrowse(target: LibraryBrowseTarget) {
    val browse = mutableState.value.browse
    val kind = browse.kind ?: return
    val cursor = browse.cursor(target)
    if (cursor.isBusy) return
    requestBrowsePage(target, kind, browse.selectedCollection, cursor.windowStart,
        TunesLinkViewModel.PAGE_SIZE, cursor.copy(isLoading = true, error = null))
}

internal fun TunesLinkViewModel.restoreBrowseAfterReconnect() {
    browseCollectionsRequest.cancel()
    browseTracksRequest.cancel()
    browseCollectionsGeneration++
    browseTracksGeneration++
    mutableState.update { it.copy(browse = it.browse.afterReconnect()) }
    val browse = mutableState.value.browse
    if (browse.kind == null) return
    if (browse.kind != LibraryBrowseKind.Songs) retryBrowse(LibraryBrowseTarget.Collections)
    if (browse.kind == LibraryBrowseKind.Songs || browse.selectedCollection != null) {
        retryBrowse(LibraryBrowseTarget.Tracks)
    }
}

internal fun LibraryBrowseUiState.afterReconnect(): LibraryBrowseUiState = copy(
    collectionsCursor = collectionsCursor.afterReconnect(),
    tracksCursor = tracksCursor.afterReconnect(),
    parentBrowse = parentBrowse?.afterReconnect(),
)

private fun LibraryPageCursor.afterReconnect() = copy(
    isLoading = false, isLoadingMore = false, isLoadingPrevious = false, error = null,
)

private fun TunesLinkViewModel.requestBrowsePage(
    target: LibraryBrowseTarget,
    kind: LibraryBrowseKind,
    selected: SelectedLibraryCollection?,
    offset: Int,
    limit: Int,
    cursor: LibraryPageCursor,
) {
    mutableState.update { it.copy(browse = it.browse.withCursor(target, cursor)) }
    if (target == LibraryBrowseTarget.Collections) {
        browseCollectionsRequest.cancel()
        val parent = mutableState.value.browse.albumParent
        browseCollectionsRequest = repository.getCollections(
            parent?.kind?.wireValue ?: kind.wireValue, parent?.id.orEmpty(), "", offset, limit,
            browseCollectionsResult(browseCollectionsGeneration, replace = false, requestedOffset = offset),
        )
        return
    }
    browseTracksRequest.cancel()
    browseTracksRequest = if (selected != null) {
        repository.getCollectionTracks(
            kind.wireValue, selected.id, "", offset, limit,
            browseTracksResult(browseTracksGeneration, replace = false, requestedOffset = offset),
        )
    } else {
        repository.getLibrary("", offset, limit,
            browseTracksResult(browseTracksGeneration, replace = false, requestedOffset = offset))
    }
}

internal fun TunesLinkViewModel.playTrack(
    track: TrackUiState,
    collection: SelectedLibraryCollection? = null,
) {
    playTrackRequest.cancel()
    val previous = mutableState.value.player
    val superseded = previous.pendingMutations.values.filter { pending ->
        pending.affectedFields.any(PlaybackAction.PlayTrack.playerFields()::contains)
    }
    superseded.forEach { mutationTimeoutJobs.remove(it.operationId)?.cancel() }
    val retained = previous.pendingMutations - superseded.map(PendingMutation::action).toSet()
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
                pendingMutations = retained + (PlaybackAction.PlayTrack to mutation),
                commandError = null,
            ),
        )
    }
    loadArtwork(track.artworkId)
    playTrackRequest = repository.playTrack(
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
    libraryGeneration++
    val normalized = query.trim()
    // Each new query is announced once; refreshing the same query only announces a new count.
    if (normalized != mutableState.value.library.loadedQuery) announcedResultQuery = null
    if (normalized.isEmpty()) {
        mutableState.update { it.copy(library = it.library.clearedSearch(loadedQuery = "")) }
        return
    }
    if (mutableState.value.connection !is ConnectionState.Connected) {
        mutableState.update {
            val library = it.library
            it.copy(
                library = if (library.loadedQuery == normalized) {
                    // These are this query's results; they refresh after reconnecting.
                    library.copy(isRefreshing = false, isLoadingMore = false, isLoadingPrevious = false)
                } else {
                    // Never present another query's rows as results for this one.
                    library.clearedSearch(loadedQuery = null).copy(awaitingConnection = true)
                },
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
                isLoadingPrevious = false,
                error = null,
                errorDirection = null,
                awaitingConnection = false,
            ),
        )
    }
    libraryRequest = repository.getLibrary(normalized, 0, TunesLinkViewModel.PAGE_SIZE,
        libraryResult(normalized, generation, replace = true))
}

internal fun LibraryUiState.clearedSearch(loadedQuery: String?) = copy(
    items = emptyList(),
    total = 0,
    hasMore = false,
    hasPrevious = false,
    windowStart = 0,
    revision = "",
    loadedQuery = loadedQuery,
    isRefreshing = false,
    isLoadingMore = false,
    isLoadingPrevious = false,
    error = null,
    errorDirection = null,
    awaitingConnection = false,
)

/** Whether showing Search must (re)run the typed query rather than keep what is displayed. */
internal fun LibraryUiState.searchNeedsCommit(): Boolean {
    val normalized = editingQuery.trim()
    return normalized.isNotEmpty() &&
        (normalized != loadedQuery || (error != null && items.isEmpty()))
}

internal fun TunesLinkViewModel.libraryResult(
    query: String,
    generation: Int,
    replace: Boolean,
    requestedOffset: Int = 0,
    direction: PageDirection? = null,
) =
    object : BridgeRepository.PageResult<BridgeClient.LibraryPage> {
        override fun page(value: BridgeClient.LibraryPage, authoritative: Boolean) {
            val current = mutableState.value.library
            if (generation != libraryGeneration || current.loadedQuery != query) return
            if (!pageAdvances(requestedOffset, value.offset, value.items.size, value.hasMore)) {
                if (authoritative) failure("", false)
                return
            }
            val alreadyAnnounced = announcedResultQuery == query && current.total == value.total
            val converted = value.items.map { it.toUiState(untitled) }
            mutableState.update { state ->
                val library = state.library
                if (!authoritative) {
                    if (library.items.isNotEmpty() &&
                        revisionChanged(library.revision, value.revision)
                    ) {
                        return@update state
                    }
                    val window = mergePageWindow(
                        library.items,
                        library.windowStart,
                        converted,
                        value.offset,
                        replace || library.items.isEmpty(),
                        TunesLinkViewModel.MAX_LIBRARY_WINDOW_ITEMS,
                        key = TrackUiState::id,
                    )
                    return@update state.copy(
                        library = library.copy(
                            items = window.items,
                            windowStart = window.startOffset,
                            revision = if (library.items.isEmpty()) value.revision else library.revision,
                            total = value.total,
                            hasMore = window.startOffset + window.items.size < value.total,
                            hasPrevious = window.startOffset > 0,
                        ),
                    )
                }
                val revisionReplaced = revisionChanged(library.revision, value.revision)
                val window = mergePageWindow(
                    library.items,
                    library.windowStart,
                    converted,
                    value.offset,
                    replace || revisionReplaced,
                    TunesLinkViewModel.MAX_LIBRARY_WINDOW_ITEMS,
                    total = value.total,
                    key = TrackUiState::id,
                )
                state.copy(
                    library = library.copy(
                        items = window.items,
                        windowStart = window.startOffset,
                        revision = if (value.revision.isNotBlank() || replace || revisionReplaced) {
                            value.revision
                        } else library.revision,
                        total = value.total,
                        hasMore = window.startOffset + window.items.size < value.total,
                        hasPrevious = window.startOffset > 0,
                        isRefreshing = false,
                        isLoadingMore = false,
                        isLoadingPrevious = false,
                        error = null,
                        errorDirection = null,
                    ),
                )
            }
            if (!authoritative) return
            if (mutableState.value.library.items.isEmpty() && value.total > 0 && requestedOffset >= value.total) {
                commitSearch(query)
                return
            }
            if (alreadyAnnounced) return
            announcedResultQuery = query
            announcePlural(R.plurals.result_count, value.total, listOf(value.total))
        }

        override fun failure(message: String, unauthorized: Boolean) =
            failure(message, unauthorized, BridgeClient.ErrorCode.NONE)

        override fun failure(message: String, unauthorized: Boolean, code: BridgeClient.ErrorCode) {
            if (generation != libraryGeneration) return
            val error = localizedFailure(message, R.string.error_library_unavailable, code)
            mutableState.update {
                it.copy(
                    library = it.library.copy(
                        isRefreshing = false,
                        isLoadingMore = false,
                        isLoadingPrevious = false,
                        error = error,
                        errorDirection = direction,
                    ),
                )
            }
        }
    }

internal fun TunesLinkViewModel.browseCollectionsResult(generation: Int, replace: Boolean, requestedOffset: Int = 0) =
    object : BridgeRepository.PageResult<BridgeClient.LibraryCollectionPage> {
        override fun page(value: BridgeClient.LibraryCollectionPage, authoritative: Boolean) {
            if (generation != browseCollectionsGeneration) return
            if (!pageAdvances(requestedOffset, value.offset, value.items.size, value.hasMore)) {
                if (authoritative) failure("", false)
                return
            }
            val converted = value.items.map {
                LibraryCollectionUiState(
                    it.id,
                    it.title.ifBlank(untitled),
                    it.subtitle,
                    it.trackCount,
                    it.artworkId,
                )
            }
            mutableState.update { state ->
                val cursor = state.browse.collectionsCursor
                if (!authoritative) {
                    if (state.browse.collections.isNotEmpty() &&
                        revisionChanged(cursor.revision, value.revision)
                    ) {
                        return@update state
                    }
                    val window = mergePageWindow(
                        state.browse.collections,
                        cursor.windowStart,
                        converted,
                        value.offset,
                        replace || state.browse.collections.isEmpty(),
                        TunesLinkViewModel.MAX_LIBRARY_WINDOW_ITEMS,
                        key = LibraryCollectionUiState::id,
                    )
                    return@update state.copy(
                        browse = state.browse.copy(
                            collections = window.items,
                            collectionsCursor = provisionalCursor(
                                cursor, window, value.total, value.revision,
                                state.browse.collections.isEmpty(),
                            ),
                        ),
                    )
                }
                val replaced = replace || revisionChanged(cursor.revision, value.revision)
                val window = mergePageWindow(
                    state.browse.collections,
                    cursor.windowStart,
                    converted,
                    value.offset,
                    replaced,
                    TunesLinkViewModel.MAX_LIBRARY_WINDOW_ITEMS,
                    total = value.total,
                    key = LibraryCollectionUiState::id,
                )
                state.copy(
                    browse = state.browse.copy(
                        collections = window.items,
                        collectionsCursor = settledCursor(
                            cursor, window, value.total, value.revision, replaced,
                        ),
                    ),
                )
            }
            val browse = mutableState.value.browse
            if (authoritative && browse.collections.isEmpty() && value.total > 0 &&
                requestedOffset >= value.total && browse.kind != null
            ) {
                requestBrowsePage(LibraryBrowseTarget.Collections, browse.kind,
                    browse.selectedCollection, 0, TunesLinkViewModel.PAGE_SIZE,
                    browse.collectionsCursor.copy(isLoading = true, windowStart = 0))
            }
        }

        override fun failure(message: String, unauthorized: Boolean) =
            failure(message, unauthorized, BridgeClient.ErrorCode.NONE)

        override fun failure(message: String, unauthorized: Boolean, code: BridgeClient.ErrorCode) {
            if (generation != browseCollectionsGeneration) return
            failBrowseCursor(LibraryBrowseTarget.Collections, message, code)
        }
    }

internal fun TunesLinkViewModel.browseTracksResult(generation: Int, replace: Boolean, requestedOffset: Int = 0) =
    object : BridgeRepository.PageResult<BridgeClient.LibraryPage> {
        override fun page(value: BridgeClient.LibraryPage, authoritative: Boolean) {
            if (generation != browseTracksGeneration) return
            if (!pageAdvances(requestedOffset, value.offset, value.items.size, value.hasMore)) {
                if (authoritative) failure("", false)
                return
            }
            val converted = value.items.map { it.toUiState(untitled) }
            mutableState.update { state ->
                val cursor = state.browse.tracksCursor
                if (!authoritative) {
                    if (state.browse.tracks.isNotEmpty() &&
                        revisionChanged(cursor.revision, value.revision)
                    ) {
                        return@update state
                    }
                    val window = mergePageWindow(
                        state.browse.tracks,
                        cursor.windowStart,
                        converted,
                        value.offset,
                        replace || state.browse.tracks.isEmpty(),
                        TunesLinkViewModel.MAX_LIBRARY_WINDOW_ITEMS,
                        key = TrackUiState::id,
                    )
                    return@update state.copy(
                        browse = state.browse.copy(
                            tracks = window.items,
                            tracksCursor = provisionalCursor(
                                cursor, window, value.total, value.revision,
                                state.browse.tracks.isEmpty(),
                            ),
                        ),
                    )
                }
                val replaced = replace || revisionChanged(cursor.revision, value.revision)
                val window = mergePageWindow(
                    state.browse.tracks,
                    cursor.windowStart,
                    converted,
                    value.offset,
                    replaced,
                    TunesLinkViewModel.MAX_LIBRARY_WINDOW_ITEMS,
                    total = value.total,
                    key = TrackUiState::id,
                )
                state.copy(
                    browse = state.browse.copy(
                        tracks = window.items,
                        tracksCursor = settledCursor(
                            cursor, window, value.total, value.revision, replaced,
                        ),
                    ),
                )
            }
            val browse = mutableState.value.browse
            if (authoritative && browse.tracks.isEmpty() && value.total > 0 &&
                requestedOffset >= value.total && browse.kind != null
            ) {
                requestBrowsePage(LibraryBrowseTarget.Tracks, browse.kind,
                    browse.selectedCollection, 0, TunesLinkViewModel.PAGE_SIZE,
                    browse.tracksCursor.copy(isLoading = true, windowStart = 0))
            }
        }

        override fun failure(message: String, unauthorized: Boolean) =
            failure(message, unauthorized, BridgeClient.ErrorCode.NONE)

        override fun failure(message: String, unauthorized: Boolean, code: BridgeClient.ErrorCode) {
            if (generation != browseTracksGeneration) return
            failBrowseCursor(LibraryBrowseTarget.Tracks, message, code)
        }
    }

private fun TunesLinkViewModel.failBrowseCursor(
    target: LibraryBrowseTarget,
    message: String,
    code: BridgeClient.ErrorCode,
) {
    val failure = localizedFailure(message, R.string.error_library_unavailable, code)
    mutableState.update {
        it.copy(
            browse = it.browse.withCursor(
                target,
                it.browse.cursor(target).copy(
                    isLoading = false,
                    isLoadingMore = false,
                    isLoadingPrevious = false,
                    error = failure,
                ),
            ),
        )
    }
}

internal fun pageAdvances(requestedOffset: Int, returnedOffset: Int, count: Int, hasMore: Boolean): Boolean =
    !hasMore || (count > 0 && returnedOffset.toLong() + count > requestedOffset)

internal fun revisionChanged(current: String, incoming: String): Boolean =
    current.isNotBlank() && incoming.isNotBlank() && current != incoming

internal fun <T> settledCursor(
    cursor: LibraryPageCursor,
    window: PageWindow<T>,
    total: Int,
    revision: String,
    replaced: Boolean,
): LibraryPageCursor = cursor.copy(
    total = total,
    windowStart = window.startOffset,
    revision = if (revision.isNotBlank() || replaced) revision else cursor.revision,
    hasMore = window.startOffset + window.items.size < total,
    hasPrevious = window.startOffset > 0,
    isLoading = false,
    isLoadingMore = false,
    isLoadingPrevious = false,
    error = null,
)

internal fun <T> provisionalCursor(
    cursor: LibraryPageCursor,
    window: PageWindow<T>,
    total: Int,
    revision: String,
    windowWasEmpty: Boolean,
): LibraryPageCursor = cursor.copy(
    total = total,
    windowStart = window.startOffset,
    revision = if (windowWasEmpty) revision else cursor.revision,
    hasMore = window.startOffset + window.items.size < total,
    hasPrevious = window.startOffset > 0,
)

/** Blank wire titles are shown with the localized "Untitled". */
private val TunesLinkViewModel.untitled: () -> String get() = { localizedString(R.string.untitled) }

private fun BridgeClient.LibraryTrack.toUiState(untitled: () -> String) = TrackUiState(
    id = id,
    title = title.ifBlank(untitled),
    artist = artist,
    album = album,
    duration = duration,
    artworkId = artworkId,
    trackNumber = trackNumber,
    discNumber = discNumber,
    albumArtist = albumArtist,
)

internal data class PageWindow<T>(val items: List<T>, val startOffset: Int)

/**
 * Merges a page into the retained window by absolute offset. The result is unique by [key]
 * (lazy lists key rows by ID): when a row moved between page loads, the incoming copy keeps its
 * position and the stale copy is dropped. Dropping a stale row before the page moves the window
 * start so the page's rows keep their absolute offsets; the next page load fills any gap.
 */
internal fun <T> mergePageWindow(
    existing: List<T>,
    existingStart: Int,
    incoming: List<T>,
    incomingStart: Int,
    replace: Boolean,
    maximumItems: Int,
    total: Int = Int.MAX_VALUE,
    key: (T) -> Any? = { it },
): PageWindow<T> {
    require(maximumItems > 0)
    require(total >= 0)
    if (total == 0) return PageWindow(emptyList(), 0)
    val window = mergeUnboundedPageWindow(existing, existingStart, incoming.distinctBy(key),
        incomingStart, replace, maximumItems, key)
    val start = window.startOffset.coerceAtMost(total)
    return PageWindow(window.items.take(total - start), start)
}

private fun <T> mergeUnboundedPageWindow(
    existing: List<T>, existingStart: Int, incoming: List<T>, incomingStart: Int,
    replace: Boolean, maximumItems: Int, key: (T) -> Any?,
): PageWindow<T> {
    if (replace || existing.isEmpty()) {
        val kept = incoming.take(maximumItems)
        return PageWindow(kept, incomingStart.coerceAtLeast(0))
    }
    if (incoming.isEmpty()) return PageWindow(existing, existingStart)

    val safeExistingStart = existingStart.coerceAtLeast(0)
    val safeIncomingStart = incomingStart.coerceAtLeast(0)
    if (safeIncomingStart.toLong() > safeExistingStart.toLong() + existing.size ||
        safeExistingStart.toLong() > safeIncomingStart.toLong() + incoming.size
    ) return PageWindow(incoming.take(maximumItems), safeIncomingStart)
    val unionStart = minOf(safeExistingStart, safeIncomingStart)
    val unionEnd = maxOf(safeExistingStart + existing.size, safeIncomingStart + incoming.size)
    val slots = MutableList<T?>(unionEnd - unionStart) { null }
    existing.forEachIndexed { index, item -> slots[safeExistingStart - unionStart + index] = item }
    incoming.forEachIndexed { index, item -> slots[safeIncomingStart - unionStart + index] = item }
    if (slots.any { it == null }) {
        val kept = incoming.take(maximumItems)
        return PageWindow(kept, safeIncomingStart)
    }
    val incomingFrom = safeIncomingStart - unionStart
    val incomingUntil = incomingFrom + incoming.size
    val incomingKeys = incoming.mapTo(HashSet(), key)
    val seen = HashSet<Any?>()
    val merged = ArrayList<T>(slots.size)
    var droppedBefore = 0
    slots.forEachIndexed { index, slot ->
        @Suppress("UNCHECKED_CAST")
        val item = slot as T
        val itemKey = key(item)
        val fromIncoming = index in incomingFrom until incomingUntil
        if (!fromIncoming && (itemKey in incomingKeys || !seen.add(itemKey))) {
            if (index < incomingFrom) droppedBefore++
        } else {
            seen.add(itemKey)
            merged += item
        }
    }
    val start = unionStart + droppedBefore
    if (merged.size <= maximumItems) return PageWindow(merged, start)

    return if (safeIncomingStart >= safeExistingStart) {
        val drop = merged.size - maximumItems
        PageWindow(merged.drop(drop), start + drop)
    } else {
        PageWindow(merged.take(maximumItems), start)
    }
}
