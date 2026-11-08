package com.kamyarps.tuneslink;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Runs the production save/load and SQLiteOpenHelper upgrade paths on Android. */
public final class LibraryCacheDatabaseTest {
    private Context context;
    private LibraryCacheStore cache;

    @Before public void isolateDatabase() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(target.getCacheDir(), "cache-test-" + UUID.randomUUID());
        assertTrue(directory.mkdirs());
        // Each test owns a separate database, including its asynchronous close operation.
        context = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getDatabasePath(String name) { return new File(directory, name); }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                    SQLiteDatabase.CursorFactory factory) {
                return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory);
            }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                    SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler handler) {
                return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(), factory, handler);
            }
        };
        cache = new LibraryCacheStore(context);
    }

    @After public void closeDatabase() { if (cache != null) cache.close(); }

    @Test public void unrelatedRevisionProducersDoNotEvictEachOther() throws Exception {
        save("pc", "songs", "", "", "snapshot-1");
        save("pc", "albums", "", "", "snapshot-1");
        save("pc", "playlists", "", "", "lists-1");
        save("pc", "playlists", "", "rock", "filtered-1");
        save("pc", "playlists", "p1", "", "p1-1");
        save("pc", "playlists", "p2", "", "p2-1");
        assertNotNull(load("pc", "songs", "", ""));
        assertNotNull(load("pc", "albums", "", ""));
        assertNotNull(load("pc", "playlists", "", ""));
        assertNotNull(load("pc", "playlists", "", "rock"));
        assertNotNull(load("pc", "playlists", "p1", ""));
        assertNotNull(load("pc", "playlists", "p2", ""));

        save("pc", "playlists", "p1", "new", "p1-2");
        assertNull(load("pc", "playlists", "p1", ""));
        assertNotNull(load("pc", "playlists", "p1", "new"));
        assertNotNull(load("pc", "playlists", "p2", ""));
        assertNotNull(load("pc", "songs", "", ""));
        assertNotNull(load("pc", "playlists", "", "rock"));
    }

    @Test public void newSnapshotEvictsOnlyItsOwnOldPages() throws Exception {
        save("pc", "songs", "", "", "old");
        save("pc", "albums", "", "", "old");
        save("pc", "songs", "", "unknown", "");
        save("other-pc", "songs", "", "", "old");
        save("pc", "playlists", "", "", "different");
        save("pc", "songs", "", "new", "new");
        assertNull(load("pc", "songs", "", ""));
        assertNull(load("pc", "albums", "", ""));
        assertNotNull(load("pc", "songs", "", "unknown"));
        assertNotNull(load("other-pc", "songs", "", ""));
        assertNotNull(load("pc", "playlists", "", ""));
        assertNotNull(load("pc", "songs", "", "new"));
    }

    @Test public void versionOneCacheIsRebuiltAndCanStoreNewPages() throws Exception {
        try (SQLiteDatabase old = context.openOrCreateDatabase("TunesLink-library-cache.db", 0, null)) {
            old.execSQL("CREATE TABLE pages (scope TEXT NOT NULL, request_key TEXT NOT NULL, "
                    + "kind INTEGER NOT NULL, revision TEXT NOT NULL, payload TEXT NOT NULL, "
                    + "stored_at INTEGER NOT NULL, accessed_at INTEGER NOT NULL, "
                    + "PRIMARY KEY(scope, request_key, kind))");
            old.execSQL("CREATE INDEX pages_accessed_at ON pages(accessed_at)");
            old.execSQL("INSERT INTO pages VALUES (?, ?, 1, 'old', '{}', ?, ?)",
                    new Object[] { "pc", key("songs", "", "old"),
                            System.currentTimeMillis(), System.currentTimeMillis() });
            old.setVersion(1);
        }
        assertNull(load("pc", "songs", "", "old"));
        save("pc", "songs", "", "fresh", "new");
        BridgeClient.LibraryPage page = (BridgeClient.LibraryPage) load("pc", "songs", "", "fresh");
        assertNotNull(page);
        assertEquals("new", page.revision);
    }

    private static String key(String kind, String id, String query) {
        return kind + "\u001f" + id + "\u001f" + query + "\u001f0\u001f60";
    }

    private void save(String scope, String kind, String id, String query, String revision) {
        if (kind.equals("songs") || !id.isEmpty()) {
            cache.saveTracks(scope, key(kind, id, query),
                    new BridgeClient.LibraryPage(List.of(), 0, 60, 0, false, revision));
        } else {
            cache.saveCollections(scope, key(kind, id, query),
                    new BridgeClient.LibraryCollectionPage(List.of(), 0, 60, 0, false, revision));
        }
    }

    private Object load(String scope, String kind, String id, String query) throws Exception {
        AtomicReference<Object> result = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        if (kind.equals("songs") || !id.isEmpty()) {
            cache.loadTracks(scope, key(kind, id, query), page -> {
                result.set(page);
                finished.countDown();
            });
        } else {
            cache.loadCollections(scope, key(kind, id, query), page -> {
                result.set(page);
                finished.countDown();
            });
        }
        assertTrue("Cache read did not finish", finished.await(5, TimeUnit.SECONDS));
        return result.get();
    }
}
