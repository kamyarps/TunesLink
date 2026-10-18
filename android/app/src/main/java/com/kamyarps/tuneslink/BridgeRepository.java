package com.kamyarps.tuneslink;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Owns the paired bridge, authenticated network access, and bridge-scoped media cache.
 *
 * <p>Callbacks for authenticated requests are delivered only while the bridge that started the
 * request is still current. This keeps Activity lifecycle and rendering code independent from
 * token storage and session-isolation details.</p>
 */
final class BridgeRepository implements AutoCloseable {
    private static final int ARTWORK_CACHE_KIB = 16 * 1024;
    private static final int MISSING_ARTWORK_CACHE_ENTRIES = 512;
    private static final long MISSING_ARTWORK_TTL_MS = 60_000;
    private static final long MIN_REVOCATION_RETRY_MS = 30_000;
    private static final long MAX_REVOCATION_RETRY_MS = 15 * 60_000;

    interface RequestHandle {
        RequestHandle NONE = () -> { };
        void cancel();
    }

    interface PageResult<T> {
        void page(T value, boolean authoritative);
        void failure(String message, boolean unauthorized);
        default void failure(String message, boolean unauthorized, BridgeClient.ErrorCode code) {
            failure(message, unauthorized);
        }
    }

    /** A saved cover may arrive before the refresh succeeds or fails. */
    interface ArtworkResult extends BridgeClient.Result<Bitmap> {
        default void cached(Bitmap bitmap) { success(bitmap); }
    }

    static final class Relocation {
        enum Status { RELOCATED, IDENTITY_CHANGED }

        final Status status;
        final SecureStore.SavedBridge trusted;
        final SecureStore.SavedBridge relocated;
        final BridgeClient.BridgeInfo observed;

        private Relocation(Status status, SecureStore.SavedBridge trusted,
                           SecureStore.SavedBridge relocated,
                           BridgeClient.BridgeInfo observed) {
            this.status = status;
            this.trusted = trusted;
            this.relocated = relocated;
            this.observed = observed;
        }

        static Relocation relocated(SecureStore.SavedBridge trusted,
                                    SecureStore.SavedBridge relocated) {
            return new Relocation(Status.RELOCATED, trusted, relocated, null);
        }

        static Relocation identityChanged(SecureStore.SavedBridge trusted,
                                          BridgeClient.BridgeInfo observed) {
            return new Relocation(Status.IDENTITY_CHANGED, trusted, null, observed);
        }
    }

    private static final class ArtworkObserver {
        final ArtworkResult result;
        boolean cancelled;

        ArtworkObserver(ArtworkResult result) {
            this.result = result;
        }
    }

    private static final class PendingArtwork {
        final BridgeSession.Request request;
        final ArrayList<ArtworkObserver> observers = new ArrayList<>();
        BridgeClient.Cancellation networkRequest = BridgeClient.Cancellation.NONE;
        ArtworkDiskCache.Handle diskRequest = () -> { };
        Bitmap preview;

        PendingArtwork(BridgeSession.Request request) {
            this.request = request;
        }
    }

    private final BridgeClient client;
    private final SecureStore store;
    private final Executor storage;
    private final Consumer<Runnable> dispatch;
    private final List<Runnable> readyObservers = new ArrayList<>();
    private boolean initialized;
    private boolean closed;
    private int pendingRevocations;
    private boolean storageRequiresRecovery;
    private final LibraryCacheStore libraryCache;
    private final ArtworkDiskCache artworkDiskCache;
    private final BridgeSession session = new BridgeSession();
    private record CachedArtwork(Bitmap bitmap, long fetchedAt) { }

    private final LruCache<String, CachedArtwork> artworkCache =
            new LruCache<>(ARTWORK_CACHE_KIB) {
                @Override
                protected int sizeOf(String key, CachedArtwork value) {
                    return Math.max(1, value.bitmap().getAllocationByteCount() / 1024);
                }
            };
    private final LruCache<String, Long> missingArtwork =
            new LruCache<>(MISSING_ARTWORK_CACHE_ENTRIES);
    private final Map<String, PendingArtwork> pendingArtwork = new HashMap<>();
    private SecureStore.SavedBridge current;
    private BridgeSession.Request updatesRequest;
    private final PendingPairing pendingPairing = new PendingPairing();
    private final PendingDiscovery pendingDiscovery = new PendingDiscovery();
    private BridgeClient.Cancellation discoveryRequest = BridgeClient.Cancellation.NONE;
    private BridgeClient.Cancellation manualRequest = BridgeClient.Cancellation.NONE;
    private boolean pairingInFlight;
    private boolean forgetting;
    private long activePairingGeneration;
    private Runnable pairingSettlementObserver = () -> { };
    private BridgeClient.Cancellation relocationRequest = BridgeClient.Cancellation.NONE;
    private long manualGeneration;
    private long relocationGeneration;
    private final AtomicBoolean relocationInFlight = new AtomicBoolean();
    private boolean retryingRevocations;
    private final ArrayList<BridgeClient.Result<Integer>> revocationRetryObservers =
            new ArrayList<>();
    /** Earliest time (elapsed realtime) for an automatic, connection-triggered revocation retry. */
    private long automaticRevocationRetryAt;
    private long automaticRevocationBackoffMs = MIN_REVOCATION_RETRY_MS;
    /** Non-null while the caller wants state updates, including during a relocation. */
    private StateUpdatesListener updatesListener;
    private boolean streamFailed;
    private final ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;

    interface StateUpdatesListener {
        void state(BridgeClient.PlayerState state);
        void connectionChanged(SecureStore.SavedBridge bridge, boolean connected, String message);
        void unauthorized(SecureStore.SavedBridge bridge, String message);
    }

    BridgeRepository(Context context) {
        this(new BridgeClient(), new SecureStore(context), new LibraryCacheStore(context),
                new ArtworkDiskCache(context),
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE),
                Executors.newSingleThreadExecutor(), new Handler(Looper.getMainLooper())::post);
    }

    BridgeRepository(BridgeClient client, SecureStore store) {
        this(client, store, Runnable::run, Runnable::run);
    }

    BridgeRepository(BridgeClient client, SecureStore store, Executor storage,
                     Consumer<Runnable> dispatch) {
        this(client, store, null, null, null, storage, dispatch);
    }

    private BridgeRepository(BridgeClient client, SecureStore store,
                             LibraryCacheStore libraryCache,
                             ArtworkDiskCache artworkDiskCache,
                             ConnectivityManager connectivity, Executor storage,
                             Consumer<Runnable> dispatch) {
        this.client = client;
        this.store = store;
        this.storage = storage;
        this.dispatch = dispatch;
        this.libraryCache = libraryCache;
        this.artworkDiskCache = artworkDiskCache;
        this.connectivity = connectivity;
        storageWork(store::load, (saved, failure) -> {
            current = saved;
            if (current != null) session.activate(current);
            initialized = true;
            List<Runnable> observers = new ArrayList<>(readyObservers);
            readyObservers.clear();
            observers.forEach(Runnable::run);
        });
    }

    boolean isInitialized() { return initialized; }

    RequestHandle whenReady(Runnable observer) {
        if (closed) return RequestHandle.NONE;
        if (initialized) observer.run();
        else readyObservers.add(observer);
        return () -> readyObservers.remove(observer);
    }

    /** Only this serial lane touches durable credentials; results return to the UI owner. */
    private <T> void storageWork(Callable<T> operation, BiConsumer<T, Exception> completed) {
        if (closed && !pairingInFlight) return;
        storage.execute(() -> {
            T value = null;
            Exception failure = null;
            try { value = operation.call(); }
            catch (Exception error) { failure = error; }
            int count = store.pendingRevocationCount();
            boolean recovery = store.requiresRecovery();
            T result = value;
            Exception error = failure;
            dispatch.accept(() -> {
                if (closed && !pairingInFlight) return;
                pendingRevocations = count;
                storageRequiresRecovery = recovery;
                completed.accept(result, error);
            });
        });
    }

    SecureStore.SavedBridge current() {
        return current;
    }

    boolean secureStorageRequiresRecovery() {
        return storageRequiresRecovery;
    }

    RequestHandle retrySecureStorage(Runnable observer) {
        AtomicBoolean cancelled = new AtomicBoolean();
        storageWork(store::load, (saved, failure) -> {
            if (current == null && saved != null) {
                current = saved;
                session.activate(saved);
            }
            if (!cancelled.get()) observer.run();
        });
        return () -> cancelled.set(true);
    }

    RequestHandle discover(BridgeClient.Result<List<BridgeClient.BridgeInfo>> result) {
        PendingDiscovery.Begin begin = pendingDiscovery.beginOperation(result);
        if (!begin.start) return this::cancelDiscovery;
        discoveryRequest = client.discover(new BridgeClient.Result<>() {
            @Override
            public void success(List<BridgeClient.BridgeInfo> bridges) {
                BridgeClient.Result<List<BridgeClient.BridgeInfo>> observer =
                        pendingDiscovery.finish(begin.generation);
                if (observer != null) observer.success(bridges);
            }

            @Override
            public void failure(String message, boolean unauthorized) {
                BridgeClient.Result<List<BridgeClient.BridgeInfo>> observer =
                        pendingDiscovery.finish(begin.generation);
                if (observer != null) observer.failure(message, unauthorized);
            }
        });
        return this::cancelDiscovery;
    }

    void cancelDiscovery() {
        pendingDiscovery.cancel();
        discoveryRequest.cancel();
        discoveryRequest = BridgeClient.Cancellation.NONE;
    }

    RequestHandle resolveManual(String address,
                                BridgeClient.Result<BridgeClient.BridgeInfo> result) {
        long generation = ++manualGeneration;
        manualRequest.cancel();
        manualRequest = client.resolveManual(address, new BridgeClient.Result<>() {
            @Override public void success(BridgeClient.BridgeInfo value) {
                if (generation == manualGeneration) result.success(value);
            }

            @Override public void failure(String message, boolean unauthorized) {
                if (generation == manualGeneration) result.failure(message, unauthorized);
            }
        });
        return () -> cancelManualResolution(generation);
    }

    void cancelManualResolution() {
        cancelManualResolution(manualGeneration);
    }

    private void cancelManualResolution(long generation) {
        if (generation != manualGeneration) return;
        manualGeneration++;
        manualRequest.cancel();
        manualRequest = BridgeClient.Cancellation.NONE;
    }

    void pair(BridgeClient.BridgeInfo bridge, String code,
              BridgeClient.Result<SecureStore.SavedBridge> result) {
        if (forgetting) {
            result.failure("The saved computer is still being removed. Try again.", false);
            return;
        }
        if (pairingInFlight && !pendingPairing.isCurrent(activePairingGeneration)) {
            result.failure("The previous pairing is still being settled. Try again.", false);
            return;
        }
        PendingPairing.Begin begin = pendingPairing.beginOperation(bridge, result);
        if (begin.result == PendingPairing.BeginResult.ATTACHED) return;
        if (begin.result == PendingPairing.BeginResult.BUSY) {
            result.failure("Another computer is already being paired", false);
            return;
        }
        pairingInFlight = true;
        activePairingGeneration = begin.generation;
        storageWork(() -> {
            int count = store.pendingRevocations().size();
            if (store.requiresRecovery()) throw new IllegalStateException("Secure storage is unavailable");
            if (count >= SecureStore.MAX_PENDING_REVOCATIONS)
                throw new IllegalStateException("Retry pending removals before pairing");
            return store.clientId();
        }, (clientId, storageFailure) -> {
            if (storageFailure != null || !pendingPairing.isCurrent(begin.generation)) {
                finishPairingFailure(begin.generation,
                        "This phone could not save its pairing identity. Retry pending removals first.", false);
                return;
            }
            startPairing(bridge, code, clientId, begin.generation);
        });
    }

    private void startPairing(BridgeClient.BridgeInfo bridge, String code, String clientId,
                              long generation) {
        SecureStore.SavedBridge previous = current;
        client.pair(bridge, code, clientId, new BridgeClient.Result<>() {
            @Override
            public void success(String token) {
                SecureStore.SavedBridge issued = new SecureStore.SavedBridge(
                        bridge.id, bridge.name, bridge.host, bridge.port, bridge.tlsFingerprint, token);
                if (!pendingPairing.isCurrent(generation)) {
                    discardPairing(generation, issued, previous);
                    return;
                }
                storageWork(() -> {
                    store.save(bridge, token);
                    SecureStore.SavedBridge saved = store.load();
                    if (saved == null) throw new IllegalStateException("Secure storage failed");
                    return saved;
                }, (saved, storageFailure) -> {
                    if (storageFailure != null || !pendingPairing.isCurrent(generation)) {
                        discardPairing(generation, issued, previous);
                        return;
                    }
                    current = saved;
                    stopStateUpdates();
                    session.activate(saved);
                    if (libraryCache != null && previous != null)
                        libraryCache.clearScope(BridgeSession.cacheScope(previous));
                    if (artworkDiskCache != null && previous != null)
                        artworkDiskCache.clearScope(BridgeSession.cacheScope(previous));
                    artworkCache.evictAll();
                    missingArtwork.evictAll();
                    cancelPendingArtwork();
                    finishPairingSuccess(generation, saved);
                    retryPendingRevocations(null);
                });
            }

            @Override
            public void failure(String message, boolean unauthorized) {
                failure(message, unauthorized, BridgeClient.ErrorCode.NONE);
            }

            @Override
            public void failure(String message, boolean unauthorized,
                                BridgeClient.ErrorCode code) {
                BridgeClient.Result<SecureStore.SavedBridge> observer =
                        pendingPairing.finish(generation);
                pairingSettled();
                if (observer != null) observer.failure(message, unauthorized, code);
            }

            @Override public void rateLimited(int retryAfterSeconds) {
                BridgeClient.Result<SecureStore.SavedBridge> observer = pendingPairing.finish(generation);
                pairingSettled();
                if (observer != null) observer.rateLimited(retryAfterSeconds);
            }
        });
    }

    void cancelPairing() {
        pendingPairing.cancel();
        // The server may already have committed a token. Keep the bounded request alive so
        // its result can be durably queued for revocation even after the UI observer leaves.
    }

    RequestHandle observePairingSettlement(Runnable observer) {
        pairingSettlementObserver = observer;
        return () -> {
            if (pairingSettlementObserver == observer) pairingSettlementObserver = () -> { };
        };
    }

    private void discardPairing(long generation, SecureStore.SavedBridge issued,
                                SecureStore.SavedBridge previous) {
        storageWork(() -> store.discardIssuedPairing(issued, previous), (pending, failure) -> {
            if (current == previous && previous != null && previous.id.equals(issued.id))
                clearActiveRuntime();
            client.revokePairing(issued, new BridgeClient.Result<>() {
                @Override public void success(Boolean ignored) {
                    if (pending == null) finish();
                    else storageWork(() -> store.removePendingRevocation(pending.revocationId),
                            (removed, error) -> finish());
                }
                @Override public void failure(String message, boolean unauthorized) { finish(); }
                private void finish() {
                    finishPairingFailure(generation,
                            "Pairing could not be completed. Try again.", false);
                }
            });
        });
    }

    private void pairingSettled() {
        pairingInFlight = false;
        if (closed) finishClose();
        else pairingSettlementObserver.run();
    }

    private void finishPairingSuccess(long generation, SecureStore.SavedBridge saved) {
        BridgeClient.Result<SecureStore.SavedBridge> observer = pendingPairing.finish(generation);
        pairingSettled();
        if (observer != null) observer.success(saved);
    }

    private void finishPairingFailure(long generation, String message, boolean unauthorized) {
        BridgeClient.Result<SecureStore.SavedBridge> observer = pendingPairing.finish(generation);
        pairingSettled();
        if (observer != null) observer.failure(message, unauthorized);
    }

    /** Resolves code entry without sending or reusing the saved (possibly revoked) token. */
    RequestHandle resolvePairingEndpoint(BridgeClient.Result<BridgeClient.BridgeInfo> result) {
        SecureStore.SavedBridge trusted = current;
        if (trusted == null) {
            result.failure("No saved computer is available", false);
            return RequestHandle.NONE;
        }
        long generation = ++relocationGeneration;
        relocationRequest.cancel();
        BridgeClient.Result<BridgeClient.BridgeInfo> resolved = new BridgeClient.Result<>() {
            @Override public void success(BridgeClient.BridgeInfo bridge) {
                if (generation == relocationGeneration && current == trusted)
                    result.success(bridge);
            }

            @Override public void failure(String message, boolean unauthorized) {
                if (generation == relocationGeneration && current == trusted)
                    result.failure(message, unauthorized);
            }
        };
        relocationRequest = client.resolveManual(trusted.host + ":" + trusted.port,
                new BridgeClient.Result<>() {
            @Override public void success(BridgeClient.BridgeInfo bridge) {
                resolved.success(bridge);
            }

            @Override public void failure(String message, boolean unauthorized) {
                if (generation != relocationGeneration || current != trusted) return;
                relocationRequest = client.discover(new BridgeClient.Result<>() {
                    @Override public void success(List<BridgeClient.BridgeInfo> bridges) {
                        if (generation != relocationGeneration || current != trusted) return;
                        BridgeClient.BridgeInfo candidate = bridges.stream()
                                .filter(bridge -> trusted.id.equals(bridge.id))
                                .sorted(java.util.Comparator.comparing(bridge ->
                                        !trusted.tlsFingerprint.equals(bridge.tlsFingerprint)))
                                .findFirst().orElse(null);
                        if (candidate == null) {
                            resolved.failure("Could not find the paired computer on this network", false);
                            return;
                        }
                        relocationRequest = client.verifyIdentity(candidate, candidate.id,
                                candidate.tlsFingerprint, resolved);
                    }

                    @Override public void failure(String failure, boolean denied) {
                        resolved.failure(failure, denied);
                    }
                });
            }
        });
        return () -> cancelRelocation(generation);
    }

    RequestHandle relocateCurrent(BridgeClient.Result<Relocation> result) {
        SecureStore.SavedBridge trusted = current;
        if (trusted == null) {
            result.failure("No saved computer is available", false);
            return RequestHandle.NONE;
        }
        long generation = ++relocationGeneration;
        relocationRequest.cancel();
        relocationRequest = client.discover(new BridgeClient.Result<>() {
            @Override public void success(List<BridgeClient.BridgeInfo> bridges) {
                if (generation != relocationGeneration || current != trusted) return;
                BridgeClient.BridgeInfo exact = null;
                BridgeClient.BridgeInfo conflicting = null;
                for (BridgeClient.BridgeInfo bridge : bridges) {
                    if (!trusted.id.equals(bridge.id)) continue;
                    if (trusted.tlsFingerprint.equals(bridge.tlsFingerprint)) exact = bridge;
                    else if (conflicting == null) conflicting = bridge;
                }
                if (exact == null) {
                    if (conflicting != null) {
                        result.success(Relocation.identityChanged(trusted, conflicting));
                    } else {
                        result.failure("Could not find the paired computer on this network", false);
                    }
                    return;
                }
                verifyRelocation(generation, trusted, exact, result);
            }

            @Override public void failure(String message, boolean unauthorized) {
                if (generation == relocationGeneration && current == trusted)
                    result.failure(message, unauthorized);
            }
        });
        return () -> cancelRelocation(generation);
    }

    private void verifyRelocation(long generation, SecureStore.SavedBridge trusted,
                                  BridgeClient.BridgeInfo candidate,
                                  BridgeClient.Result<Relocation> result) {
        relocationRequest = client.verifyIdentity(candidate, trusted.id,
                trusted.tlsFingerprint, new BridgeClient.Result<>() {
            @Override public void success(BridgeClient.BridgeInfo verified) {
                if (generation != relocationGeneration || current != trusted) return;
                storageWork(() -> store.updateEndpoint(trusted, verified), (relocated, failure) -> {
                    if (generation != relocationGeneration || current != trusted || closed) return;
                    if (failure != null) {
                        if (generation == relocationGeneration)
                            result.failure("Found the computer, but could not save its new address", false);
                        return;
                    }
                    haltStateStream();
                    cancelPendingArtwork();
                    current = relocated;
                    session.activate(relocated);
                    if (generation == relocationGeneration)
                        result.success(Relocation.relocated(trusted, relocated));
                });
            }

            @Override public void failure(String message, boolean unauthorized) {
                if (generation == relocationGeneration && current == trusted)
                    result.failure(message, unauthorized);
            }
        });
    }

    void cancelRelocation() {
        cancelRelocation(relocationGeneration);
    }

    private void cancelRelocation(long generation) {
        if (generation != relocationGeneration) return;
        relocationInFlight.set(false);
        relocationGeneration++;
        relocationRequest.cancel();
        relocationRequest = BridgeClient.Cancellation.NONE;
    }

    void startStateUpdates(StateUpdatesListener listener) {
        BridgeSession.Request request = capture();
        if (request == null) return;
        if (updatesRequest != null && session.isCurrent(updatesRequest)) {
            client.requestStateRefresh();
            return;
        }
        haltStateStream();
        updatesRequest = request;
        updatesListener = listener;
        streamFailed = false;
        relocationInFlight.set(false);
        watchNetwork();
        client.startStateUpdates(request.bridge, new BridgeClient.StateListener() {
            @Override
            public void state(BridgeClient.PlayerState state) {
                if (session.isCurrent(request) && updatesRequest == request) {
                    if (relocationInFlight.getAndSet(false)) cancelRelocation();
                    listener.state(state);
                }
            }

            @Override
            public void connectionChanged(boolean connected, String message) {
                if (!session.isCurrent(request) || updatesRequest != request) return;
                // The client reports transitions only, so this runs once per (re)connection.
                streamFailed = !connected;
                listener.connectionChanged(request.bridge, connected, message);
                if (connected) retryPendingRevocationsAutomatically();
                else relocateIfIdle(request, listener);
            }

            @Override
            public void unauthorized(String message) {
                if (session.isCurrent(request) && updatesRequest == request)
                    listener.unauthorized(request.bridge, message);
            }
        });
    }

    /** Starts one rediscovery for a failed stream unless one is already running. */
    private void relocateIfIdle(BridgeSession.Request request, StateUpdatesListener listener) {
        if (!session.isCurrent(request) || updatesRequest != request
                || !relocationInFlight.compareAndSet(false, true)) return;
        relocateCurrent(new BridgeClient.Result<>() {
            @Override public void success(Relocation relocation) {
                // Updates may have been stopped (backgrounded, permission lost) meanwhile.
                if (updatesListener != listener) return;
                if (relocation.status == Relocation.Status.IDENTITY_CHANGED) {
                    listener.connectionChanged(relocation.trusted, false,
                            BridgeClient.IDENTITY_CHANGED_MESSAGE);
                } else {
                    startStateUpdates(listener);
                }
            }

            @Override public void failure(String ignored, boolean unauthorized) {
                // The stream retries with bounded backoff; allow its next failure
                // to rediscover a PC which was absent during this discovery window.
                relocationInFlight.set(false);
            }
        });
    }

    /** Stops state updates for the caller: no stream, no relocation restart, no network watch. */
    void stopStateUpdates() {
        updatesListener = null;
        streamFailed = false;
        unwatchNetwork();
        if (relocationInFlight.get()) cancelRelocation();
        haltStateStream();
    }

    /** Stops the current stream only; a relocation in progress may restart it. */
    private void haltStateStream() {
        updatesRequest = null;
        client.stopStateUpdates();
    }

    /** Wi-Fi or Ethernet became available: retry now instead of waiting out the backoff. */
    void networkAvailable() {
        StateUpdatesListener listener = updatesListener;
        BridgeSession.Request request = updatesRequest;
        if (listener == null || request == null || !session.isCurrent(request)) return;
        client.networkAvailable();
        if (streamFailed) relocateIfIdle(request, listener);
    }

    private void watchNetwork() {
        if (connectivity == null || networkCallback != null) return;
        Handler main = new Handler(Looper.getMainLooper());
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                main.post(BridgeRepository.this::networkAvailable);
            }
        };
        try {
            // No INTERNET capability is required: the bridge is on the local network.
            connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    .build(), callback);
            networkCallback = callback;
        } catch (RuntimeException unavailable) {
            // Recovery still happens through the stream's own reconnect backoff.
        }
    }

    private void unwatchNetwork() {
        ConnectivityManager.NetworkCallback callback = networkCallback;
        networkCallback = null;
        if (connectivity == null || callback == null) return;
        try {
            connectivity.unregisterNetworkCallback(callback);
        } catch (RuntimeException alreadyUnregistered) {
            // Nothing to release.
        }
    }

    void requestStateRefresh() {
        if (updatesRequest != null && session.isCurrent(updatesRequest))
            client.requestStateRefresh();
    }

    void refreshState(BridgeClient.Result<BridgeClient.PlayerState> result) {
        BridgeSession.Request request = capture();
        if (request != null) client.getState(request.bridge, guarded(request, result));
    }

    RequestHandle command(String command, Double value, BridgeClient.Result<Boolean> result) {
        BridgeSession.Request request = capture();
        if (request == null) return RequestHandle.NONE;
        DeferredRequest deferred = new DeferredRequest();
        // Share the storage barrier with selections so a following command cannot overtake
        // a selection whose sequence is still being committed.
        storageWork(() -> null, (ignored, failure) -> {
            if (deferred.cancelled || closed || !session.isCurrent(request)) return;
            deferred.network = client.command(request.bridge, command, value,
                    refreshAfterSuccess(request, result));
        });
        return deferred;
    }

    private static final class DeferredRequest implements RequestHandle {
        volatile boolean cancelled;
        BridgeClient.Cancellation network = BridgeClient.Cancellation.NONE;
        @Override public void cancel() {
            cancelled = true;
            network.cancel();
        }
    }

    public RequestHandle getLibrary(String query, int offset, int limit,
                                    PageResult<BridgeClient.LibraryPage> result) {
        BridgeSession.Request request = capture();
        if (request == null) return RequestHandle.NONE;
        String key = libraryRequestKey("songs", "", query, offset, limit);
        return loadCachedTracksThenNetwork(request, key, result,
                networkResult -> client.getLibrary(request.bridge, query, offset, limit,
                        networkResult));
    }

    RequestHandle getCollectionTracks(String kind, String id, String query, int offset, int limit,
                                      PageResult<BridgeClient.LibraryPage> result) {
        BridgeSession.Request request = capture();
        if (request == null) return RequestHandle.NONE;
        String key = libraryRequestKey(kind, id, query, offset, limit);
        return loadCachedTracksThenNetwork(request, key, result,
                networkResult -> client.getLibrary(request.bridge, query, offset, limit, kind, id,
                        networkResult));
    }

    RequestHandle getCollections(String kind, String query, int offset, int limit,
                                 PageResult<BridgeClient.LibraryCollectionPage> result) {
        return getCollections(kind, "", query, offset, limit, result);
    }

    RequestHandle getCollections(String kind, String parentId, String query, int offset, int limit,
                                 PageResult<BridgeClient.LibraryCollectionPage> result) {
        BridgeSession.Request request = capture();
        if (request == null) return RequestHandle.NONE;
        String key = libraryRequestKey(kind, parentId, query, offset, limit);
        if (libraryCache == null) {
            BridgeClient.Cancellation network = client.getCollections(
                    request.bridge, kind, parentId, query, offset, limit,
                    authoritativeOnly(request, result));
            return network::cancel;
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<BridgeClient.Cancellation> network =
                new AtomicReference<>(BridgeClient.Cancellation.NONE);
        LibraryCacheStore.LoadHandle load = libraryCache.loadCollections(
                BridgeSession.cacheScope(request.bridge), key, cached -> {
                    if (cancelled.get() || !session.isCurrent(request)) return;
                    if (cached != null) result.page(cached, false);
                    BridgeClient.Cancellation started = client.getCollections(request.bridge,
                            kind, parentId, query, offset, limit,
                            cacheCollectionsResult(request, key, cancelled, result));
                    network.set(started);
                    if (cancelled.get()) started.cancel();
                });
        return () -> {
            cancelled.set(true);
            load.cancel();
            network.get().cancel();
        };
    }

    private interface TrackNetworkStart {
        BridgeClient.Cancellation start(BridgeClient.Result<BridgeClient.LibraryPage> result);
    }

    private RequestHandle loadCachedTracksThenNetwork(
            BridgeSession.Request request,
            String key,
            PageResult<BridgeClient.LibraryPage> result,
            TrackNetworkStart networkStart) {
        if (libraryCache == null) {
            BridgeClient.Cancellation network = networkStart.start(
                    authoritativeOnly(request, result));
            return network::cancel;
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<BridgeClient.Cancellation> network =
                new AtomicReference<>(BridgeClient.Cancellation.NONE);
        LibraryCacheStore.LoadHandle load = libraryCache.loadTracks(
                BridgeSession.cacheScope(request.bridge), key, cached -> {
                    if (cancelled.get() || !session.isCurrent(request)) return;
                    if (cached != null) result.page(cached, false);
                    BridgeClient.Cancellation started = networkStart.start(
                            cacheTracksResult(request, key, cancelled, result));
                    network.set(started);
                    if (cancelled.get()) started.cancel();
                });
        return () -> {
            cancelled.set(true);
            load.cancel();
            network.get().cancel();
        };
    }

    private <T> BridgeClient.Result<T> authoritativeOnly(BridgeSession.Request request,
                                                         PageResult<T> result) {
        return pageNetworkResult(request, new AtomicBoolean(), result, null);
    }

    private BridgeClient.Result<BridgeClient.LibraryPage> cacheTracksResult(
            BridgeSession.Request request, String key, AtomicBoolean cancelled,
            PageResult<BridgeClient.LibraryPage> result) {
        return pageNetworkResult(request, cancelled, result, page ->
                libraryCache.saveTracks(BridgeSession.cacheScope(request.bridge), key, page));
    }

    private BridgeClient.Result<BridgeClient.LibraryCollectionPage> cacheCollectionsResult(
            BridgeSession.Request request, String key, AtomicBoolean cancelled,
            PageResult<BridgeClient.LibraryCollectionPage> result) {
        return pageNetworkResult(request, cancelled, result, page ->
                libraryCache.saveCollections(BridgeSession.cacheScope(request.bridge), key, page));
    }

    /** Delivers an authoritative page (optionally caching it) or a typed failure. */
    private <T> BridgeClient.Result<T> pageNetworkResult(
            BridgeSession.Request request, AtomicBoolean cancelled, PageResult<T> result,
            java.util.function.Consumer<T> cache) {
        return new BridgeClient.Result<>() {
            @Override public void success(T value) {
                if (cancelled.get() || !session.isCurrent(request)) return;
                if (cache != null) cache.accept(value);
                result.page(value, true);
            }

            @Override public void failure(String message, boolean unauthorized) {
                failure(message, unauthorized, BridgeClient.ErrorCode.NONE);
            }

            @Override public void failure(String message, boolean unauthorized,
                                          BridgeClient.ErrorCode code) {
                if (!cancelled.get() && session.isCurrent(request))
                    result.failure(message, unauthorized, code);
            }
        };
    }

    private static String libraryRequestKey(String kind, String collectionId, String query,
                                            int offset, int limit) {
        return kind + "\u001f" + (collectionId == null ? "" : collectionId) + "\u001f"
                + (query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT))
                + "\u001f" + Math.max(0, offset) + "\u001f" + Math.max(1, limit);
    }

    RequestHandle playTrack(String trackId, String collectionKind, String collectionId,
                            BridgeClient.Result<Boolean> result) {
        BridgeSession.Request request = capture();
        if (request == null) {
            result.failure("Computer is not connected", false);
            return RequestHandle.NONE;
        }
        DeferredRequest deferred = new DeferredRequest();
        storageWork(() -> deferred.cancelled ? 0L : store.nextPlaybackSequence(), (sequence, failure) -> {
            if (deferred.cancelled || closed || !session.isCurrent(request)) return;
            if (failure != null) {
                result.failure("Could not safely save the playback request. Try again.", false);
                return;
            }
            deferred.network = client.playTrack(request.bridge, trackId,
                    collectionKind, collectionId, sequence, refreshAfterSuccess(request, result));
        });
        return deferred;
    }

    Bitmap cachedArtwork(String artworkId, int size) {
        SecureStore.SavedBridge bridge = current;
        CachedArtwork cached = bridge == null ? null : retainedArtwork(
                BridgeSession.artworkCacheKey(bridge, artworkId, size));
        return cached == null ? null : cached.bitmap();
    }

    private CachedArtwork retainedArtwork(String key) {
        CachedArtwork cached = artworkCache.get(key);
        if (cached == null) return null;
        if (ArtworkDiskCache.isRetained(cached.fetchedAt(), System.currentTimeMillis()))
            return cached;
        artworkCache.remove(key);
        return null;
    }

    RequestHandle getArtwork(String artworkId, int size, ArtworkResult result) {
        BridgeSession.Request request = capture();
        if (request == null) return RequestHandle.NONE;
        String cacheKey = BridgeSession.artworkCacheKey(request.bridge, artworkId, size);
        Long missingAt = missingArtwork.get(cacheKey);
        if (missingAt != null && SystemClock.elapsedRealtime() - missingAt
                < MISSING_ARTWORK_TTL_MS) {
            result.success(null);
            return RequestHandle.NONE;
        }
        if (missingAt != null) missingArtwork.remove(cacheKey);
        CachedArtwork cached = retainedArtwork(cacheKey);
        if (cached != null && ArtworkDiskCache.isFresh(cached.fetchedAt(), System.currentTimeMillis())) {
            result.success(cached.bitmap());
            return RequestHandle.NONE;
        }
        PendingArtwork pending = pendingArtwork.get(cacheKey);
        ArtworkObserver observer = new ArtworkObserver(result);
        if (pending != null) {
            PendingArtwork joined = pending;
            joined.observers.add(observer);
            if (joined.preview != null) result.cached(joined.preview);
            return () -> cancelArtworkObserver(cacheKey, joined, observer);
        }
        pending = new PendingArtwork(request);
        pending.observers.add(observer);
        pendingArtwork.put(cacheKey, pending);
        PendingArtwork started = pending;
        if (cached != null) {
            deliverCachedArtwork(started, cached.bitmap());
            startArtworkNetwork(cacheKey, artworkId, size, started);
        } else if (artworkDiskCache != null) {
            started.diskRequest = artworkDiskCache.load(
                    BridgeSession.cacheScope(request.bridge), cacheKey, (bitmap, fetchedAt) -> {
                        if (pendingArtwork.get(cacheKey) != started
                                || !session.isCurrent(started.request)) return;
                        if (bitmap == null) {
                            startArtworkNetwork(cacheKey, artworkId, size, started);
                            return;
                        }
                        artworkCache.put(cacheKey, new CachedArtwork(bitmap, fetchedAt));
                        if (ArtworkDiskCache.isFresh(fetchedAt, System.currentTimeMillis())) {
                            if (!pendingArtwork.remove(cacheKey, started)) return;
                            for (ArtworkObserver target : new ArrayList<>(started.observers)) {
                                if (!target.cancelled && session.isCurrent(started.request))
                                    target.result.success(bitmap);
                            }
                        } else {
                            deliverCachedArtwork(started, bitmap);
                            startArtworkNetwork(cacheKey, artworkId, size, started);
                        }
                    });
        } else {
            startArtworkNetwork(cacheKey, artworkId, size, started);
        }
        return () -> cancelArtworkObserver(cacheKey, started, observer);
    }

    private void deliverCachedArtwork(PendingArtwork pending, Bitmap bitmap) {
        pending.preview = bitmap;
        for (ArtworkObserver target : new ArrayList<>(pending.observers)) {
            if (!target.cancelled && session.isCurrent(pending.request)) target.result.cached(bitmap);
        }
    }

    private void startArtworkNetwork(String cacheKey, String artworkId, int size,
                                     PendingArtwork started) {
        // A cached callback may cancel the final observer or change the paired computer.
        if (pendingArtwork.get(cacheKey) != started || !session.isCurrent(started.request)) return;
        started.networkRequest = client.getArtwork(started.request.bridge, artworkId, size,
                new BridgeClient.Result<>() {
            @Override
            public void success(Bitmap bitmap) {
                if (!pendingArtwork.remove(cacheKey, started)
                        || !session.isCurrent(started.request)) return;
                if (bitmap != null) {
                    missingArtwork.remove(cacheKey);
                    artworkCache.put(cacheKey, new CachedArtwork(bitmap, System.currentTimeMillis()));
                    if (artworkDiskCache != null) artworkDiskCache.save(
                            BridgeSession.cacheScope(started.request.bridge), cacheKey, bitmap);
                } else {
                    artworkCache.remove(cacheKey);
                    if (artworkDiskCache != null) artworkDiskCache.remove(
                            BridgeSession.cacheScope(started.request.bridge), cacheKey);
                    missingArtwork.put(cacheKey, SystemClock.elapsedRealtime());
                }
                for (ArtworkObserver target : new ArrayList<>(started.observers)) {
                    if (!target.cancelled && session.isCurrent(started.request)) target.result.success(bitmap);
                }
            }

            @Override
            public void failure(String message, boolean unauthorized) {
                if (!pendingArtwork.remove(cacheKey, started)
                        || !session.isCurrent(started.request)) return;
                for (ArtworkObserver target : new ArrayList<>(started.observers)) {
                    if (!target.cancelled && session.isCurrent(started.request))
                        target.result.failure(message, unauthorized);
                }
            }
        });
    }

    private void cancelArtworkObserver(String cacheKey, PendingArtwork pending,
                                       ArtworkObserver observer) {
        observer.cancelled = true;
        pending.observers.remove(observer);
        if (pending.observers.isEmpty() && pendingArtwork.remove(cacheKey, pending)) {
            pending.diskRequest.cancel();
            pending.networkRequest.cancel();
        }
    }

    private void cancelPendingArtwork() {
        for (PendingArtwork pending : pendingArtwork.values())
        {
            pending.diskRequest.cancel();
            pending.networkRequest.cancel();
        }
        pendingArtwork.clear();
    }

    void forgetFromBridge(BridgeClient.Result<Boolean> result) {
        if (pairingInFlight || forgetting) {
            result.failure("A pairing change is still being saved. Try again.", false);
            return;
        }
        forgetting = true;
        storageWork(store::prepareForget, (pending, failure) -> {
            forgetting = false;
            if (failure != null) {
                result.failure("Could not safely remove this computer", false);
                return;
            }
            clearActiveRuntime();
            if (pending == null) {
                result.success(true);
                return;
            }
            client.revokePairing(pending.bridge, new BridgeClient.Result<>() {
                @Override public void success(Boolean value) {
                    storageWork(() -> store.removePendingRevocation(pending.revocationId), (removed, error) -> {
                        if (error == null) result.success(true);
                        else result.failure("Removed on the computer, but cleanup will be retried", false);
                    });
                }

                @Override public void failure(String message, boolean unauthorized) {
                    result.failure("Removed from this phone, but still authorized on the computer. "
                            + message, unauthorized);
                }
            });
        });
    }

    int pendingRevocationCount() {
        return pendingRevocations;
    }

    void retryPendingRevocations(BridgeClient.Result<Integer> result) {
        if (result != null) revocationRetryObservers.add(result);
        if (retryingRevocations) return;
        retryingRevocations = true;
        storageWork(store::pendingRevocations, (pending, failure) -> {
            if (failure != null) {
                retryingRevocations = false;
                finishRevocationRetry(0, "Could not read pending removals");
            } else retryPendingRevocation(pending, 0, 0, null);
        });
    }

    /**
     * Retries queued revocations after the stream (re)connects, at most once per backoff window,
     * so an unreachable old computer is not contacted on every reconnect.
     */
    private void retryPendingRevocationsAutomatically() {
        long now = SystemClock.elapsedRealtime();
        if (retryingRevocations || now < automaticRevocationRetryAt
                || pendingRevocations == 0) return;
        automaticRevocationRetryAt = now + automaticRevocationBackoffMs;
        retryPendingRevocations(new BridgeClient.Result<>() {
            @Override public void success(Integer removed) {
                if (pendingRevocations == 0) {
                    automaticRevocationBackoffMs = MIN_REVOCATION_RETRY_MS;
                    automaticRevocationRetryAt = 0;
                } else {
                    backOffAutomaticRevocations();
                }
            }

            @Override public void failure(String message, boolean unauthorized) {
                backOffAutomaticRevocations();
            }
        });
    }

    private void backOffAutomaticRevocations() {
        automaticRevocationBackoffMs = Math.min(MAX_REVOCATION_RETRY_MS,
                automaticRevocationBackoffMs * 2);
    }

    private void retryPendingRevocation(List<SecureStore.PendingRevocation> pending, int index,
                                        int removed, String firstFailure) {
        if (index >= pending.size()) {
            retryingRevocations = false;
            finishRevocationRetry(removed, firstFailure);
            return;
        }
        SecureStore.PendingRevocation item = pending.get(index);
        SecureStore.SavedBridge active = current;
        if (active != null && active.id.equals(item.bridge.id)) {
            // Pairing with that computer again already replaced this authorization there; its
            // old certificate may be gone, so contacting it could never succeed.
            removeRevocationAndContinue(pending, index, removed, firstFailure);
            return;
        }
        client.revokePairing(item.bridge, new BridgeClient.Result<>() {
            @Override public void success(Boolean ignored) {
                removeRevocationAndContinue(pending, index, removed, firstFailure);
            }

            @Override public void failure(String message, boolean unauthorized) {
                retryPendingRevocation(pending, index + 1, removed,
                        firstFailure == null ? message : firstFailure);
            }
        });
    }

    private void removeRevocationAndContinue(List<SecureStore.PendingRevocation> pending,
                                             int index, int removed, String firstFailure) {
        storageWork(() -> store.removePendingRevocation(pending.get(index).revocationId),
                (ignored, failure) -> retryPendingRevocation(pending, index + 1,
                        failure == null ? removed + 1 : removed,
                        failure != null && firstFailure == null
                                ? "Could not save revocation cleanup" : firstFailure));
    }

    private void finishRevocationRetry(int removed, String failure) {
        if (revocationRetryObservers.isEmpty()) return;
        ArrayList<BridgeClient.Result<Integer>> observers =
                new ArrayList<>(revocationRetryObservers);
        revocationRetryObservers.clear();
        for (BridgeClient.Result<Integer> observer : observers) {
            if (failure == null) observer.success(removed);
            else observer.failure(failure, false);
        }
    }

    private void clearActiveRuntime() {
        SecureStore.SavedBridge active = current;
        stopStateUpdates();
        session.clear();
        current = null;
        if (libraryCache != null && active != null)
            libraryCache.clearScope(BridgeSession.cacheScope(active));
        if (artworkDiskCache != null && active != null)
            artworkDiskCache.clearScope(BridgeSession.cacheScope(active));
        artworkCache.evictAll();
        missingArtwork.evictAll();
        cancelPendingArtwork();
    }

    private BridgeSession.Request capture() {
        return session.capture();
    }

    private <T> BridgeClient.Result<T> guarded(BridgeSession.Request request,
                                                BridgeClient.Result<T> result) {
        return new BridgeClient.Result<>() {
            @Override
            public void success(T value) {
                if (session.isCurrent(request)) result.success(value);
            }

            @Override
            public void failure(String message, boolean unauthorized) {
                if (session.isCurrent(request)) result.failure(message, unauthorized);
            }
        };
    }

    private <T> BridgeClient.Result<T> refreshAfterSuccess(BridgeSession.Request request,
                                                            BridgeClient.Result<T> result) {
        return new BridgeClient.Result<>() {
            @Override
            public void success(T value) {
                if (!session.isCurrent(request)) return;
                client.requestStateRefresh();
                result.success(value);
            }

            @Override
            public void failure(String message, boolean unauthorized) {
                failure(message, unauthorized, BridgeClient.ErrorCode.NONE);
            }

            @Override
            public void failure(String message, boolean unauthorized,
                                BridgeClient.ErrorCode code) {
                if (session.isCurrent(request)) result.failure(message, unauthorized, code);
            }
        };
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        readyObservers.clear();
        cancelDiscovery();
        cancelManualResolution();
        cancelPairing();
        cancelRelocation();
        stopStateUpdates();
        session.clear();
        // Pairing owns a server-side transaction; let its bounded response and revocation
        // finish before closing the transport and the durable-storage lane.
        if (!pairingInFlight) finishClose();
        if (libraryCache != null) libraryCache.close();
        if (artworkDiskCache != null) artworkDiskCache.close();
        artworkCache.evictAll();
        missingArtwork.evictAll();
        cancelPendingArtwork();
    }

    private void finishClose() {
        client.close();
        if (storage instanceof ExecutorService executor) executor.shutdown();
    }
}
