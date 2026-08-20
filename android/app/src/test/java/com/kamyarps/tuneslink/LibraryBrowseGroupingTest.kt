package com.kamyarps.tuneslink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryBrowseGroupingTest {
    private fun track(
        id: String,
        album: String,
        trackNumber: Int = 0,
        artworkId: String = id,
    ) = TrackUiState(
        id = id,
        title = "Song $id",
        artist = "Performer",
        album = album,
        duration = 180.0,
        artworkId = artworkId,
        trackNumber = trackNumber,
    )

    @Test
    fun songsAreGroupedIntoTheAlbumsTheyArriveIn() {
        val albums = libraryBrowseAlbums(
            listOf(
                track("1", "Night Ferry", trackNumber = 1),
                track("2", "Night Ferry", trackNumber = 2),
                track("3", "Signals", trackNumber = 1),
            ),
        )

        assertEquals(listOf("Night Ferry", "Signals"), albums.map { it.heading.album })
        assertEquals(listOf(2, 1), albums.map { it.heading.trackCount })
        assertEquals(listOf("1", "2"), albums[0].songs.map { it.track.id })
        assertEquals(listOf("3"), albums[1].songs.map { it.track.id })
    }

    @Test
    fun groupingNeverReordersWhatTheBridgeSent() {
        // Playback follows the order the bridge returned, so the list has to show that same order
        // even when an album's songs are not all next to each other.
        val sent = listOf(
            track("1", "Night Ferry"),
            track("2", "Signals"),
            track("3", "Night Ferry"),
        )

        val songs = libraryBrowseRows(sent).filterIsInstance<LibraryBrowseRow.Song>()

        assertEquals(sent.map { it.id }, songs.map { it.track.id })
        assertEquals(3, libraryBrowseAlbums(sent).size)
    }

    @Test
    fun everyRowKeyIsUniqueEvenWhenAnAlbumTitleRepeats() {
        val rows = libraryBrowseRows(
            listOf(
                track("1", "Greatest Hits"),
                track("2", "Signals"),
                track("3", "Greatest Hits"),
            ),
        )

        assertEquals(rows.size, rows.map { it.key }.distinct().size)
    }

    @Test
    fun aSongWithoutATrackNumberFallsBackToItsPlaceInTheAlbum() {
        val songs = libraryBrowseAlbums(
            listOf(
                track("1", "Night Ferry", trackNumber = 0),
                track("2", "Night Ferry", trackNumber = 7),
                track("3", "Night Ferry", trackNumber = 0),
            ),
        ).single().songs

        assertEquals(listOf(1, 7, 3), songs.map { it.position })
    }

    @Test
    fun anAlbumHeadingUsesTheFirstArtworkItCanShow() {
        val heading = libraryBrowseAlbums(
            listOf(
                track("1", "Night Ferry", artworkId = ""),
                track("2", "Night Ferry", artworkId = "art-2"),
            ),
        ).single().heading

        assertEquals("art-2", heading.artworkId)
    }

    @Test
    fun emptyCollectionsProduceNoRows() {
        assertTrue(libraryBrowseAlbums(emptyList()).isEmpty())
        assertTrue(libraryBrowseRows(emptyList()).isEmpty())
    }

    @Test
    fun onlyArtistsAndGenresAreGroupedIntoAlbums() {
        fun browse(kind: LibraryBrowseKind?) = LibraryBrowseUiState(
            kind = kind,
            selectedCollection = kind?.let {
                SelectedLibraryCollection(it, "id", "Title", "Subtitle")
            },
        )

        assertTrue(browse(LibraryBrowseKind.Artists).groupsTracksByAlbum())
        assertTrue(browse(LibraryBrowseKind.Genres).groupsTracksByAlbum())
        assertFalse(browse(LibraryBrowseKind.Albums).groupsTracksByAlbum())
        assertFalse(browse(LibraryBrowseKind.Playlists).groupsTracksByAlbum())
        assertFalse(browse(null).groupsTracksByAlbum())
    }
}
