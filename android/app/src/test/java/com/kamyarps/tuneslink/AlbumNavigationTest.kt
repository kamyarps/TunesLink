package com.kamyarps.tuneslink

import org.junit.Assert.*
import org.junit.Test

class AlbumNavigationTest {
    @Test
    fun artistsAndGenresOpenAnAlbumListWithTheirOwnPagingAndParentHistory() {
        for (kind in listOf(LibraryBrowseKind.Artists, LibraryBrowseKind.Genres)) {
            val parentCollection = LibraryCollectionUiState("parent", "Parent", "", 200, "art")
            val original = LibraryBrowseUiState(kind = kind, collections = listOf(parentCollection),
                collectionScrollIndex = 8, collectionScrollOffset = 24,
                collectionsCursor = LibraryPageCursor(total = 600, windowStart = 120,
                    hasPrevious = true, hasMore = true, isLoadingMore = true))
            val albums = original.openAlbums(parentCollection)
            assertEquals(LibraryBrowseKind.Albums, albums.kind)
            assertEquals(kind, albums.albumParent?.kind)
            assertEquals("parent", albums.albumParent?.id)
            assertFalse(albums.showingTracks)
            assertTrue(albums.visibleItemsEmpty)
            assertEquals(0, albums.visibleCursor.windowStart)
            assertEquals(original.collections, albums.parentBrowse?.collections)
            assertEquals(120, albums.parentBrowse?.visibleCursor?.windowStart)
            assertFalse(albums.parentBrowse!!.visibleCursor.isBusy)
            assertEquals(8, albums.parentBrowse.collectionScrollIndex)
            assertEquals(24, albums.parentBrowse.collectionScrollOffset)
        }
    }

    @Test
    fun openingAnAlbumUsesAlbumPlaybackContextAndRetainsArtistNavigation() {
        val parent = LibraryBrowseUiState(kind = LibraryBrowseKind.Artists)
        val albums = parent.openAlbums(LibraryCollectionUiState("artist", "Artist", "", 200, ""))
        val detail = albums.copy(selectedCollection = SelectedLibraryCollection(
            LibraryBrowseKind.Albums, "album", "Album", "Artist"),
            tracksCursor = LibraryPageCursor(total = 120, hasMore = true))
        assertTrue(detail.showingTracks)
        assertEquals(LibraryBrowseTarget.Tracks, detail.visibleTarget)
        assertEquals(LibraryBrowseKind.Albums, detail.selectedCollection?.kind)
        assertEquals("artist", detail.albumParent?.id)
        assertFalse(detail.copy(selectedCollection = null).showingTracks)
    }
}
