package com.kamyarps.tuneslink

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged

internal enum class PageDirection { Previous, Next }

internal fun pageDirection(cursor: LibraryPageCursor, atStart: Boolean, atEnd: Boolean): PageDirection? =
    when {
        cursor.isBusy || cursor.error != null -> null
        atStart && cursor.hasPrevious -> PageDirection.Previous
        atEnd && cursor.hasMore -> PageDirection.Next
        else -> null
    }

@Composable
internal fun LibraryPagination(
    list: LazyListState,
    cursor: LibraryPageCursor,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) = LibraryPaginationEffect(list, cursor, onPrevious, onNext) {
    if (list.layoutInfo.visibleItemsInfo.isEmpty()) null
    else (!list.canScrollBackward to !list.canScrollForward)
}

@Composable
internal fun LibraryPagination(
    grid: LazyGridState,
    cursor: LibraryPageCursor,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) = LibraryPaginationEffect(grid, cursor, onPrevious, onNext) {
    if (grid.layoutInfo.visibleItemsInfo.isEmpty()) null
    else (!grid.canScrollBackward to !grid.canScrollForward)
}

@Composable
private fun LibraryPaginationEffect(
    identity: Any,
    cursor: LibraryPageCursor,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    boundaries: () -> Pair<Boolean, Boolean>?,
) {
    val currentCursor by rememberUpdatedState(cursor)
    val previous by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)
    val currentBoundaries by rememberUpdatedState(boundaries)
    LaunchedEffect(identity) {
        snapshotFlow {
            currentBoundaries()?.let { (start, end) ->
                pageDirection(currentCursor, start, end) to currentCursor
            }
        }.distinctUntilChanged().collect { observation ->
            // The cursor is part of the observation: a cached page can settle between frames.
            when (observation?.first) {
                PageDirection.Previous -> previous()
                PageDirection.Next -> next()
                null -> Unit
            }
        }
    }
}

internal fun LibraryUiState.pageCursor() = LibraryPageCursor(
    total = total, windowStart = windowStart, revision = revision,
    hasMore = hasMore, hasPrevious = hasPrevious,
    isLoading = isRefreshing, isLoadingMore = isLoadingMore,
    isLoadingPrevious = isLoadingPrevious, error = error,
)

@Composable
internal fun LibraryPageError(error: String?, onRetry: () -> Unit) {
    if (error == null) return
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Text(error, style = MaterialTheme.typography.bodyMedium,
            color = TunesLinkTheme.colors.secondaryText)
        TunesLinkTonalAction(stringResource(R.string.retry), onRetry)
    }
}
