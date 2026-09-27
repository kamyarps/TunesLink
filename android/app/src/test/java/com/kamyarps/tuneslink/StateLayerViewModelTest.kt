package com.kamyarps.tuneslink

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import sun.misc.Unsafe

/** ViewModel and presentation fixes from the 2026-09-27 state-layer pass. */
@OptIn(ExperimentalCoroutinesApi::class)
class StateLayerViewModelTest {
    @Before fun mainDispatcher() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun resetDispatcher() = Dispatchers.resetMain()

    private fun track(id: String, title: String = id) = TrackUiState(id, title, "Artist", "Album", 60.0, "")

    // 1. Page windows stay unique by ID in both directions.

    @Test fun aRowThatMovedLaterKeepsItsIncomingPositionWhenAppending() {
        val existing = (0 until 60).map { track("t$it") }
        val incoming = listOf(track("t10", "moved")) + (61 until 120).map { track("t$it") }
        val window = mergePageWindow(existing, 0, incoming, 60, false, 480, total = 200,
            key = TrackUiState::id)
        assertEquals(window.items.size, window.items.map { it.id }.toSet().size)
        assertEquals(119, window.items.size)
        // The incoming rows keep their absolute offsets; the window start moves past the gap.
        assertEquals(1, window.startOffset)
        assertEquals("moved", window.items[60 - window.startOffset].title)
        assertEquals("t119", window.items.last().id)
        val cursor = settledCursor(LibraryPageCursor(), window, 200, "", false)
        assertTrue(cursor.hasPrevious)
        assertTrue(cursor.hasMore)
        assertEquals(120, cursor.windowStart + window.items.size)
    }

    @Test fun aRowThatMovedEarlierKeepsItsIncomingPositionWhenPrepending() {
        val existing = (60 until 120).map { track(if (it == 63) "t5" else "t$it") }
        val incoming = (0 until 60).map { track("t$it", if (it == 5) "fresh" else "t$it") }
        val window = mergePageWindow(existing, 60, incoming, 0, false, 480, total = 120,
            key = TrackUiState::id)
        assertEquals(window.items.size, window.items.map { it.id }.toSet().size)
        assertEquals(0, window.startOffset)
        assertEquals(119, window.items.size)
        assertEquals("fresh", window.items[5].title)
        // The shrunken tail reloads: the cursor still reports more rows.
        assertTrue(settledCursor(LibraryPageCursor(), window, 120, "", false).hasMore)
    }

    @Test fun aStaleCachedPageNextToAFreshOneCannotDuplicateCollections() {
        fun collection(id: String) = LibraryCollectionUiState(id, id, "", 1, "")
        val fresh = (0 until 60).map { collection("p$it") }
        val cached = (59 until 119).map { collection("p$it") } // older snapshot, shifted by one
        val window = mergePageWindow(fresh, 0, cached, 60, false, 480,
            key = LibraryCollectionUiState::id)
        assertEquals(window.items.size, window.items.map { it.id }.toSet().size)
        val replaced = mergePageWindow(emptyList(), 0, cached + cached.take(3), 60, true, 480,
            key = LibraryCollectionUiState::id)
        assertEquals(60, replaced.items.size)
    }

    @Test fun browseReducerNeverPublishesDuplicateTrackIds() {
        val repository = BridgeRepository(FakeClient(), pairedStore())
        val tracks = (0 until 60).map { track("t$it") }
        val vm = testViewModel(repository, TunesLinkUiState(browse = LibraryBrowseUiState(
            kind = LibraryBrowseKind.Playlists, tracks = tracks,
            tracksCursor = LibraryPageCursor(total = 120, hasMore = true, isLoadingMore = true))))
        val page = (listOf("t3") + (61 until 120).map { "t$it" }).map {
            BridgeClient.LibraryTrack(it, it, "Artist", "Album", 60.0, 0, 0, "", "")
        }
        vm.browseTracksResult(vm.field("browseTracksGeneration") as Int, replace = false,
            requestedOffset = 60).page(BridgeClient.LibraryPage(page, 60, 60, 120, false, ""), true)
        val ids = vm.mutableState.value.browse.tracks.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        repository.close()
    }

    // 2. Opening a dialog releases "Finding computers…".

    @Test fun openingManualAddressDuringDiscoveryLeavesDiscovering() {
        val client = FakeClient()
        val repository = BridgeRepository(client, SecureStore(MemoryBackend(), CountingCrypto()))
        val vm = testViewModel(repository, TunesLinkUiState(connection = ConnectionState.Discovering))
        vm.openManualAddress()
        assertEquals(ConnectionState.Unpaired, vm.mutableState.value.connection)
        assertEquals(TunesLinkModal.ManualAddress, vm.mutableState.value.modal)
        val paired = TunesLinkUiState(connection = ConnectionState.Connected("PC"))
        assertSame(paired, paired.afterDiscoveryCancelled())
        repository.close()
    }

    // 4. Abandoned commands are cancelled when the session is restored.

    @Test fun reconnectCancelsCommandsQueuedBeforeTheOutage() {
        val client = FakeClient()
        val repository = BridgeRepository(client, pairedStore())
        val vm = testViewModel(repository, TunesLinkUiState(
            connection = ConnectionState.RecoverableFailure("PC"),
            player = PlayerUiState(trackId = "track", artworkId = "")))
        vm.setVolume(30)
        assertEquals(1, (vm.field("commandRequests") as Map<*, *>).size)
        // connect() is private; drive the same listener the ViewModel installs.
        val connect = TunesLinkViewModel::class.java.getDeclaredMethod("connect", Boolean::class.java)
        connect.isAccessible = true
        connect.invoke(vm, false)
        client.listener!!.connectionChanged(true, null)
        assertEquals(1, client.commandCancels)
        assertTrue((vm.field("commandRequests") as Map<*, *>).isEmpty())
        assertTrue(vm.mutableState.value.player.pendingMutations.isEmpty())
        repository.close()
    }

    // 13. Artwork after a track change.

    @Test fun artworkFailureAfterATrackChangeShowsThePlaceholder() {
        val bitmap = fakeBitmap()
        val previous = PlayerUiState(trackId = "a", artworkId = "art-a", artworkOwnerId = "art-a",
            artworkState = ArtworkLoadState.Ready(bitmap))
        assertTrue(previous.artworkIsCurrent)
        val changed = mergePlaybackState(previous, playerState(trackId = "b", artworkId = "art-b"))
        assertSame(bitmap, changed.artwork)
        assertFalse(changed.artworkIsCurrent)
        assertNull(changed.withArtworkFailure("HTTP 500").artwork)
        assertSame(bitmap, previous.withArtworkFailure("timeout").artwork)
    }

    @Test fun failedCoverRetriesSoonInsteadOfWaitingFiveMinutes() {
        val client = FakeClient()
        val repository = BridgeRepository(client, pairedStore())
        val vm = testViewModel(repository, TunesLinkUiState(
            connection = ConnectionState.Connected("PC"),
            player = PlayerUiState(trackId = "b", artworkId = "art-b", artworkOwnerId = "art-a",
                artworkState = ArtworkLoadState.Ready(fakeBitmap()))))
        vm.loadArtwork("art-b")
        assertEquals(Long.MAX_VALUE, vm.artworkDueAt)
        client.artwork!!.failure("HTTP 500", false)
        val player = vm.mutableState.value.player
        assertTrue(player.artworkState is ArtworkLoadState.FailedRetainingPrevious)
        assertNull(player.artwork)
        assertTrue(vm.artworkDueAt - System.currentTimeMillis() <= 2_000)
        assertNotNull(vm.artworkRetryJob)
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L),
            (1..7).map(::artworkRetryDelayMillis))
        repository.close()
    }

    // 15/17. Search while offline, retry by page, and announcements.

    @Test fun offlineSearchShowsAWaitingStateInsteadOfAnotherQuerysResults() {
        val vm = testViewModel(BridgeRepository(FakeClient(), pairedStore()), TunesLinkUiState(
            connection = ConnectionState.RecoverableFailure("PC"),
            library = LibraryUiState(editingQuery = "beat", loadedQuery = "abba",
                items = listOf(track("t1")), total = 1)))
        vm.commitSearch("beat")
        val library = vm.mutableState.value.library
        assertTrue(library.awaitingConnection)
        assertTrue(library.items.isEmpty())
        assertNull(library.loadedQuery)
        assertTrue(library.searchNeedsCommit())

        val same = testViewModel(BridgeRepository(FakeClient(), pairedStore()), TunesLinkUiState(
            connection = ConnectionState.RecoverableFailure("PC"),
            library = LibraryUiState(editingQuery = "abba", loadedQuery = "abba",
                items = listOf(track("t1")), total = 1)))
        same.commitSearch("abba")
        assertEquals(1, same.mutableState.value.library.items.size)
        assertFalse(same.mutableState.value.library.awaitingConnection)
    }

    @Test fun returningToSearchKeepsResultsAndTheirAnnouncement() {
        val client = FakeClient()
        val vm = testViewModel(BridgeRepository(client, pairedStore()), TunesLinkUiState(
            connection = ConnectionState.Connected("PC"),
            library = LibraryUiState(editingQuery = "abba ", loadedQuery = "abba",
                items = listOf(track("t1")), total = 1)))
        vm.announcedResultQuery = "abba"
        vm.navigate(TunesLinkDestination.Search)
        assertNull(client.library)
        assertEquals("abba", vm.announcedResultQuery)
    }

    @Test fun retryingAFailedNextPageLoadsThatPageNotTheFirst() {
        val client = FakeClient()
        val vm = testViewModel(BridgeRepository(client, pairedStore()), TunesLinkUiState(
            connection = ConnectionState.Connected("PC"),
            library = LibraryUiState(editingQuery = "a", loadedQuery = "a", windowStart = 120,
                items = (120 until 240).map { track("t$it") }, total = 600, hasMore = true,
                hasPrevious = true, error = "Offline", errorDirection = PageDirection.Next)))
        vm.retrySearch()
        assertEquals(240, client.libraryOffset)
        assertNull(vm.mutableState.value.library.error)
        assertTrue(vm.mutableState.value.library.isLoadingMore)
    }

    // 16. Forget resets the remembered destination and search.

    @Test fun forgetResetsDestinationAndSearchSoTheSameQueryCommitsAgain() {
        val vm = testViewModel(BridgeRepository(FakeClient(), SecureStore(MemoryBackend(), CountingCrypto())),
            TunesLinkUiState(library = LibraryUiState(editingQuery = "abba")))
        vm.setField("restoreDestination", TunesLinkDestination.Search)
        vm.setField("resetAfterModalDismiss", true)
        vm.savedStateHandle[TunesLinkViewModel.KEY_EDITING_QUERY] = "abba"
        vm.completeModalDismiss()
        assertEquals(TunesLinkDestination.Library, vm.field("restoreDestination"))
        assertEquals("", vm.editingQuery.value)
        assertEquals("", vm.savedStateHandle[TunesLinkViewModel.KEY_EDITING_QUERY])
        assertEquals("", vm.mutableState.value.library.editingQuery)
    }

    // 18. Dismissing "Pair again" restores the recovery state.

    @Test fun dismissingPairAgainRestoresThePairingExpiredState() {
        val client = FakeClient()
        val repository = BridgeRepository(client, pairedStore())
        val expired = ConnectionState.Unauthorized("PC")
        val vm = testViewModel(repository, TunesLinkUiState(
            route = TunesLinkRoute.Connected(TunesLinkDestination.Library), connection = expired))
        vm.pairAgain()
        client.manual!!.success(testBridge())
        assertTrue(vm.mutableState.value.modal is TunesLinkModal.Pairing)
        vm.requestModalDismiss()
        vm.completeModalDismiss()
        assertNull(vm.mutableState.value.modal)
        assertEquals(expired, vm.mutableState.value.connection)
        repository.close()
    }

    // 19/20. Permission grant and "Choose another computer".

    @Test fun grantingPermissionToAPairedPhoneNeverShowsWelcome() {
        val vm = testViewModel(BridgeRepository(FakeClient(), pairedStore()),
            TunesLinkUiState(route = TunesLinkRoute.LocalNetworkPermission))
        vm.setField("pendingPermissionAction", PendingPermissionAction.Reconnect)
        vm.localNetworkPermissionResult(true)
        assertEquals(TunesLinkRoute.Connecting, vm.mutableState.value.route)
        assertEquals(ConnectionState.Connecting, vm.mutableState.value.connection)
    }

    @Test fun choosingAnotherComputerCanReturnToTheSavedOne() {
        val client = FakeClient()
        val repository = BridgeRepository(client, pairedStore())
        val vm = testViewModel(repository, TunesLinkUiState(
            route = TunesLinkRoute.Connected(TunesLinkDestination.Library),
            connection = ConnectionState.RecoverableFailure("PC")))
        vm.chooseAnotherComputer()
        assertEquals("PC", vm.mutableState.value.returnComputer)
        assertNotNull(repository.current())
        vm.returnToSavedComputer()
        val state = vm.mutableState.value
        assertNull(state.returnComputer)
        assertFalse(state.navigation.switchingComputer)
        assertEquals(TunesLinkRoute.Connecting, state.route)
        assertEquals(1, client.starts)
        repository.close()
    }

    // 21. Seeks belong to the song they started on.

    @Test fun aSeekReleasedAfterTheSongChangedIsDropped() {
        val client = FakeClient()
        val repository = BridgeRepository(client, pairedStore())
        val vm = testViewModel(repository, TunesLinkUiState(
            connection = ConnectionState.Connected("PC"),
            player = PlayerUiState(trackId = "b", artworkId = "")))
        vm.seek(120.0, trackId = "a")
        assertTrue(client.commands.isEmpty())
        vm.seek(120.0, trackId = "b")
        assertEquals(listOf("position" to 120.0), client.commands)
        repository.close()
    }

    @Test fun aSeekToTheEndIsSettledByTheNextSong() {
        val seek = PendingMutation(1, PlaybackAction.Position, PlaybackAction.Position.playerFields(),
            expectedNumber = 199.0, previousTrackId = "a", startedAtMillis = 0)
        assertTrue(seek.matches(playerState(trackId = "b", position = 0.0)))
        assertFalse(seek.matches(playerState(trackId = "a", position = 20.0)))
    }

    // 14/23. Typed codes first, legacy text second.

    @Test fun typedCodesMapToLocalizedCopyAndPairingClosedIsNotACodeError() {
        assertEquals(R.string.error_pairing_closed,
            failureMessageRes("Pairing failed", R.string.error_pairing_code, BridgeClient.ErrorCode.PAIRING_CLOSED))
        assertFalse(isPairingCodeFailure(BridgeClient.ErrorCode.PAIRING_CLOSED, R.string.error_pairing_closed))
        assertTrue(isPairingCodeFailure(BridgeClient.ErrorCode.PAIRING_CODE_INCORRECT, R.string.error_pairing_code))
        assertTrue(isPairingCodeFailure(BridgeClient.ErrorCode.NONE, R.string.error_pairing_code))
        assertEquals(R.string.error_bridge_busy, bridgeErrorRes(BridgeClient.ErrorCode.BUSY))
        assertEquals(R.string.error_itunes_busy, playbackFailureRes(BridgeClient.ErrorCode.ITUNES_BUSY))
        assertNull(playbackFailureRes(BridgeClient.ErrorCode.BRIDGE_ERROR))
        assertEquals(R.string.error_library_unavailable,
            failureMessageRes("x", R.string.error_library_unavailable, BridgeClient.ErrorCode.INVALID_REQUEST))
    }

    @Test fun legacyStorageFailuresAreNotReportedAsIdentityOrCodeErrors() {
        val code = R.string.error_pairing_code
        assertEquals(R.string.error_pairing_storage,
            legacyFailureRes("This phone could not save its pairing identity. Try again.", code))
        assertEquals(R.string.error_pairing_storage, legacyFailureRes(
            "Pairing succeeded, but this phone could not save its credentials. Try again.", code))
        assertEquals(R.string.error_pairing_save_failed, legacyFailureRes("Pairing could not be saved", code))
        assertEquals(R.string.error_bridge_identity,
            legacyFailureRes("Bridge identity changed — verify and pair again", code))
        assertEquals(R.string.error_pairing_code, legacyFailureRes("Pairing failed", code))
    }

    private fun fakeBitmap(): Bitmap {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            .get(null) as Unsafe
        return unsafe.allocateInstance(Bitmap::class.java) as Bitmap
    }
}
