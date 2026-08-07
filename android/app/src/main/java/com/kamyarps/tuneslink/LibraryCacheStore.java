package com.kamyarps.tuneslink;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded, bridge-scoped disk cache for library pages displayed while fresh data is fetched. */
final class LibraryCacheStore implements AutoCloseable {
    private static final String DATABASE_NAME = "TunesLink-library-cache.db";
    private static final int DATABASE_VERSION = 1;
    private static final int KIND_TRACKS = 1;
    private static final int KIND_COLLECTIONS = 2;
    private static final int MAX_PAGES = 256;
    private static final long MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;

    interface Loaded<T> { void accept(T value); }

    interface LoadHandle {
        LoadHandle NONE = () -> { };
        void cancel();
    }

    private final Database database;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean closed = new AtomicBoolean();

    LibraryCacheStore(Context context) {
        database = new Database(context.getApplicationContext());
    }

    LoadHandle loadTracks(String scope, String requestKey,
                          Loaded<BridgeClient.LibraryPage> loaded) {
        return load(scope, requestKey, KIND_TRACKS, json ->
                BridgeClient.parseLibraryPage(json, json.optInt("limit", 60)), loaded);
    }

    LoadHandle loadCollections(String scope, String requestKey,
                               Loaded<BridgeClient.LibraryCollectionPage> loaded) {
        return load(scope, requestKey, KIND_COLLECTIONS, json ->
                BridgeClient.parseLibraryCollectionPage(json, json.optInt("limit", 60)), loaded);
    }

    void saveTracks(String scope, String requestKey, BridgeClient.LibraryPage page) {
        try {
            save(scope, requestKey, KIND_TRACKS, page.revision, tracksJson(page));
        } catch (JSONException ignored) {
            // Invalid metadata is never allowed to interrupt the authoritative network result.
        }
    }

    void saveCollections(String scope, String requestKey,
                         BridgeClient.LibraryCollectionPage page) {
        try {
            save(scope, requestKey, KIND_COLLECTIONS, page.revision, collectionsJson(page));
        } catch (JSONException ignored) {
            // Invalid metadata is never allowed to interrupt the authoritative network result.
        }
    }

    void clearScope(String scope) {
        if (scope == null || scope.isBlank() || closed.get()) return;
        executor.execute(() -> database.getWritableDatabase().delete(
                "pages", "scope = ?", new String[] { scope }));
    }

    private interface Parser<T> { T parse(JSONObject json); }

    private <T> LoadHandle load(String scope, String requestKey, int kind, Parser<T> parser,
                                Loaded<T> loaded) {
        if (closed.get()) return LoadHandle.NONE;
        AtomicBoolean cancelled = new AtomicBoolean();
        executor.execute(() -> {
            T value = null;
            long now = System.currentTimeMillis();
            SQLiteDatabase db = database.getWritableDatabase();
            try (Cursor cursor = db.query("pages", new String[] { "payload", "stored_at" },
                    "scope = ? AND request_key = ? AND kind = ?",
                    new String[] { scope, requestKey, Integer.toString(kind) },
                    null, null, null, "1")) {
                if (cursor.moveToFirst()) {
                    long storedAt = cursor.getLong(1);
                    if (now - storedAt <= MAX_AGE_MS) {
                        value = parser.parse(new JSONObject(cursor.getString(0)));
                        ContentValues access = new ContentValues();
                        access.put("accessed_at", now);
                        db.update("pages", access,
                                "scope = ? AND request_key = ? AND kind = ?",
                                new String[] { scope, requestKey, Integer.toString(kind) });
                    } else {
                        db.delete("pages", "scope = ? AND request_key = ? AND kind = ?",
                                new String[] { scope, requestKey, Integer.toString(kind) });
                    }
                }
            } catch (Exception ignored) {
                // A corrupt or obsolete cache entry is a miss; the network remains authoritative.
            }
            T result = value;
            main.post(() -> {
                if (!cancelled.get() && !closed.get()) loaded.accept(result);
            });
        });
        return () -> cancelled.set(true);
    }

    private void save(String scope, String requestKey, int kind, String revision,
                      JSONObject payload) {
        if (closed.get()) return;
        executor.execute(() -> {
            long now = System.currentTimeMillis();
            SQLiteDatabase db = database.getWritableDatabase();
            db.beginTransaction();
            try {
                if (revision != null && !revision.isBlank()) {
                    db.delete("pages", "scope = ? AND revision <> ? AND revision <> ''",
                            new String[] { scope, revision });
                }
                ContentValues values = new ContentValues();
                values.put("scope", scope);
                values.put("request_key", requestKey);
                values.put("kind", kind);
                values.put("revision", revision == null ? "" : revision);
                values.put("payload", payload.toString());
                values.put("stored_at", now);
                values.put("accessed_at", now);
                db.insertWithOnConflict("pages", null, values,
                        SQLiteDatabase.CONFLICT_REPLACE);
                db.delete("pages", "stored_at < ?",
                        new String[] { Long.toString(now - MAX_AGE_MS) });
                db.execSQL("DELETE FROM pages WHERE rowid IN (SELECT rowid FROM pages "
                        + "ORDER BY accessed_at DESC LIMIT -1 OFFSET " + MAX_PAGES + ")");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        });
    }

    private static JSONObject tracksJson(BridgeClient.LibraryPage page) throws JSONException {
        JSONObject json = pageHeader(page.offset, page.limit, page.total, page.hasMore,
                page.revision);
        JSONArray items = new JSONArray();
        for (BridgeClient.LibraryTrack track : page.items) {
            JSONObject item = new JSONObject();
            item.put("id", track.id);
            item.put("title", track.title);
            item.put("artist", track.artist);
            item.put("album", track.album);
            item.put("duration", track.duration);
            item.put("trackNumber", track.trackNumber);
            item.put("discNumber", track.discNumber);
            item.put("artworkId", track.artworkId);
            items.put(item);
        }
        json.put("items", items);
        return json;
    }

    private static JSONObject collectionsJson(BridgeClient.LibraryCollectionPage page)
            throws JSONException {
        JSONObject json = pageHeader(page.offset, page.limit, page.total, page.hasMore,
                page.revision);
        JSONArray items = new JSONArray();
        for (BridgeClient.LibraryCollection collection : page.items) {
            JSONObject item = new JSONObject();
            item.put("id", collection.id);
            item.put("title", collection.title);
            item.put("subtitle", collection.subtitle);
            item.put("trackCount", collection.trackCount);
            item.put("artworkId", collection.artworkId);
            items.put(item);
        }
        json.put("items", items);
        return json;
    }

    private static JSONObject pageHeader(int offset, int limit, int total, boolean hasMore,
                                         String revision) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("offset", offset);
        json.put("limit", limit);
        json.put("total", total);
        json.put("hasMore", hasMore);
        json.put("revision", revision == null ? "" : revision);
        return json;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        executor.execute(database::close);
        executor.shutdown();
    }

    private static final class Database extends SQLiteOpenHelper {
        Database(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE pages (scope TEXT NOT NULL, request_key TEXT NOT NULL, "
                    + "kind INTEGER NOT NULL, revision TEXT NOT NULL, payload TEXT NOT NULL, "
                    + "stored_at INTEGER NOT NULL, accessed_at INTEGER NOT NULL, "
                    + "PRIMARY KEY(scope, request_key, kind))");
            db.execSQL("CREATE INDEX pages_accessed_at ON pages(accessed_at)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS pages");
            onCreate(db);
        }
    }
}
