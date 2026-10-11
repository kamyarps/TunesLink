package com.kamyarps.tuneslink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class LibraryCacheRevisionTest {
    private static String key(String kind, String id, String query, int offset) {
        return kind + "\u001f" + id + "\u001f" + query + "\u001f" + offset + "\u001f60";
    }

    @Test public void allLibraryViewsShareTheSnapshotRevisionDomain() {
        String domain = LibraryCacheStore.revisionDomain(key("songs", "", "", 0));
        for (String kind : new String[] { "albums", "artists", "genres" }) {
            assertEquals(domain, LibraryCacheStore.revisionDomain(key(kind, "", "search", 60)));
            assertEquals(domain, LibraryCacheStore.revisionDomain(key(kind, "collection", "", 120)));
        }
    }

    @Test public void playlistTracksAndFilteredListsHaveIndependentRevisions() {
        String library = LibraryCacheStore.revisionDomain(key("songs", "", "", 0));
        String list = LibraryCacheStore.revisionDomain(key("playlists", "", "", 0));
        String filtered = LibraryCacheStore.revisionDomain(key("playlists", "", "rock", 0));
        String playlist = LibraryCacheStore.revisionDomain(key("playlists", "p1", "", 0));
        String other = LibraryCacheStore.revisionDomain(key("playlists", "p2", "", 0));
        assertNotEquals(library, list);
        assertNotEquals(list, filtered);
        assertNotEquals(list, playlist);
        assertNotEquals(playlist, other);
        assertEquals(list, LibraryCacheStore.revisionDomain(key("playlists", "", "", 60)));
        assertEquals(playlist, LibraryCacheStore.revisionDomain(key("playlists", "p1", "rock", 60)));
    }

    @Test public void unrecognizedRequestsCannotEvictKnownRevisionDomains() {
        assertNotEquals("library", LibraryCacheStore.revisionDomain("old-format"));
        assertNotEquals("library", LibraryCacheStore.revisionDomain(key("future", "", "", 0)));
        assertNotEquals(LibraryCacheStore.revisionDomain("a"), LibraryCacheStore.revisionDomain("b"));
    }
}
