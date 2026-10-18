package com.kamyarps.tuneslink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Runs only in the Google Play build (-PTunesLink.playDemo=true). */
public final class DemoCatalogTest {
    private final DemoCatalog catalog = new DemoCatalog();

    @Test
    public void demoAddressIsRecognizedOnlyInThePlayBuild() {
        assertTrue(DemoBridge.ENABLED);
        assertTrue(DemoBridge.isDemoAddress(" Demo "));
        assertTrue(DemoBridge.isDemoAddress("demo:45832"));
        assertFalse(DemoBridge.isDemoAddress("192.168.1.20"));
        assertFalse(DemoBridge.isDemoAddress("demos"));
        assertTrue(DemoBridge.isDemoHost(DemoBridge.info().host));
        assertTrue(BridgeClient.isValidFingerprint(DemoBridge.info().tlsFingerprint));
        assertTrue(BridgeClient.isValidBridgeId(DemoBridge.info().id));
    }

    @Test
    public void everySongHasArtworkAndAUniqueId() {
        Set<String> ids = new HashSet<>();
        for (DemoCatalog.Track track : catalog.tracks) {
            assertTrue(ids.add(track.id()));
            assertTrue(catalog.hasArtwork(track.artworkId()));
            assertNotNull("Missing cover " + track.artworkId(), DemoCatalogTest.class
                    .getResourceAsStream("/tuneslink-demo/" + track.artworkId() + ".jpg"));
            assertTrue(track.duration() > 0);
        }
        assertEquals(25, ids.size());
    }

    @Test
    public void songsAreSortedAndPaged() {
        DemoCatalog.Page<DemoCatalog.Track> first = catalog.songs("", 0, 10);
        assertEquals(25, first.total());
        assertEquals(10, first.items().size());
        assertTrue(first.hasMore());
        assertEquals("Afterimage", first.items().get(0).title());
        DemoCatalog.Page<DemoCatalog.Track> rest = catalog.songs("", 20, 10);
        assertEquals(5, rest.items().size());
        assertFalse(rest.hasMore());
        assertEquals(25, catalog.songs("", 0, 500).items().size());
    }

    @Test
    public void searchMatchesTitlesArtistsAndAlbums() {
        assertEquals(5, catalog.songs("neon", 0, 60).total());
        assertEquals(1, catalog.songs("ORBIT", 0, 60).total());
        assertEquals(3, catalog.songs("tidewater", 0, 60).total());
        assertEquals(0, catalog.songs("nothing like this", 0, 60).total());
    }

    @Test
    public void collectionsBrowseDownToSongs() {
        assertEquals(8, catalog.collections("albums", "", 0, 60).total());
        assertEquals(8, catalog.collections("artists", "", 0, 60).total());
        assertEquals(8, catalog.collections("genres", "", 0, 60).total());
        assertEquals(3, catalog.collections("playlists", "", 0, 60).total());
        assertNull(catalog.collections("podcasts", "", 0, 60));

        DemoCatalog.Collection artist = catalog.collections("artists", "Neon", 0, 60).items().get(0);
        DemoCatalog.Page<DemoCatalog.Collection> albums =
                catalog.collectionAlbums("artists", artist.id(), "", 0, 60);
        assertEquals(1, albums.total());
        DemoCatalog.Collection album = albums.items().get(0);
        assertEquals("Afterglow Avenue", album.title());
        assertEquals("Neon Palms", album.subtitle());

        List<DemoCatalog.Track> songs =
                catalog.collectionSongs("albums", album.id(), "", 0, 60).items();
        assertEquals(List.of("Midnight Drive", "Glass City", "Low Tide Lights", "Slow Fade"),
                songs.stream().map(DemoCatalog.Track::title).toList());
        assertEquals(1, songs.get(0).trackNumber());
        assertNull(catalog.collectionSongs("albums", "albums-missing", "", 0, 60));
        assertNull(catalog.collectionAlbums("albums", album.id(), "", 0, 60));
    }

    @Test
    public void playerAdvancesWithTimeAndStopsAtTheEndOfTheQueue() {
        AtomicLong now = new AtomicLong(1_000);
        DemoCatalog.Player player = new DemoCatalog.Player(catalog, now::get);
        DemoCatalog.State start = player.state();
        assertTrue(start.playing());
        assertEquals("Midnight Drive", start.track().title());
        assertEquals(72, start.position(), 0.001);

        now.addAndGet(10_000);
        assertEquals(82, player.state().position(), 0.001);
        now.addAndGet((244 - 82 + 5) * 1000L);
        DemoCatalog.State next = player.state();
        assertEquals("Glass City", next.track().title());
        assertEquals(5, next.position(), 0.001);

        assertTrue(player.command("playPause", null));
        now.addAndGet(60_000);
        assertFalse(player.state().playing());
        assertEquals(5, player.state().position(), 0.001);

        DemoCatalog.Collection album = catalog.collections("albums", "Afterglow", 0, 1).items().get(0);
        assertTrue(player.play("demo-slow-fade", "albums", album.id()));
        now.addAndGet(240_000);
        DemoCatalog.State stopped = player.state();
        assertFalse(stopped.playing());
        assertEquals("Midnight Drive", stopped.track().title());
    }

    @Test
    public void commandsUpdateThePlayer() {
        AtomicLong now = new AtomicLong();
        DemoCatalog.Player player = new DemoCatalog.Player(catalog, now::get);
        assertTrue(player.command("volume", 140.0));
        assertEquals(100, player.state().volume());
        assertTrue(player.command("position", 30.0));
        assertEquals(30, player.state().position(), 0.001);
        assertTrue(player.command("repeat", 1.0));
        assertEquals("one", player.state().repeatMode());
        now.addAndGet(300_000);
        assertEquals("Midnight Drive", player.state().track().title());
        assertTrue(player.command("shuffle", 1.0));
        assertTrue(player.state().shuffle());
        assertTrue(player.command("next", null));
        assertFalse("Midnight Drive".equals(player.state().track().title()));
        assertTrue(player.command("previous", null));
        assertFalse(player.command("volume", null));
        assertFalse(player.command("eject", null));
        assertFalse(player.play("demo-missing", "", ""));
        assertTrue(player.play("demo-orbit", "", ""));
        assertEquals("Orbit", player.state().track().title());
    }
}
