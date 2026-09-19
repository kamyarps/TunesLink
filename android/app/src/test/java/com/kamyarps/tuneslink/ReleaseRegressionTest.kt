package com.kamyarps.tuneslink

import org.junit.Assert.*
import org.junit.Test

class ReleaseRegressionTest {
    @Test
    fun aSixHundredAndOneSongLibraryCanBeBrowsedForwardAndBackWithoutLosingRows() {
        var window = PageWindow((0 until 60).toList(), 0)
        var cursor = settledCursor(LibraryPageCursor(), window, 601, "revision", true)
        val seen = window.items.toMutableSet()
        while (cursor.hasMore) {
            assertEquals(PageDirection.Next, pageDirection(cursor, atStart = false, atEnd = true))
            val offset = window.startOffset + window.items.size
            window = mergePageWindow(window.items, window.startOffset,
                (offset until minOf(offset + 60, 601)).toList(), offset, false, 480)
            seen.addAll(window.items)
            cursor = settledCursor(cursor, window, 601, "revision", false)
            assertTrue(window.items.size <= 480)
        }
        assertEquals((0 until 601).toSet(), seen)
        assertEquals(121, window.startOffset)
        while (cursor.hasPrevious) {
            assertEquals(PageDirection.Previous, pageDirection(cursor, atStart = true, atEnd = false))
            val offset = (window.startOffset - 60).coerceAtLeast(0)
            window = mergePageWindow(window.items, window.startOffset,
                (offset until window.startOffset).toList(), offset, false, 480)
            cursor = settledCursor(cursor, window, 601, "revision", false)
        }
        assertEquals((0 until 480).toList(), window.items)
        assertEquals(0, window.startOffset)
        assertEquals(PageDirection.Next, pageDirection(cursor, atStart = false, atEnd = true))
    }

    @Test
    fun paginationWaitsForRetryAfterFailureAndNeverStartsTwoLoads() {
        val cursor = LibraryPageCursor(hasMore = true, hasPrevious = true)
        assertNull(pageDirection(cursor.copy(error = "Connection lost"), true, true))
        assertNull(pageDirection(cursor.copy(isLoadingMore = true), true, true))
        assertNull(pageDirection(cursor.copy(isLoadingPrevious = true), true, true))
        assertEquals(PageDirection.Previous, pageDirection(cursor, true, true))
    }

    @Test
    fun parentCollectionsDoNotHideAnEmptyDetailLoadingOrFailureState() {
        val browse = LibraryBrowseUiState(
            kind = LibraryBrowseKind.Albums,
            collections = listOf(LibraryCollectionUiState("album", "Album", "Artist", 120, "art")),
            selectedCollection = SelectedLibraryCollection(LibraryBrowseKind.Albums, "album", "Album", "Artist"),
            tracksCursor = LibraryPageCursor(isLoading = true),
        )
        assertTrue(browse.visibleItemsEmpty)
        assertTrue(browse.isLoading)
        val failed = browse.copy(tracksCursor = LibraryPageCursor(error = "Offline"))
        assertTrue(failed.visibleItemsEmpty)
        assertEquals("Offline", failed.error)
        assertFalse(failed.isLoading)
    }

    @Test
    fun backgroundCancellationRestoresDiscoveryAndPairingControls() {
        val discovering = TunesLinkUiState(connection = ConnectionState.Discovering)
        assertEquals(ConnectionState.Unpaired, discovering.afterTransientCancellation().connection)
        val submitting = TunesLinkUiState(connection = ConnectionState.Pairing,
            pairing = PairingUiState("123456", phase = PairingPhase.Submitting), manualResolutionBusy = true)
        val cancelled = submitting.afterTransientCancellation()
        assertTrue(cancelled.pairing.canSubmit)
        assertEquals("123456", cancelled.pairing.code)
        assertFalse(cancelled.pairingBusy)
        assertFalse(cancelled.manualResolutionBusy)
    }

    @Test
    fun unavailableBackendCannotConfirmAnOptimisticPause() {
        val mutation = PendingMutation(1, PlaybackAction.PlayPause, setOf(PlayerField.Playing),
            expectedBoolean = false, startedAtMillis = 0)
        val unavailable = BridgeClient.PlayerState(false, false, "Song", "Artist", "Album",
            200.0, 4.0, 55, "art", "track", false, "off")
        assertFalse(mutation.matches(unavailable))
    }

    @Test
    fun backendFailureDisablesAllPlaybackSurfacesAndShowsRecoveryWithRetainedMetadata() {
        val connected = TunesLinkUiState(connection = ConnectionState.Connected("PC"),
            player = PlayerUiState(iTunesAvailable = true, title = "Song", trackId = "track"))
        assertTrue(connected.playbackControlsEnabled)
        val failed = connected.copy(player = connected.player.copy(iTunesAvailable = false))
        assertFalse(failed.playbackControlsEnabled)
        assertEquals(R.string.open_itunes, playerIdleSubtitle(failed.player, unavailableHint = true))
        assertNull(playerIdleSubtitle(connected.player, unavailableHint = true))
    }

    @Test
    fun nonAdvancingPagesStopButAnExhaustedShrunkenLibraryIsAccepted() {
        assertFalse(pageAdvances(100060, 100000, 60, true))
        assertFalse(pageAdvances(60, 60, 0, true))
        assertTrue(pageAdvances(100060, 100060, 60, true))
        assertTrue(pageAdvances(100060, 0, 0, false))
        assertTrue(pageAdvances(Int.MAX_VALUE, Int.MAX_VALUE, 1, true))
        val window = mergePageWindow(listOf(1), 0, listOf(2), Int.MAX_VALUE - 1, false, 480)
        assertEquals(listOf(2), window.items)
    }
}
