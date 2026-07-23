package com.kamyarps.tuneslink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunesLinkPresentationTest {
    @Test
    fun pairingRequiresACompleteCode() {
        assertFalse(PairingUiState(code = "12345").canSubmit)
        assertTrue(PairingUiState(code = "123456").canSubmit)
        assertFalse(
            PairingUiState(
                code = "123456",
                phase = PairingPhase.Submitting,
            ).canSubmit,
        )
    }

    @Test
    fun connectionProblemsDisableControlsAndDescribeRecovery() {
        val lost = ConnectionAvailability.from(ConnectionState.RecoverableFailure("Studio PC"))
        val expired = ConnectionAvailability.from(ConnectionState.Unauthorized("Studio PC"))
        val connected = ConnectionAvailability.from(ConnectionState.Connected("Studio PC"))

        assertFalse(lost.controlsEnabled)
        assertEquals(R.string.try_again, lost.primaryLabelRes)
        assertEquals(R.string.pair_again, expired.primaryLabelRes)
        assertTrue(connected.controlsEnabled)
        assertFalse(connected.visible)
    }

    @Test
    fun connectionRecoveryDialogAppearsOncePerVisibleFailureKind() {
        val lost = ConnectionAvailability.from(ConnectionState.RecoverableFailure("Studio PC"))

        assertTrue(shouldPresentConnectionRecoveryDialog(lost, null, false, true))
        assertFalse(
            shouldPresentConnectionRecoveryDialog(
                lost,
                ConnectionAvailabilityKind.ConnectionLost,
                false,
                true,
            ),
        )
        assertFalse(shouldPresentConnectionRecoveryDialog(lost, null, true, true))
        assertFalse(shouldPresentConnectionRecoveryDialog(lost, null, false, false))

        val identityChanged = ConnectionAvailability.from(ConnectionState.IdentityChanged("Studio PC"))
        assertTrue(
            shouldPresentConnectionRecoveryDialog(
                identityChanged,
                ConnectionAvailabilityKind.ConnectionLost,
                false,
                true,
            ),
        )
    }

    @Test
    fun backPriorityIsModalThenActiveSearchThenSecondaryDestinations() {
        assertEquals(
            NavigationBackAction.DismissModal,
            navigationBackAction(true, true, TunesLinkDestination.NowPlaying),
        )
        assertEquals(
            NavigationBackAction.CancelSearch,
            navigationBackAction(false, true, TunesLinkDestination.NowPlaying),
        )
        assertEquals(
            NavigationBackAction.ShowLibrary,
            navigationBackAction(false, false, TunesLinkDestination.NowPlaying),
        )
        assertEquals(
            NavigationBackAction.ShowLibrary,
            navigationBackAction(false, false, TunesLinkDestination.Search),
        )
        assertEquals(
            NavigationBackAction.System,
            navigationBackAction(false, false, TunesLinkDestination.Library),
        )
    }

    @Test
    fun connectedDestinationsShareRootContentWithoutLosingTheirRouteState() {
        val library = TunesLinkRoute.Connected(TunesLinkDestination.Library)
        val nowPlaying = TunesLinkRoute.Connected(TunesLinkDestination.NowPlaying)
        val search = TunesLinkRoute.Connected(TunesLinkDestination.Search)

        assertEquals(library.rootContentKey(), nowPlaying.rootContentKey())
        assertEquals(library.rootContentKey(), search.rootContentKey())
        assertEquals(TunesLinkDestination.Library, library.destination)
        assertEquals(TunesLinkDestination.NowPlaying, nowPlaying.destination)
        assertFalse(library.rootContentKey() == TunesLinkRoute.Welcome.rootContentKey())
    }

    @Test
    fun sseMergePreservesPendingCommandAndRetainsArtworkWhileNextImageLoads() {
        val current = PlayerUiState(
            artworkId = "old",
            artworkState = ArtworkLoadState.Empty,
            pendingMutations = mapOf(PlaybackAction.PlayPause to PendingMutation(
                operationId = 1,
                action = PlaybackAction.PlayPause,
                affectedFields = setOf(PlayerField.Playing),
                expectedBoolean = false,
                startedAtMillis = 0,
            )),
            commandError = "still visible",
        )
        val incoming = BridgeClient.PlayerState(
            true, true, "New song", "Artist", "Album", 200.0, 4.0, 55, "new",
            "track-2", true, "all",
        )

        val merged = mergePlaybackState(current, incoming)

        assertEquals(1L, merged.pending(PlaybackAction.PlayPause)?.operationId)
        assertEquals("still visible", merged.commandError)
        assertTrue(merged.artworkState is ArtworkLoadState.Loading)
        assertEquals("new", merged.artworkId)
    }

    @Test
    fun staleSsePreservesOptimisticFieldsUntilExpectedStateArrives() {
        val mutation = PendingMutation(
            operationId = 7,
            action = PlaybackAction.Volume,
            affectedFields = setOf(PlayerField.Volume),
            expectedNumber = 80.0,
            startedAtMillis = 0,
        )
        val optimistic = PlayerUiState(
            volume = 80,
            pendingMutations = mapOf(PlaybackAction.Volume to mutation),
        )
        val stale = BridgeClient.PlayerState(
            true, false, "Song", "Artist", "Album", 200.0, 4.0, 20, "art",
            "track", false, "off",
        )
        val confirmed = BridgeClient.PlayerState(
            true, false, "Song", "Artist", "Album", 200.0, 4.0, 80, "art",
            "track", false, "off",
        )

        val protected = mergePlaybackState(optimistic, stale)
        assertEquals(80, protected.volume)
        assertEquals(7L, protected.pending(PlaybackAction.Volume)?.operationId)

        val settled = mergePlaybackState(protected, confirmed)
        assertEquals(80, settled.volume)
        assertTrue(settled.pendingMutations.isEmpty())
    }

    @Test
    fun independentPendingCommandsReconcileWithoutFreezingEachOther() {
        val volume = PendingMutation(
            operationId = 10,
            action = PlaybackAction.Volume,
            affectedFields = PlaybackAction.Volume.playerFields(),
            expectedNumber = 80.0,
            startedAtMillis = 0,
        )
        val playback = PendingMutation(
            operationId = 11,
            action = PlaybackAction.PlayPause,
            affectedFields = PlaybackAction.PlayPause.playerFields(),
            expectedBoolean = true,
            startedAtMillis = 0,
        )
        val optimistic = PlayerUiState(
            playing = true,
            volume = 80,
            pendingMutations = mapOf(
                PlaybackAction.Volume to volume,
                PlaybackAction.PlayPause to playback,
            ),
        )
        val frame = BridgeClient.PlayerState(
            true, true, "Song", "Artist", "Album", 200.0, 4.0, 20, "art",
            "track", false, "off",
        )

        val merged = mergePlaybackState(optimistic, frame)

        assertEquals(80, merged.volume)
        assertTrue(merged.playing)
        assertEquals(null, merged.pending(PlaybackAction.PlayPause))
        assertEquals(10L, merged.pending(PlaybackAction.Volume)?.operationId)
        assertFalse(merged.hasPendingConflict(PlaybackAction.PlayPause))
        assertTrue(merged.hasPendingConflict(PlaybackAction.Volume))
    }

    @Test
    fun playTrackMutationProtectsOptimisticMetadataFromOldFrame() {
        val mutation = PendingMutation(
            operationId = 8,
            action = PlaybackAction.PlayTrack,
            affectedFields = setOf(PlayerField.Playing, PlayerField.Metadata, PlayerField.Position),
            expectedTrackId = "new-track",
            previousTrackId = "old-track",
            startedAtMillis = 0,
        )
        val optimistic = PlayerUiState(
            playing = true,
            title = "New song",
            duration = 180.0,
            position = 0.0,
            trackId = "new-track",
            artworkId = "new-art",
            pendingMutations = mapOf(PlaybackAction.PlayTrack to mutation),
        )
        val stale = BridgeClient.PlayerState(
            true, false, "Old song", "Artist", "Album", 220.0, 90.0, 50, "old-art",
            "old-track", false, "off",
        )

        val merged = mergePlaybackState(optimistic, stale)
        assertTrue(merged.playing)
        assertEquals("New song", merged.title)
        assertEquals(0.0, merged.position, 0.0)
        assertEquals("new-art", merged.artworkId)
    }

    @Test
    fun playbackActionsMatchTheBridgeCommandContract() {
        assertEquals(
            setOf("playPause", "previous", "next", "position", "volume", "shuffle", "repeat"),
            PlaybackAction.entries.mapNotNull(PlaybackAction::wireCommand).toSet(),
        )
    }

    @Test
    fun repeatModeCyclesLikeTheMusicPlayerControl() {
        assertEquals(RepeatMode.All, RepeatMode.Off.next())
        assertEquals(RepeatMode.One, RepeatMode.All.next())
        assertEquals(RepeatMode.Off, RepeatMode.One.next())
    }

    @Test
    fun nowPlayingUsesPurposefulResponsiveCompositions() {
        assertEquals(
            NowPlayingLayoutMode.Vertical,
            nowPlayingLayoutMode(widthDp = 360f, heightDp = 608f, fontScale = 1f),
        )
        assertEquals(
            NowPlayingLayoutMode.CompactHorizontal,
            nowPlayingLayoutMode(widthDp = 700f, heightDp = 400f, fontScale = 1f),
        )
        assertEquals(
            NowPlayingLayoutMode.ExpandedHorizontal,
            nowPlayingLayoutMode(widthDp = 900f, heightDp = 600f, fontScale = 1f),
        )
        assertEquals(
            NowPlayingLayoutMode.Vertical,
            nowPlayingLayoutMode(widthDp = 900f, heightDp = 600f, fontScale = 2f),
        )
    }

    @Test
    fun navigationAdaptsLabelsBeforeLargeTextCanCollide() {
        assertTrue(navigationRailShowsLabels(fontScale = 1f))
        assertFalse(navigationRailShowsLabels(fontScale = 1.3f))
        assertFalse(navigationRailShowsLabels(fontScale = 2f))
        assertFalse(usesCompactPlayerNavigationLabel(fontScale = 1f))
        assertTrue(usesCompactPlayerNavigationLabel(fontScale = 1.3f))
        assertTrue(usesCompactPlayerNavigationLabel(fontScale = 2f))
    }

    @Test
    fun nowPlayingArtworkDominatesWithoutCrowdingControls() {
        assertEquals(
            261.44f,
            nowPlayingArtworkSizeDp(
                widthDp = 360f,
                heightDp = 608f,
                outerPaddingDp = 24f,
                mode = NowPlayingLayoutMode.Vertical,
                fontScale = 1f,
            ),
            0.01f,
        )
        assertEquals(
            206.72f,
            nowPlayingArtworkSizeDp(
                widthDp = 360f,
                heightDp = 608f,
                outerPaddingDp = 24f,
                mode = NowPlayingLayoutMode.Vertical,
                fontScale = 2f,
            ),
            0.01f,
        )
        assertEquals(
            520f,
            nowPlayingArtworkSizeDp(
                widthDp = 587f,
                heightDp = 1000f,
                outerPaddingDp = 32f,
                mode = NowPlayingLayoutMode.Vertical,
                fontScale = 1f,
            ),
            0.01f,
        )
    }
}
