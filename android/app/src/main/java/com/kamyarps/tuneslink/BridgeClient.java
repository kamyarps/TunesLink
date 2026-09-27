package com.kamyarps.tuneslink;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;

class BridgeClient extends BridgeClientSupport {
    static final class BridgeInfo {
        final String id;
        final String name;
        final String host;
        final int port;
        final String tlsFingerprint;

        BridgeInfo(String id, String name, String host, int port, String tlsFingerprint) {
            this.id = id;
            this.name = name;
            this.host = host;
            this.port = port;
            this.tlsFingerprint = normalizeFingerprint(tlsFingerprint);
        }
    }

    static final class PlayerState {
        final boolean iTunesAvailable;
        final boolean playing;
        final String title;
        final String artist;
        final String album;
        final double duration;
        final double position;
        final int volume;
        final String artworkId;
        final String trackId;
        final boolean shuffleEnabled;
        final String repeatMode;

        PlayerState(boolean iTunesAvailable, boolean playing, String title, String artist,
                    String album, double duration, double position, int volume, String artworkId,
                    String trackId, boolean shuffleEnabled,
                    String repeatMode) {
            this.iTunesAvailable = iTunesAvailable;
            this.playing = playing;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.duration = duration;
            this.position = position;
            this.volume = volume;
            this.artworkId = artworkId;
            this.trackId = trackId;
            this.shuffleEnabled = shuffleEnabled;
            this.repeatMode = repeatMode;
        }
    }

    static final class LibraryTrack {
        final String id;
        final String title;
        final String artist;
        final String album;
        final double duration;
        final int trackNumber;
        final int discNumber;
        final String artworkId;
        final String albumArtist;

        LibraryTrack(String id, String title, String artist, String album, double duration,
                     int trackNumber, int discNumber, String artworkId, String albumArtist) {
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.duration = duration;
            this.trackNumber = trackNumber;
            this.discNumber = discNumber;
            this.artworkId = artworkId;
            this.albumArtist = albumArtist == null ? "" : albumArtist;
        }
    }

    static final class LibraryCollection {
        final String id;
        final String title;
        final String subtitle;
        final int trackCount;
        final String artworkId;

        LibraryCollection(String id, String title, String subtitle, int trackCount,
                          String artworkId) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.trackCount = trackCount;
            this.artworkId = artworkId;
        }
    }

    static final class LibraryCollectionPage {
        final List<LibraryCollection> items;
        final int offset;
        final int limit;
        final int total;
        final boolean hasMore;
        final String revision;

        LibraryCollectionPage(List<LibraryCollection> items, int offset, int limit, int total,
                              boolean hasMore) {
            this(items, offset, limit, total, hasMore, "");
        }

        LibraryCollectionPage(List<LibraryCollection> items, int offset, int limit, int total,
                              boolean hasMore, String revision) {
            this.items = items;
            this.offset = offset;
            this.limit = limit;
            this.total = total;
            this.hasMore = hasMore;
            this.revision = revision == null ? "" : revision;
        }
    }

    static final class LibraryPage {
        final List<LibraryTrack> items;
        final int offset;
        final int limit;
        final int total;
        final boolean hasMore;
        final String revision;

        LibraryPage(List<LibraryTrack> items, int offset, int limit, int total,
                    boolean hasMore) {
            this(items, offset, limit, total, hasMore, "");
        }

        LibraryPage(List<LibraryTrack> items, int offset, int limit, int total,
                    boolean hasMore, String revision) {
            this.items = items;
            this.offset = offset;
            this.limit = limit;
            this.total = total;
            this.hasMore = hasMore;
            this.revision = revision == null ? "" : revision;
        }
    }

    /** Machine-readable bridge failure category; older bridges send none (NONE). */
    enum ErrorCode {
        NONE, PAIRING_RATE_LIMITED, PAIRING_CLOSED, PAIRING_CODE_INCORRECT, PAIRING_DEVICE_LIMIT,
        PAIRING_SAVE_FAILED, BUSY, ITUNES_BUSY, ITUNES_UNAVAILABLE, NOT_FOUND, INVALID_REQUEST,
        NOT_PAIRED, SUPERSEDED, BRIDGE_ERROR;

        static ErrorCode fromWire(String code) {
            return switch (code == null ? "" : code) {
                case "pairing_rate_limited" -> PAIRING_RATE_LIMITED;
                case "pairing_closed" -> PAIRING_CLOSED;
                case "pairing_code_incorrect" -> PAIRING_CODE_INCORRECT;
                case "pairing_device_limit" -> PAIRING_DEVICE_LIMIT;
                case "pairing_save_failed" -> PAIRING_SAVE_FAILED;
                case "busy" -> BUSY;
                case "itunes_busy" -> ITUNES_BUSY;
                case "itunes_unavailable" -> ITUNES_UNAVAILABLE;
                case "not_found" -> NOT_FOUND;
                case "invalid_request" -> INVALID_REQUEST;
                case "not_paired" -> NOT_PAIRED;
                case "superseded" -> SUPERSEDED;
                case "bridge_error" -> BRIDGE_ERROR;
                default -> NONE;
            };
        }
    }

    /** A non-2xx bridge response: its English diagnostic plus the typed code, if any. */
    static final class BridgeFailure extends IOException {
        final ErrorCode code;

        BridgeFailure(String message, ErrorCode code) {
            super(message);
            this.code = code;
        }
    }

    static BridgeFailure failureFrom(BridgeHttpClient.Response response, String fallback) {
        JSONObject body;
        try {
            body = parseObject(response.body);
        } catch (JSONException notJson) {
            body = new JSONObject();
        }
        String message = body.optString("error", "");
        return new BridgeFailure(message.isBlank() ? fallback : message,
                ErrorCode.fromWire(body.optString("code", "")));
    }

    static ErrorCode codeOf(Exception failure) {
        return failure instanceof BridgeFailure typed ? typed.code : ErrorCode.NONE;
    }

    interface Result<T> {
        void success(T value);
        void failure(String message, boolean unauthorized);
        /** Receives the typed code as well; the default keeps the legacy two-argument form. */
        default void failure(String message, boolean unauthorized, ErrorCode code) {
            failure(message, unauthorized);
        }
        default void rateLimited(int retryAfterSeconds) {
            failure("Too many pairing attempts. Try again later.", false,
                    ErrorCode.PAIRING_RATE_LIMITED);
        }
    }

    interface Cancellation {
        Cancellation NONE = () -> { };
        void cancel();
    }

    static final class ConnectionCancellation implements Cancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<HttpsURLConnection> connection = new AtomicReference<>();
        private final AtomicReference<DatagramSocket> datagramSocket = new AtomicReference<>();
        private final AtomicReference<SSLSocket> sslSocket = new AtomicReference<>();
        private final AtomicReference<Future<?>> task = new AtomicReference<>();

        void attach(HttpsURLConnection active) throws InterruptedIOException {
            connection.set(active);
            if (cancelled.get()) {
                disconnect(active);
                throw new InterruptedIOException("Request cancelled");
            }
        }

        void detach(HttpsURLConnection active) {
            connection.compareAndSet(active, null);
        }

        void attach(DatagramSocket active) throws InterruptedIOException {
            datagramSocket.set(active);
            if (cancelled.get()) {
                datagramSocket.compareAndSet(active, null);
                active.close();
                throw new InterruptedIOException("Request cancelled");
            }
        }

        void detach(DatagramSocket active) {
            datagramSocket.compareAndSet(active, null);
        }

        void attach(SSLSocket active) throws InterruptedIOException {
            sslSocket.set(active);
            if (cancelled.get()) {
                sslSocket.compareAndSet(active, null);
                try { active.close(); } catch (IOException ignored) { }
                throw new InterruptedIOException("Request cancelled");
            }
        }

        void detach(SSLSocket active) {
            sslSocket.compareAndSet(active, null);
        }

        void setTask(Future<?> future) {
            task.set(future);
            if (cancelled.get()) future.cancel(true);
        }

        boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public void cancel() {
            if (!cancelled.compareAndSet(false, true)) return;
            HttpsURLConnection active = connection.getAndSet(null);
            if (active != null) active.disconnect();
            DatagramSocket datagram = datagramSocket.getAndSet(null);
            if (datagram != null) datagram.close();
            SSLSocket socket = sslSocket.getAndSet(null);
            if (socket != null) {
                try { socket.close(); } catch (IOException ignored) { }
            }
            Future<?> future = task.get();
            if (future != null) future.cancel(true);
        }

        private void disconnect(HttpsURLConnection active) {
            connection.compareAndSet(active, null);
            active.disconnect();
        }
    }

    interface StateListener {
        void state(PlayerState state);
        void connectionChanged(boolean connected, String message);
        void unauthorized(String message);
    }

    static String discoveryIdentityKey(BridgeInfo bridge) {
        return bridge.id + '\u0000' + normalizeFingerprint(bridge.tlsFingerprint);
    }

    static PlayerState parsePlayerState(JSONObject json) {
        return new PlayerState(
                json.optBoolean("iTunesAvailable", false),
                json.optBoolean("playing", false),
                json.optString("title", ""),
                json.optString("artist", ""),
                json.optString("album", ""),
                json.optDouble("duration", 0),
                json.optDouble("position", 0),
                clamp(json.optInt("volume", 0), 0, 100),
                json.optString("artworkId", ""),
                json.optString("trackId", ""),
                json.optBoolean("shuffleEnabled", false),
                normalizedRepeatMode(json.optString("repeatMode", "off")));
    }

    static LibraryPage parseLibraryPage(JSONObject json, int requestedLimit) {
        JSONArray array = json.optJSONArray("items");
        List<LibraryTrack> items = new ArrayList<>();
        if (array != null) {
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) continue;
                String id = item.optString("id", "");
                if (id.isBlank()) continue;
                // A missing title stays blank; the presentation layer localizes "Untitled".
                items.add(new LibraryTrack(id,
                        item.optString("title", ""),
                        item.optString("artist", ""),
                        item.optString("album", ""),
                        Math.max(0, item.optDouble("duration", 0)),
                        Math.max(0, item.optInt("trackNumber", 0)),
                        Math.max(0, item.optInt("discNumber", 0)),
                        item.optString("artworkId", ""),
                        item.optString("albumArtist", "")));
            }
        }
        return new LibraryPage(items,
                Math.max(0, json.optInt("offset", 0)),
                clamp(json.optInt("limit", requestedLimit), 1, 60),
                Math.max(0, json.optInt("total", items.size())),
                json.optBoolean("hasMore", false),
                json.optString("revision", ""));
    }

    static LibraryCollectionPage parseLibraryCollectionPage(JSONObject json, int requestedLimit) {
        JSONArray array = json.optJSONArray("items");
        List<LibraryCollection> items = new ArrayList<>();
        if (array != null) {
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) continue;
                String id = item.optString("id", "");
                if (id.isBlank()) continue;
                items.add(new LibraryCollection(
                        id,
                        item.optString("title", ""),
                        item.optString("subtitle", ""),
                        Math.max(0, item.optInt("trackCount", 0)),
                        item.optString("artworkId", "")));
            }
        }
        return new LibraryCollectionPage(items,
                Math.max(0, json.optInt("offset", 0)),
                clamp(json.optInt("limit", requestedLimit), 1, 60),
                Math.max(0, json.optInt("total", items.size())),
                json.optBoolean("hasMore", false),
                json.optString("revision", ""));
    }

    enum StreamStatus { DISCONNECTED, UNSUPPORTED, UNAUTHORIZED, IDENTITY_CHANGED }
    private record StreamResult(StreamStatus status, boolean receivedState, String message) {}
    private static final class StopStreamException extends IOException {}

    /**
     * Classifies the state-stream response. Only 404/405 (or a 200 that is not an event stream)
     * mean the bridge predates streaming; a transient 5xx is a disconnect with backoff.
     * Returns null when the response is a usable event stream.
     */
    static StreamStatus streamStatusFor(int status, String contentType) {
        if (status == 401) return StreamStatus.UNAUTHORIZED;
        if (status == 404 || status == 405) return StreamStatus.UNSUPPORTED;
        if (status != 200) return StreamStatus.DISCONNECTED;
        return contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith("text/event-stream")
                ? null : StreamStatus.UNSUPPORTED;
    }

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService artworkExecutor = Executors.newFixedThreadPool(2);
    private final ExecutorService stateExecutor = Executors.newSingleThreadExecutor();
    private final BridgeHttpClient http = new BridgeHttpClient();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger stateGeneration = new AtomicInteger();
    /** Commands queued under an older epoch are dropped before they start. */
    private final AtomicInteger commandEpoch = new AtomicInteger();
    private final Object stateWake = new Object();
    private boolean stateWakeRequested; // guarded by stateWake
    private volatile boolean resetStateBackoff;
    private volatile HttpsURLConnection activeStateConnection;
    private volatile Thread stateThread;

    Cancellation discover(Result<List<BridgeInfo>> result) {
        ConnectionCancellation cancellation = new ConnectionCancellation();
        Future<?> future = executor.submit(() -> {
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            List<BridgeInfo> bridges = new ArrayList<>();
            try (DatagramSocket socket = new DatagramSocket()) {
                cancellation.attach(socket);
                socket.setBroadcast(true);
                socket.setSoTimeout(350);
                byte[] message = DISCOVERY_MESSAGE.getBytes(StandardCharsets.US_ASCII);
                for (InetAddress broadcast : broadcastAddresses()) {
                    try {
                        socket.send(new DatagramPacket(message, message.length, broadcast,
                                DISCOVERY_PORT));
                    } catch (IOException ignoredOnOneInterface) {
                    }
                }

                long deadline = SystemClock.elapsedRealtime() + 2400;
                while (!cancellation.isCancelled()
                        && SystemClock.elapsedRealtime() < deadline) {
                    byte[] buffer = new byte[2048];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    try {
                        socket.receive(packet);
                        JSONObject json = new JSONObject(new String(packet.getData(), 0,
                                packet.getLength(), StandardCharsets.UTF_8));
                        if (!PROTOCOL.equals(json.optString("protocol"))) continue;
                        String fingerprint = json.optString("tlsFingerprint");
                        if (!isValidFingerprint(fingerprint)) continue;
                        String id = json.optString("id");
                        if (!isValidBridgeId(id)) continue;
                        int port = json.optInt("port", DEFAULT_PORT);
                        if (!isValidPort(port)) continue;
                        String host = packet.getAddress().getHostAddress();
                        BridgeInfo bridge = new BridgeInfo(id,
                                safeBridgeName(json.optString("name")), host, port, fingerprint);
                        if (!seen.add(discoveryIdentityKey(bridge))) continue;
                        bridges.add(bridge);
                    } catch (SocketTimeoutException ignored) {
                    } catch (JSONException ignoredInvalidPacket) {
                    }
                }
                deliverSuccess(result, bridges, cancellation);
            } catch (Exception e) {
                if (!cancellation.isCancelled())
                    deliverFailure(result, friendly(e), false, cancellation);
            }
        });
        cancellation.setTask(future);
        return cancellation;
    }

    Cancellation resolveManual(String input, Result<BridgeInfo> result) {
        ConnectionCancellation cancellation = new ConnectionCancellation();
        Future<?> future = executor.submit(() -> {
            try {
                String candidate = input == null ? "" : input.trim();
                int port = DEFAULT_PORT;
                int colon = candidate.lastIndexOf(':');
                if (colon > 0 && candidate.indexOf(':') == colon) {
                    port = Integer.parseInt(candidate.substring(colon + 1));
                    candidate = candidate.substring(0, colon);
                }
                if (candidate.isBlank() || port < 1 || port > 65535) {
                    throw new IOException("Enter the computer's IPv4 address, for example 192.168.1.20");
                }
                InetAddress selected = parsePrivateIpv4(candidate);
                if (selected == null) throw new IOException("That address is not on your local network");
                String host = selected.getHostAddress();
                String fingerprint = BridgeHttpClient.probeFingerprint(host, port, cancellation);
                BridgeHttpClient.Response response = request(host, port, fingerprint, null,
                        "GET", "/api/info", null, cancellation);
                if (response.status != 200) throw new IOException("TunesLink Bridge did not answer");
                JSONObject json = new JSONObject(new String(response.body, StandardCharsets.UTF_8));
                if (!PROTOCOL.equals(json.optString("protocol"))) {
                    throw new IOException("This is not a TunesLink Bridge");
                }
                if (!fingerprint.equals(normalizeFingerprint(json.optString("tlsFingerprint"))))
                    throw new IOException("Bridge identity changed during connection");
                String id = json.optString("id");
                if (!isValidBridgeId(id)) throw new IOException("Bridge identity is invalid");
                deliverSuccess(result, new BridgeInfo(id,
                        safeBridgeName(json.optString("name")), host, port, fingerprint),
                        cancellation);
            } catch (Exception e) {
                if (!cancellation.isCancelled())
                    deliverFailure(result, friendly(e), false, cancellation);
            }
        });
        cancellation.setTask(future);
        return cancellation;
    }

    Cancellation verifyIdentity(BridgeInfo candidate, String expectedId,
                                String expectedFingerprint, Result<BridgeInfo> result) {
        ConnectionCancellation cancellation = new ConnectionCancellation();
        Future<?> future = executor.submit(() -> {
            try {
                if (!expectedId.equals(candidate.id)
                        || !normalizeFingerprint(expectedFingerprint)
                        .equals(candidate.tlsFingerprint)) {
                    throw new IOException(IDENTITY_CHANGED_MESSAGE);
                }
                BridgeHttpClient.Response response = request(candidate.host, candidate.port,
                        expectedFingerprint, null, "GET", "/api/info", null, cancellation);
                if (response.status != 200) throw new IOException("TunesLink Bridge did not answer");
                JSONObject json = parseObject(response.body);
                if (!PROTOCOL.equals(json.optString("protocol"))) {
                    throw new IOException("This is not a TunesLink Bridge");
                }
                String id = json.optString("id");
                String fingerprint = normalizeFingerprint(json.optString("tlsFingerprint"));
                if (!expectedId.equals(id) || !normalizeFingerprint(expectedFingerprint)
                        .equals(fingerprint)) {
                    throw new IOException(IDENTITY_CHANGED_MESSAGE);
                }
                deliverSuccess(result, new BridgeInfo(id,
                        safeBridgeName(json.optString("name")), candidate.host, candidate.port,
                        fingerprint), cancellation);
            } catch (Exception failure) {
                if (!cancellation.isCancelled())
                    deliverFailure(result, friendly(failure), false, cancellation);
            }
        });
        cancellation.setTask(future);
        return cancellation;
    }

    Cancellation pair(BridgeInfo bridge, String code, String clientId, Result<String> result) {
        ConnectionCancellation cancellation = new ConnectionCancellation();
        Future<?> future = executor.submit(() -> {
            try {
                if (!isValidClientId(clientId)) throw new IOException("This phone identity is invalid");
                JSONObject request = new JSONObject()
                        .put("code", code)
                        .put("clientId", clientId)
                        .put("deviceName", deviceName());
                BridgeHttpClient.Response response = request(bridge.host, bridge.port,
                        bridge.tlsFingerprint, null, "POST",
                        "/api/pair", request.toString().getBytes(StandardCharsets.UTF_8),
                        cancellation);
                if (response.status == 429) {
                    main.post(() -> {
                        if (!closed.get() && !cancellation.isCancelled())
                            result.rateLimited(response.retryAfterSeconds);
                    });
                    return;
                }
                if (response.status != 200) throw failureFrom(response, "Pairing failed");
                String token = parseObject(response.body).getString("token");
                if (token.length() < 32) throw new IOException("Bridge returned an invalid token");
                deliverSuccess(result, token, cancellation);
            } catch (Exception e) {
                if (!cancellation.isCancelled())
                    deliverFailure(result, friendly(e), false, codeOf(e), cancellation);
            }
        });
        cancellation.setTask(future);
        return cancellation;
    }

    void getState(SecureStore.SavedBridge bridge, Result<PlayerState> result) {
        executor.execute(() -> {
            try {
                BridgeHttpClient.Response response = request(bridge.host, bridge.port,
                        bridge.tlsFingerprint, bridge.token,
                        "GET", "/api/state", null);
                if (response.status == 401) {
                    deliverFailure(result, "This phone is no longer paired", true);
                    return;
                }
                if (response.status != 200) throw new IOException("Bridge is not ready");
                deliverSuccess(result, parsePlayerState(parseObject(response.body)));
            } catch (Exception e) {
                deliverFailure(result, friendly(e), false);
            }
        });
    }

    /**
     * Queues a playback command on the ordered command lane. A command that has not started when
     * it is cancelled, the stream reports a disconnect, or state updates stop is dropped silently;
     * cancelling a started command abandons its connection and callback.
     */
    Cancellation command(SecureStore.SavedBridge bridge, String command, Double value,
                         Result<Boolean> result) {
        ConnectionCancellation cancellation = new ConnectionCancellation();
        int epoch = commandEpoch.get();
        Future<?> task = commandExecutor.submit(() -> {
            if (cancellation.isCancelled() || epoch != commandEpoch.get()) return;
            try {
                JSONObject json = new JSONObject().put("command", command);
                if (value != null) json.put("value", value);
                BridgeHttpClient.Response response = request(bridge.host, bridge.port,
                        bridge.tlsFingerprint, bridge.token, "POST", "/api/command",
                        json.toString().getBytes(StandardCharsets.UTF_8), cancellation);
                if (response.status == 401) {
                    deliverFailure(result, "This phone is no longer paired", true, cancellation);
                } else if (response.status == 200 || response.status == 204) {
                    deliverSuccess(result, true, cancellation);
                } else {
                    throw failureFrom(response, "iTunes did not accept that command");
                }
            } catch (Exception e) {
                deliverFailure(result, friendly(e), false, codeOf(e), cancellation);
            }
        });
        cancellation.setTask(task);
        return cancellation;
    }

    Cancellation revokePairing(SecureStore.SavedBridge bridge, Result<Boolean> result) {
        ConnectionCancellation cancellation = new ConnectionCancellation();
        Future<?> future = executor.submit(() -> {
            try {
                BridgeHttpClient.Response response = request(bridge.host, bridge.port,
                        bridge.tlsFingerprint,
                        bridge.token, "DELETE", "/api/pairing/self", null, cancellation);
                if (response.status == 200 || response.status == 204 || response.status == 401) {
                    deliverSuccess(result, true, cancellation);
                } else {
                    throw new IOException("The computer could not revoke this phone");
                }
            } catch (Exception e) {
                if (!cancellation.isCancelled())
                    deliverFailure(result, friendly(e), false, cancellation);
            }
        });
        cancellation.setTask(future);
        return cancellation;
    }

    Cancellation getLibrary(SecureStore.SavedBridge bridge, String query, int offset, int limit,
                            Result<LibraryPage> result) {
        return getLibrary(bridge, query, offset, limit, "", "", result);
    }

    Cancellation getLibrary(SecureStore.SavedBridge bridge, String query, int offset, int limit,
                            String collectionKind, String collectionId,
                            Result<LibraryPage> result) {
        ConnectionCancellation cancellation = new ConnectionCancellation();
        Future<?> future = executor.submit(() -> {
            try {
                String path = "/api/library?query=" + urlEncode(query == null ? "" : query)
                        + "&offset=" + Math.max(0, offset)
                        + "&limit=" + clamp(limit, 1, 60);
                if (collectionKind != null && !collectionKind.isBlank()) {
                    path += "&collectionKind=" + urlEncode(collectionKind)
                            + "&collectionId=" + urlEncode(collectionId == null ? "" : collectionId);
                }
                BridgeHttpClient.Response response = request(bridge.host, bridge.port,
                        bridge.tlsFingerprint,
                        bridge.token, "GET", path, null, cancellation);
                if (response.status == 401) {
                    deliverFailure(result, "This phone is no longer paired", true, cancellation);
                    return;
                }
                if (response.status != 200) throw failureFrom(response, "Library is unavailable");
                deliverSuccess(result, parseLibraryPage(parseObject(response.body), limit),
                        cancellation);
            } catch (Exception e) {
                deliverFailure(result, friendly(e), false, codeOf(e), cancellation);
            }
        });
        cancellation.setTask(future);
        return cancellation;
    }

    Cancellation getCollections(SecureStore.SavedBridge bridge, String kind, String query,
                                int offset, int limit,
                                Result<LibraryCollectionPage> result) {
        return getCollections(bridge, kind, "", query, offset, limit, result);
    }

    Cancellation getCollections(SecureStore.SavedBridge bridge, String kind, String parentId,
                                String query, int offset, int limit,
                                Result<LibraryCollectionPage> result) {
        ConnectionCancellation cancellation = new ConnectionCancellation();
        Future<?> future = executor.submit(() -> {
            try {
                String path = (parentId.isEmpty() ? "/api/collections" : "/api/collection-albums")
                        + "?kind=" + urlEncode(kind == null ? "" : kind)
                        + "&id=" + urlEncode(parentId)
                        + "&query=" + urlEncode(query == null ? "" : query)
                        + "&offset=" + Math.max(0, offset)
                        + "&limit=" + clamp(limit, 1, 60);
                BridgeHttpClient.Response response = request(bridge.host, bridge.port,
                        bridge.tlsFingerprint, bridge.token, "GET", path, null, cancellation);
                if (response.status == 401) {
                    deliverFailure(result, "This phone is no longer paired", true, cancellation);
                    return;
                }
                if (response.status == 404 && !parentId.isEmpty()) {
                    throw new IOException("Update TunesLink Bridge to browse collection albums");
                }
                if (response.status != 200)
                    throw failureFrom(response, "Library collections are unavailable");
                deliverSuccess(result,
                        parseLibraryCollectionPage(parseObject(response.body), limit), cancellation);
            } catch (Exception e) {
                deliverFailure(result, friendly(e), false, codeOf(e), cancellation);
            }
        });
        cancellation.setTask(future);
        return cancellation;
    }

    Cancellation playTrack(SecureStore.SavedBridge bridge, String trackId, String collectionKind,
                           String collectionId, long sequence, Result<Boolean> result) {
        ConnectionCancellation cancellation = new ConnectionCancellation();
        AtomicBoolean acknowledged = new AtomicBoolean();
        int epoch = commandEpoch.get();
        Future<?> task = commandExecutor.submit(() -> {
            // Never sent: nothing to cancel at the bridge, and no callback is expected.
            if (cancellation.isCancelled() || epoch != commandEpoch.get()) {
                acknowledged.set(true);
                return;
            }
            long started = SystemClock.elapsedRealtime();
            try {
                JSONObject json = new JSONObject().put("trackId", trackId)
                        .put("sequence", sequence);
                if (collectionKind != null && !collectionKind.isBlank()
                        && collectionId != null && !collectionId.isBlank()) {
                    json.put("collectionKind", collectionKind);
                    json.put("collectionId", collectionId);
                }
                BridgeHttpClient.Response response = request(bridge.host, bridge.port,
                        bridge.tlsFingerprint,
                        bridge.token, "POST", "/api/play",
                        json.toString().getBytes(StandardCharsets.UTF_8), cancellation);
                acknowledged.set(true);
                if (response.status == 401) {
                    deliverFailure(result, "This phone is no longer paired", true, cancellation);
                } else if (response.status == 200 || response.status == 204) {
                    deliverSuccess(result, true, cancellation);
                } else {
                    throw failureFrom(response, "iTunes could not play that song");
                }
            } catch (Exception e) {
                if (!cancellation.isCancelled())
                    deliverFailure(result, friendly(e), false, codeOf(e), cancellation);
            } finally {
                Log.i("TunesLinkTiming", "play.http_ms=" +
                        (SystemClock.elapsedRealtime() - started));
            }
        });
        cancellation.setTask(task);
        return () -> {
            if (cancellation.isCancelled()) return;
            cancellation.cancel();
            if (closed.get() || acknowledged.get()) return;
            executor.execute(() -> {
                try {
                    byte[] body = new JSONObject().put("sequence", sequence).toString()
                            .getBytes(StandardCharsets.UTF_8);
                    request(bridge.host, bridge.port, bridge.tlsFingerprint, bridge.token,
                            "POST", "/api/play/cancel", body);
                } catch (Exception ignored) {
                    // A newer selection also supersedes this request at the bridge. Older
                    // bridges may not support explicit cancellation yet.
                }
            });
        };
    }

    Cancellation getArtwork(SecureStore.SavedBridge bridge, String artworkId, int size,
                            Result<Bitmap> result) {
        ConnectionCancellation cancellation = new ConnectionCancellation();
        Future<?> task = artworkExecutor.submit(() -> {
            try {
                int requestedSize = artworkRequestSize(size);
                BridgeHttpClient.Response response = request(bridge.host, bridge.port,
                        bridge.tlsFingerprint, bridge.token,
                        "GET", "/api/artwork?id=" + urlEncode(artworkId)
                                + "&size=" + requestedSize, null, cancellation);
                if (cancellation.isCancelled()) return;
                if (response.status == 404) {
                    deliverSuccess(result, null);
                    return;
                }
                if (response.status == 401) {
                    deliverFailure(result, "This phone is no longer paired", true);
                    return;
                }
                if (response.status != 200) throw new IOException("Artwork is unavailable");
                if (!response.contentType.startsWith("image/"))
                    throw new IOException("Artwork response was not an image");
                Bitmap bitmap = decodeArtwork(response.body, requestedSize);
                deliverSuccess(result, bitmap);
            } catch (Exception e) {
                if (!cancellation.isCancelled()) deliverFailure(result, friendly(e), false);
            }
        });
        cancellation.setTask(task);
        return cancellation;
    }

    void startStateUpdates(SecureStore.SavedBridge bridge, StateListener listener) {
        int generation = stateGeneration.incrementAndGet();
        disconnectStateConnection();
        synchronized (stateWake) {
            stateWakeRequested = false;
        }
        stateExecutor.execute(() -> runStateUpdates(bridge, listener, generation));
    }

    void stopStateUpdates() {
        stateGeneration.incrementAndGet();
        commandEpoch.incrementAndGet();
        disconnectStateConnection();
        wakeStateThread();
        Thread thread = stateThread;
        if (thread != null) thread.interrupt();
    }

    void requestStateRefresh() {
        // The current bridge pushes command results through the open SSE stream. Interrupting
        // that read reports a false disconnect; only wake legacy polling or reconnect backoff,
        // and never interrupt a request that is in flight.
        if (activeStateConnection != null) return;
        wakeStateThread();
    }

    /** A network became available: retry now and restart the reconnect backoff. */
    void networkAvailable() {
        if (activeStateConnection != null) return;
        resetStateBackoff = true;
        wakeStateThread();
    }

    private void wakeStateThread() {
        synchronized (stateWake) {
            stateWakeRequested = true;
            stateWake.notifyAll();
        }
    }

    private void runStateUpdates(SecureStore.SavedBridge bridge, StateListener listener,
                                 int generation) {
        stateThread = Thread.currentThread();
        boolean legacyPolling = false;
        int failures = 0;
        // Connection transitions are reported once, not with every state frame.
        boolean[] reported = {false};
        long[] delays = {2_000, 4_000, 8_000, 15_000};
        try {
            while (isStateCurrent(generation)) {
                Thread.interrupted();
                if (resetStateBackoff) {
                    resetStateBackoff = false;
                    failures = 0;
                }
                if (legacyPolling) {
                    try {
                        BridgeHttpClient.Response response = request(bridge.host, bridge.port,
                                bridge.tlsFingerprint, bridge.token, "GET", "/api/state", null);
                        if (response.status == 401) {
                            deliverUnauthorized(listener, generation,
                                    "This phone is no longer paired");
                            return;
                        }
                        if (response.status != 200) throw new IOException("Bridge is not ready");
                        PlayerState state = parsePlayerState(parseObject(response.body));
                        failures = 0;
                        deliverState(listener, generation, state, reported);
                        sleepWhileCurrent(generation, state.playing ? 850 : 2_000);
                    } catch (Exception exception) {
                        if (!isStateCurrent(generation)) return;
                        if (isIdentityFailure(exception)) {
                            deliverConnection(listener, generation, IDENTITY_CHANGED_MESSAGE,
                                    reported);
                            return;
                        }
                        failures++;
                        deliverConnection(listener, generation, friendly(exception), reported);
                        sleepWhileCurrent(generation,
                                delays[Math.min(Math.max(0, failures - 1), delays.length - 1)]);
                    }
                    continue;
                }

                StreamResult outcome = streamState(bridge, listener, generation, reported);
                if (!isStateCurrent(generation)) return;
                if (outcome.status == StreamStatus.UNSUPPORTED) {
                    legacyPolling = true;
                    failures = 0;
                    continue;
                }
                if (outcome.status == StreamStatus.UNAUTHORIZED) return;
                if (outcome.status == StreamStatus.IDENTITY_CHANGED) {
                    deliverConnection(listener, generation, outcome.message, reported);
                    return;
                }
                if (outcome.receivedState) {
                    failures = 0;
                    sleepWhileCurrent(generation, 300);
                    continue;
                }
                failures++;
                deliverConnection(listener, generation,
                        outcome.message == null ? "Reconnecting to the computer" : outcome.message,
                        reported);
                sleepWhileCurrent(generation,
                        delays[Math.min(Math.max(0, failures - 1), delays.length - 1)]);
            }
        } finally {
            if (stateThread == Thread.currentThread()) stateThread = null;
            disconnectStateConnection();
        }
    }

    private StreamResult streamState(SecureStore.SavedBridge bridge, StateListener listener,
                                     int generation, boolean[] reported) {
        HttpsURLConnection connection = null;
        boolean[] receivedState = {false};
        try {
            connection = http.openPinnedConnection(bridge.host, bridge.port, bridge.tlsFingerprint,
                    "/api/state/stream");
            activeStateConnection = connection;
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(30_000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setRequestProperty("Authorization", "Bearer " + bridge.token);
            int status = connection.getResponseCode();
            StreamStatus rejected = streamStatusFor(status, connection.getContentType());
            if (rejected == StreamStatus.UNAUTHORIZED) {
                deliverUnauthorized(listener, generation, "This phone is no longer paired");
                return new StreamResult(StreamStatus.UNAUTHORIZED, false, null);
            }
            if (rejected == StreamStatus.UNSUPPORTED)
                return new StreamResult(StreamStatus.UNSUPPORTED, false, null);
            if (rejected != null) throw new IOException("Bridge state stream is unavailable");
            SseParser parser = new SseParser();
            byte[] buffer = new byte[4096];
            try (InputStream input = connection.getInputStream()) {
                int read;
                while (isStateCurrent(generation) && (read = input.read(buffer)) != -1) {
                    parser.accept(buffer, 0, read, event -> {
                        if ("unauthorized".equals(event.name())) {
                            deliverUnauthorized(listener, generation,
                                    "This phone is no longer paired");
                            throw new StopStreamException();
                        }
                        if (!"state".equals(event.name())) return;
                        try {
                            PlayerState state = parsePlayerState(new JSONObject(event.data()));
                            receivedState[0] = true;
                            deliverState(listener, generation, state, reported);
                        } catch (JSONException invalidState) {
                            throw new IOException("Bridge sent an invalid state event", invalidState);
                        }
                    });
                }
            }
            return new StreamResult(StreamStatus.DISCONNECTED, receivedState[0],
                    "Reconnecting to the computer");
        } catch (StopStreamException unauthorized) {
            return new StreamResult(StreamStatus.UNAUTHORIZED, receivedState[0], null);
        } catch (Exception failure) {
            if (isIdentityFailure(failure))
                return new StreamResult(StreamStatus.IDENTITY_CHANGED, receivedState[0],
                        IDENTITY_CHANGED_MESSAGE);
            return new StreamResult(StreamStatus.DISCONNECTED, receivedState[0], friendly(failure));
        } finally {
            if (activeStateConnection == connection) activeStateConnection = null;
            if (connection != null) connection.disconnect();
        }
    }

    /** Sleeps between polls or reconnects; wakeStateThread() ends the wait early. */
    private void sleepWhileCurrent(int generation, long milliseconds) {
        long deadline = System.nanoTime() + milliseconds * 1_000_000L;
        synchronized (stateWake) {
            try {
                while (!stateWakeRequested && isStateCurrent(generation)) {
                    long remaining = (deadline - System.nanoTime()) / 1_000_000L;
                    if (remaining <= 0) break;
                    stateWake.wait(remaining);
                }
            } catch (InterruptedException stopped) {
                Thread.interrupted();
            }
            stateWakeRequested = false;
        }
    }

    private boolean isStateCurrent(int generation) {
        return !closed.get() && stateGeneration.get() == generation;
    }

    private void disconnectStateConnection() {
        HttpsURLConnection connection = activeStateConnection;
        activeStateConnection = null;
        if (connection != null) connection.disconnect();
    }

    private void deliverState(StateListener listener, int generation, PlayerState state,
                              boolean[] reported) {
        boolean connectedNow = !reported[0];
        reported[0] = true;
        main.post(() -> {
            if (isStateCurrent(generation)) {
                if (connectedNow) listener.connectionChanged(true, null);
                listener.state(state);
            }
        });
    }

    private void deliverConnection(StateListener listener, int generation, String message,
                                   boolean[] reported) {
        reported[0] = false;
        commandEpoch.incrementAndGet();
        main.post(() -> {
            if (isStateCurrent(generation)) listener.connectionChanged(false, message);
        });
    }

    private void deliverUnauthorized(StateListener listener, int generation, String message) {
        commandEpoch.incrementAndGet();
        main.post(() -> {
            if (isStateCurrent(generation)) listener.unauthorized(message);
        });
    }

    void close() {
        closed.set(true);
        stopStateUpdates();
        executor.shutdownNow();
        commandExecutor.shutdownNow();
        artworkExecutor.shutdownNow();
        stateExecutor.shutdownNow();
        http.clear();
    }

    private BridgeHttpClient.Response request(String host, int port, String tlsFingerprint,
                                              String token, String method, String path,
                                              byte[] body) throws IOException {
        return request(host, port, tlsFingerprint, token, method, path, body, null);
    }

    private BridgeHttpClient.Response request(String host, int port, String tlsFingerprint,
                                              String token, String method, String path, byte[] body,
                                              ConnectionCancellation cancellation) throws IOException {
        return http.request(host, port, tlsFingerprint, token, method, path, body, cancellation);
    }

    private <T> void deliverSuccess(Result<T> result, T value) {
        if (closed.get()) return;
        main.post(() -> {
            if (!closed.get()) result.success(value);
        });
    }

    private <T> void deliverSuccess(Result<T> result, T value,
                                    ConnectionCancellation cancellation) {
        if (closed.get() || cancellation.isCancelled()) return;
        main.post(() -> {
            if (!closed.get() && !cancellation.isCancelled()) result.success(value);
        });
    }

    private <T> void deliverFailure(Result<T> result, String message, boolean unauthorized) {
        if (closed.get()) return;
        main.post(() -> {
            if (!closed.get()) result.failure(message, unauthorized);
        });
    }

    private <T> void deliverFailure(Result<T> result, String message, boolean unauthorized,
                                    ConnectionCancellation cancellation) {
        deliverFailure(result, message, unauthorized, ErrorCode.NONE, cancellation);
    }

    private <T> void deliverFailure(Result<T> result, String message, boolean unauthorized,
                                    ErrorCode code, ConnectionCancellation cancellation) {
        if (closed.get() || cancellation.isCancelled()) return;
        main.post(() -> {
            if (!closed.get() && !cancellation.isCancelled())
                result.failure(message, unauthorized, code);
        });
    }

}
