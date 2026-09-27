package com.kamyarps.tuneslink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TunesLinkLayoutBehaviorTest {
    private val album = SelectedLibraryCollection(LibraryBrowseKind.Albums, "album", "Album", "Artist")

    @Test
    fun workspaceBackOnlyGoesUpFromAnOpenedCollectionOrDrilledInParent() {
        val workspaceRoot = LibraryBrowseUiState(kind = LibraryBrowseKind.Albums)
        // The phone layout treats a category as navigable back to the Library root...
        assertTrue(browseBackEnabled(TunesLinkDestination.Library, workspaceRoot, tabletWorkspace = false))
        // ...but in the workspace a category is the root, so the system handles Back (exit).
        assertFalse(browseBackEnabled(TunesLinkDestination.Library, workspaceRoot, tabletWorkspace = true))
        assertTrue(
            browseBackEnabled(
                TunesLinkDestination.Library,
                workspaceRoot.copy(selectedCollection = album),
                tabletWorkspace = true,
            ),
        )
        val drilledIn = LibraryBrowseUiState(
            kind = LibraryBrowseKind.Albums,
            albumParent = SelectedLibraryCollection(LibraryBrowseKind.Artists, "artist", "Artist", ""),
            parentBrowse = LibraryBrowseUiState(kind = LibraryBrowseKind.Artists),
        )
        assertTrue(browseBackEnabled(TunesLinkDestination.Library, drilledIn, tabletWorkspace = true))
        assertFalse(browseBackEnabled(TunesLinkDestination.Search, drilledIn, tabletWorkspace = true))
    }

    @Test
    fun workspaceNowPlayingBacksLikeLibraryButPhoneNowPlayingReturnsToLibrary() {
        assertEquals(
            TunesLinkDestination.Library,
            effectiveBackDestination(TunesLinkDestination.NowPlaying, tabletWorkspace = true),
        )
        assertEquals(
            TunesLinkDestination.NowPlaying,
            effectiveBackDestination(TunesLinkDestination.NowPlaying, tabletWorkspace = false),
        )
        // At the workspace root with no search, nothing intercepts Back.
        val destination = effectiveBackDestination(TunesLinkDestination.NowPlaying, tabletWorkspace = true)
        assertEquals(NavigationBackAction.System, navigationBackAction(false, false, destination))
    }

    @Test
    fun onlyAnUntouchedDefaultCategoryIsRestoredToTheLibraryRoot() {
        assertTrue(LibraryBrowseUiState(kind = LibraryBrowseKind.Albums).isWorkspaceDefault())
        assertFalse(LibraryBrowseUiState(kind = LibraryBrowseKind.Songs).isWorkspaceDefault())
        assertFalse(LibraryBrowseUiState().isWorkspaceDefault())
        assertFalse(
            LibraryBrowseUiState(kind = LibraryBrowseKind.Albums, selectedCollection = album).isWorkspaceDefault(),
        )
    }

    @Test
    fun emptyLibraryCategoriesHaveTheirOwnCopyAndCounts() {
        assertEquals(R.string.no_playlists, LibraryBrowseKind.Playlists.emptyCopy().title)
        assertEquals(R.string.no_artists, LibraryBrowseKind.Artists.emptyCopy().title)
        assertEquals(R.string.no_genres, LibraryBrowseKind.Genres.emptyCopy().title)
        // All songs must not be described as a "collection".
        assertEquals(R.string.no_songs_library_detail, LibraryBrowseKind.Songs.emptyCopy().detail)
        assertEquals(R.plurals.playlist_count, LibraryBrowseKind.Playlists.countPlural())
        assertEquals(R.plurals.artist_count, LibraryBrowseKind.Artists.countPlural())
        assertEquals(R.plurals.genre_count, LibraryBrowseKind.Genres.countPlural())
        assertEquals(R.plurals.album_count, LibraryBrowseKind.Albums.countPlural())
    }

    @Test
    fun theRootStateIgnoresPlaybackClockTicks() {
        val first = TunesLinkUiState(player = PlayerUiState(title = "Song", position = 12.0))
        val next = first.copy(player = first.player.copy(position = 12.75))
        assertEquals(first.withoutPlaybackClock(), next.withoutPlaybackClock())
        val unchanged = TunesLinkUiState()
        assertSame(unchanged, unchanged.withoutPlaybackClock())
    }

    @Test
    fun playbackClockAdvancesLocallyBetweenSamples() {
        val clock = PlaybackClock()
        clock.accept(sample(position = 10.0), nowMillis = 1_000)
        assertEquals(10.0, clock.positionAt(1_000), 0.001)
        assertEquals(10.5, clock.positionAt(1_500), 0.001)
        // A slightly-behind sample (sampling lag) does not pull the clock backwards.
        clock.accept(sample(position = 10.5), nowMillis = 1_750)
        assertEquals(10.75, clock.positionAt(1_750), 0.001)
    }

    @Test
    fun playbackClockSnapsWhenDriftExceedsOneSecondOrTheTrackChanges() {
        val clock = PlaybackClock()
        clock.accept(sample(position = 10.0), nowMillis = 0)
        clock.accept(sample(position = 42.0), nowMillis = 500)
        assertEquals(42.0, clock.positionAt(500), 0.001)
        clock.accept(sample(trackId = "other", position = 0.0), nowMillis = 600)
        assertEquals(0.0, clock.positionAt(600), 0.001)
    }

    @Test
    fun playbackClockStopsWhenPausedHeldOrOffline() {
        val clock = PlaybackClock()
        clock.accept(sample(position = 10.0), nowMillis = 0)
        clock.accept(sample(position = 10.4, playing = false), nowMillis = 400)
        assertFalse(clock.ticking)
        assertEquals(clock.positionAt(400), clock.positionAt(5_000), 0.001)

        clock.accept(sample(position = 30.0, held = true), nowMillis = 5_000)
        assertEquals(30.0, clock.positionAt(9_000), 0.001)

        clock.accept(sample(position = 30.0, live = false), nowMillis = 9_000)
        assertEquals(30.0, clock.positionAt(12_000), 0.001)
    }

    @Test
    fun playbackClockNeverRunsPastTheEndOfTheSong() {
        val clock = PlaybackClock()
        clock.accept(sample(position = 199.0, duration = 200.0), nowMillis = 0)
        assertEquals(200.0, clock.positionAt(10_000), 0.001)
    }

    @Test
    fun aReleasedScrubHoldsUntilTheBridgeConfirmsIt() {
        val clock = PlaybackClock()
        clock.accept(sample(position = 10.0), nowMillis = 0)
        clock.hold(90.0, nowMillis = 100)
        assertEquals(90.0, clock.positionAt(2_000), 0.001)
        clock.accept(sample(position = 90.2), nowMillis = 2_000)
        assertTrue(clock.ticking)
        assertEquals(91.0, clock.positionAt(3_000), 0.001)
    }

    @Test
    fun playbackSamplesHoldDuringAPendingSeek() {
        val seek = PendingMutation(1, PlaybackAction.Position, setOf(PlayerField.Position),
            expectedNumber = 90.0, startedAtMillis = 0)
        val state = TunesLinkUiState(
            connection = ConnectionState.Connected("PC"),
            player = PlayerUiState(playing = true, position = 90.0, trackId = "t",
                pendingMutations = mapOf(PlaybackAction.Position to seek)),
        )
        assertTrue(state.playbackSample().held)
        assertTrue(state.playbackSample().live)
        assertFalse(state.copy(connection = ConnectionState.Connecting).playbackSample().live)
    }

    private fun sample(
        trackId: String = "track",
        position: Double,
        duration: Double = 300.0,
        playing: Boolean = true,
        held: Boolean = false,
        live: Boolean = true,
    ) = PlaybackPositionSample(trackId, position, duration, playing, held, live)
}
