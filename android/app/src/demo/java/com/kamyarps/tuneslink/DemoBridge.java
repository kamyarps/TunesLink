package com.kamyarps.tuneslink;

import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Google Play reviewer demo: typing "demo" as the computer address pairs with this in-process
 * bridge instead of a PC. It answers the same HTTP API as TunesLink Bridge, so every screen runs
 * unchanged, and it never opens a network connection.
 *
 * <p>Only builds made with {@code -PTunesLink.playDemo=true} (the Google Play bundle) compile this
 * file. Every other build compiles the stub in {@code src/demoStub}, so GitHub APKs contain no
 * demo code. To remove demo mode, delete {@code src/demo}, {@code src/demoStub},
 * {@code src/testDemo}, and the {@code TunesLink.playDemo} block in {@code app/build.gradle}.
 */
final class DemoBridge {
    static final boolean ENABLED = true;
    static final String HOST = "demo";

    private static final String ID = "tuneslink-demo";
    private static final String NAME = "Living Room PC";
    private static final String FINGERPRINT =
            "DE30DE30DE30DE30DE30DE30DE30DE30DE30DE30DE30DE30DE30DE30DE30DE30";
    private static final String TOKEN = "tuneslink-demo-token-0000000000000000";
    private static final DemoCatalog CATALOG = new DemoCatalog();
    private static final DemoCatalog.Player PLAYER =
            new DemoCatalog.Player(CATALOG, SystemClock::elapsedRealtime);

    private DemoBridge() {
    }

    /** "demo", or "demo:port" when the app reconnects to the saved demo computer. */
    static boolean isDemoAddress(String input) {
        if (input == null) return false;
        String address = input.trim().toLowerCase(Locale.ROOT);
        int colon = address.indexOf(':');
        return HOST.equals(colon < 0 ? address : address.substring(0, colon));
    }

    static boolean isDemoHost(String host) {
        return HOST.equals(host);
    }

    static BridgeClient.BridgeInfo info() {
        return new BridgeClient.BridgeInfo(ID, NAME, HOST, BridgeClient.DEFAULT_PORT, FINGERPRINT);
    }

    static BridgeHttpClient.Response respond(String method, String target, byte[] body,
                                             String token) throws IOException {
        try {
            String path = target.split("\\?", 2)[0];
            String route = method + " " + path;
            if (!route.equals("GET /api/info") && !route.equals("POST /api/pair")
                    && token == null) {
                return error(401, "not_paired", "This phone is no longer paired");
            }
            return switch (route) {
                case "GET /api/info" -> json(new JSONObject()
                        .put("protocol", BridgeClient.PROTOCOL)
                        .put("id", ID)
                        .put("name", NAME)
                        .put("port", BridgeClient.DEFAULT_PORT)
                        .put("tlsFingerprint", FINGERPRINT));
                case "POST /api/pair" -> json(new JSONObject().put("token", TOKEN));
                case "DELETE /api/pairing/self" -> new BridgeHttpClient.Response(204,
                        new byte[0], "");
                case "GET /api/state" -> json(state(PLAYER.state()));
                case "GET /api/library" -> library(target);
                case "GET /api/collections" -> collections(target);
                case "GET /api/collection-albums" -> collectionAlbums(target);
                case "GET /api/artwork" -> artwork(query(target, "id"));
                case "POST /api/play" -> play(body);
                case "POST /api/play/cancel" -> json(new JSONObject().put("ok", true));
                case "POST /api/command" -> command(body);
                default -> error(404, "not_found", "Not found");
            };
        } catch (JSONException invalid) {
            return error(400, "invalid_request", "Invalid request");
        }
    }

    private static BridgeHttpClient.Response library(String target) throws JSONException {
        String kind = query(target, "collectionKind");
        String search = query(target, "query");
        int offset = number(target, "offset", 0);
        int limit = number(target, "limit", 40);
        DemoCatalog.Page<DemoCatalog.Track> page = kind.isEmpty()
                ? CATALOG.songs(search, offset, limit)
                : CATALOG.collectionSongs(kind, query(target, "collectionId"), search, offset,
                        limit);
        if (page == null) return error(404, "not_found", "That collection is no longer available");
        JSONArray items = new JSONArray();
        for (DemoCatalog.Track track : page.items()) {
            items.put(new JSONObject()
                    .put("id", track.id())
                    .put("title", track.title())
                    .put("artist", track.artist())
                    .put("album", track.album())
                    .put("albumArtist", track.artist())
                    .put("duration", track.duration())
                    .put("trackNumber", track.trackNumber())
                    .put("discNumber", 1)
                    .put("artworkId", track.artworkId()));
        }
        return json(page(items, page));
    }

    private static BridgeHttpClient.Response collections(String target) throws JSONException {
        DemoCatalog.Page<DemoCatalog.Collection> page = CATALOG.collections(
                query(target, "kind"), query(target, "query"), number(target, "offset", 0),
                number(target, "limit", 40));
        if (page == null) return error(400, "invalid_request", "Unknown library collection");
        return json(page(collectionItems(page), page));
    }

    private static BridgeHttpClient.Response collectionAlbums(String target)
            throws JSONException {
        DemoCatalog.Page<DemoCatalog.Collection> page = CATALOG.collectionAlbums(
                query(target, "kind"), query(target, "id"), query(target, "query"),
                number(target, "offset", 0), number(target, "limit", 40));
        if (page == null) return error(400, "invalid_request", "Invalid album collection");
        return json(page(collectionItems(page), page));
    }

    private static JSONArray collectionItems(DemoCatalog.Page<DemoCatalog.Collection> page)
            throws JSONException {
        JSONArray items = new JSONArray();
        for (DemoCatalog.Collection collection : page.items()) {
            items.put(new JSONObject()
                    .put("id", collection.id())
                    .put("title", collection.title())
                    .put("subtitle", collection.subtitle())
                    .put("trackCount", collection.tracks().size())
                    .put("artworkId", collection.artworkId()));
        }
        return items;
    }

    private static JSONObject page(JSONArray items, DemoCatalog.Page<?> page)
            throws JSONException {
        return new JSONObject()
                .put("items", items)
                .put("offset", page.offset())
                .put("limit", page.limit())
                .put("total", page.total())
                .put("hasMore", page.hasMore())
                .put("revision", DemoCatalog.REVISION);
    }

    private static BridgeHttpClient.Response artwork(String id) throws IOException {
        if (!CATALOG.hasArtwork(id)) return error(404, "not_found", "Artwork not found");
        try (InputStream input = DemoBridge.class.getResourceAsStream(
                "/tuneslink-demo/" + id + ".jpg")) {
            if (input == null) return error(404, "not_found", "Artwork not found");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(256 * 1024);
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) bytes.write(buffer, 0, read);
            return new BridgeHttpClient.Response(200, bytes.toByteArray(), "image/jpeg");
        }
    }

    private static BridgeHttpClient.Response play(byte[] body) throws JSONException {
        JSONObject request = new JSONObject(new String(body, StandardCharsets.UTF_8));
        boolean played = PLAYER.play(request.optString("trackId"),
                request.optString("collectionKind"), request.optString("collectionId"));
        return played ? json(new JSONObject().put("ok", true))
                : error(404, "not_found", "That song is no longer available");
    }

    private static BridgeHttpClient.Response command(byte[] body) throws JSONException {
        JSONObject request = new JSONObject(new String(body, StandardCharsets.UTF_8));
        Double value = request.has("value") ? request.getDouble("value") : null;
        return PLAYER.command(request.optString("command"), value)
                ? json(new JSONObject().put("ok", true))
                : error(400, "invalid_request", "Unknown command");
    }

    private static JSONObject state(DemoCatalog.State state) throws JSONException {
        DemoCatalog.Track track = state.track();
        return new JSONObject()
                .put("iTunesAvailable", true)
                .put("playing", state.playing())
                .put("title", track.title())
                .put("artist", track.artist())
                .put("album", track.album())
                .put("duration", track.duration())
                .put("position", state.position())
                .put("volume", state.volume())
                .put("artworkId", track.artworkId())
                .put("trackId", track.id())
                .put("shuffleEnabled", state.shuffle())
                .put("repeatMode", state.repeatMode());
    }

    private static BridgeHttpClient.Response json(JSONObject value) {
        return new BridgeHttpClient.Response(200, value.toString().getBytes(StandardCharsets.UTF_8),
                "application/json; charset=utf-8");
    }

    private static BridgeHttpClient.Response error(int status, String code, String message) {
        String body;
        try {
            body = new JSONObject().put("error", message).put("code", code).toString();
        } catch (JSONException impossible) {
            body = "{}";
        }
        return new BridgeHttpClient.Response(status, body.getBytes(StandardCharsets.UTF_8),
                "application/json; charset=utf-8");
    }

    private static String query(String target, String name) {
        int start = target.indexOf('?');
        if (start < 0) return "";
        for (String pair : target.substring(start + 1).split("&")) {
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            if (!key.equals(name)) continue;
            try {
                return URLDecoder.decode(equals < 0 ? "" : pair.substring(equals + 1), "UTF-8");
            } catch (UnsupportedEncodingException | IllegalArgumentException invalid) {
                return "";
            }
        }
        return "";
    }

    private static int number(String target, String name, int fallback) {
        try {
            return Integer.parseInt(query(target, name));
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }
}
