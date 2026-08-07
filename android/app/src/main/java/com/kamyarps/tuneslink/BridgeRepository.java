package com.kamyarps.tuneslink;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.LruCache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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

    interface RequestHandle {
        RequestHandle NONE = () -> { };
        void cancel();
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
        final BridgeClient.Result<Bitmap> result;
        boolean cancelled;

        ArtworkObserver(BridgeClient.Result<Bitmap> result) {
            this.result = result;
        }
    }

    private static final class PendingArtwork {
        final BridgeSession.Request request;
        final ArrayList<ArtworkObserver> observers = new ArrayList<>();
        BridgeClient.Cancellation networkRequest = BridgeClient.Cancellation.NONE;
        ArtworkDiskCache.Handle diskRequest = () -> { };

        PendingArtwork(BridgeSession.Request request) {
            this.request = request;
        }
    }

    private final BridgeClient client;
    private final SecureStore store;
    private final LibraryCacheStore libraryCache;
    private final ArtworkDiskCache artworkDiskCache;
    private final BridgeSession session = new BridgeSession();
    private final LruCache<String, Bitmap> artworkCache =
            new LruCache<>(ARTWORK_CACHE_KIB) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return Math.max(1, value.getAllocationByteCount() / 1024);
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
    private BridgeClient.Cancellation pairingRequest = BridgeClient.Cancellation.NONE;
    private BridgeClient.Cancellation relocationRequest = BridgeClient.Cancellation.NONE;
    private long manualGeneration;
    private long relocationGeneration;
    private boolean retryingRevocations;
    private final ArrayList<BridgeClient.Result<Integer>> revocationRetryObservers =
            new ArrayList<>();

    interface StateUpdatesListener {
        void state(BridgeClient.PlayerState state);
        void connectionChanged(SecureStore.SavedBridge bridge, boolean connected, String message);
        void unauthorized(SecureStore.SavedBridge bridge, String message);
    }

    BridgeRepository(Context context) {
        this(new BridgeClient(), new SecureStore(context), new LibraryCacheStore(context),
                new ArtworkDiskCache(context));
    }

    BridgeRepository(BridgeClient client, SecureStore store) {
        this(client, store, null, null);
    }

    private BridgeRepository(BridgeClient client, SecureStore store,
                             LibraryCacheStore libraryCache,
                             ArtworkDiskCache artworkDiskCache) {
        this.client = client;
        this.store = store;
        this.libraryCache = libraryCache;
        this.artworkDiskCache = artworkDiskCache;
        current = store.load();
        if (current != null) session.activate(current);
    }

    SecureStore.SavedBridge current() {
        return current;
    }

    boolean secureStorageRequiresRecovery() {
        return store.requiresRecovery();
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
        PendingPairing.Begin begin = pendingPairing.beginOperation(bridge, result);
        if (begin.result == PendingPairing.BeginResult.ATTACHED) return;
        if (begin.result == PendingPairing.BeginResult.BUSY) {
            result.failure("Another computer is already being paired", false);
            return;
        }
        final String clientId;
        try {
            clientId = store.clientId();
        } catch (RuntimeException storageFailure) {
            finishPairingFailure(begin.generation,
                    "This phone could not save its pairing identity. Try again.", false);
            return;
        }
        pairingRequest = client.pair(bridge, code, clientId, new BridgeClient.Result<>() {
            @Override
            public void success(String token) {
                if (!pendingPairing.isCurrent(begin.generation)) return;
                try {
                    SecureStore.SavedBridge previous = current;
                    store.save(bridge, token);
                    SecureStore.SavedBridge saved = store.load();
                    if (saved == null) throw new IllegalStateException("Secure storage failed");
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
                    finishPairingSuccess(begin.generation, saved);
                    retryPendingRevocations(null);
                } catch (Exception storageFailure) {
                    SecureStore.SavedBridge orphan = new SecureStore.SavedBridge(
                            bridge.id, bridge.name, bridge.host, bridge.port,
                            bridge.tlsFingerprint, token);
                    client.revokePairing(orphan, new BridgeClient.Result<>() {
                        @Override public void success(Boolean ignored) { }
                        @Override public void failure(String ignored, boolean unauthorized) { }
                    });
                    finishPairingFailure(begin.generation,
                            "Pairing succeeded, but this phone could not save its credentials. Try again.",
                            false);
                }
            }

            @Override
            public void failure(String message, boolean unauthorized) {
                finishPairingFailure(begin.generation, message, unauthorized);
            }
        });
    }

    void cancelPairing() {
        pendingPairing.cancel();
        pairingRequest.cancel();
        pairingRequest = BridgeClient.Cancellation.NONE;
    }

    private void finishPairingSuccess(long generation, SecureStore.SavedBridge saved) {
        BridgeClient.Result<SecureStore.SavedBridge> observer = pendingPairing.finish(generation);
        if (observer != null) observer.success(saved);
    }

    private void finishPairingFailure(long generation, String message, boolean unauthorized) {
        BridgeClient.Result<SecureStore.SavedBridge> observer = pendingPairing.finish(generation);
        if (observer != null) observer.failure(message, unauthorized);
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
                try {
                    SecureStore.SavedBridge relocated = store.updateEndpoint(trusted, verified);
                    stopStateUpdates();
                    current = relocated;
                    session.activate(relocated);
                    result.success(Relocation.relocated(trusted, relocated));
                } catch (Exception persistenceFailure) {
                    result.failure("Found the computer, but could not save its new address", false);
                }
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
        relocationGeneration++;
        relocationRequest.cancel();
        relocationRequest = BridgeClient.Cancellation.NONE;
    }

    void getState(BridgeClient.Result<BridgeClient.PlayerState> result) {
        BridgeSession.Request request = capture();
        if (request == null) return;
        client.getState(request.bridge, new BridgeClient.Result<>() {
            @Override public void success(BridgeClient.PlayerState value) {
                if (session.isCurrent(request)) result.success(value);
            }

            @Override public void failure(String message, boolean unauthorized) {
                if (!session.isCurrent(request)) return;
                if (unauthorized) {
                    result.failure(message, true);
                    return;
                }
                relocateCurrent(new BridgeClient.Result<>() {
                    @Override public void success(Relocation relocation) {
                        if (relocation.status == Relocation.Status.IDENTITY_CHANGED) {
                            result.failure(BridgeClient.IDENTITY_CHANGED_MESSAGE, false);
                            return;
                        }
                        BridgeSession.Request retry = capture();
                        if (retry != null) client.getState(retry.bridge, guarded(retry, result));
                    }

                    @Override public void failure(String relocationMessage,
                                                  boolean relocationUnauthorized) {
                        result.failure(message, false);
                    }
                });
            }
        });
    }

    void startStateUpdates(StateUpdatesListener listener) {
        BridgeSession.Request request = capture();
        if (request == null) return;
        if (updatesRequest != null && session.isCurrent(updatesRequest)) {
            client.requestStateRefresh();
            return;
        }
        stopStateUpdates();
        updatesRequest = request;
        AtomicBoolean relocationAttempted = new AtomicBoolean();
        client.startStateUpdates(request.bridge, new BridgeClient.StateListener() {
            @Override
            public void state(BridgeClient.PlayerState state) {
                if (session.isCurrent(request) && updatesRequest == request) {
                    relocationAttempted.set(false);
                    listener.state(state);
                }
            }

            @Override
            public void connectionChanged(boolean connected, String message) {
                if (session.isCurrent(request) && updatesRequest == request)
                    listener.connectionChanged(request.bridge, connected, message);
                if (connected) retryPendingRevocations(null);
                if (!connected && session.isCurrent(request) && updatesRequest == request
                        && relocationAttempted.compareAndSet(false, true)) {
                    relocateCurrent(new BridgeClient.Result<>() {
                        @Override public void success(Relocation relocation) {
                            if (relocation.status == Relocation.Status.IDENTITY_CHANGED) {
                                listener.connectionChanged(relocation.trusted, false,
                                        BridgeClient.IDENTITY_CHANGED_MESSAGE);
                            } else {
                                startStateUpdates(listener);
                            }
                        }

                        @Override public void failure(String ignored, boolean unauthorized) {
                            // The stream's bounded reconnect loop remains the source of status.
                        }
                    });
                }
            }

            @Override
            public void unauthorized(String message) {
                if (session.isCurrent(request) && updatesRequest == request)
                    listener.unauthorized(request.bridge, message);
            }
        });
    }

    void stopStateUpdates() {
        updatesRequest = null;
        client.stopStateUpdates();
    }

    void requestStateRefresh() {
        if (updatesRequest != null && session.isCurrent(updatesRequest))
            client.requestStateRefresh();
    }

    void command(String command, Double value, BridgeClient.Result<Boolean> result) {
        BridgeSession.Request request = capture();
        if (request != null) client.command(request.bridge, command, value,
                refreshAfterSuccess(request, result));
    }

    public RequestHandle getLibrary(String query, int offset, int limit,
                                    BridgeClient.Result<BridgeClient.LibraryPage> result) {
        BridgeSession.Request request = capture();
        if (request == null) return RequestHandle.NONE;
        String key = libraryRequestKey("songs", "", query, offset, limit);
        return loadCachedTracksThenNetwork(request, key, result,
                networkResult -> client.getLibrary(request.bridge, query, offset, limit,
                        networkResult));
    }

    RequestHandle getCollectionTracks(String kind, String id, String query, int offset, int limit,
                                      BridgeClient.Result<BridgeClient.LibraryPage> result) {
        BridgeSession.Request request = capture();
        if (request == null) return RequestHandle.NONE;
        String key = libraryRequestKey(kind, id, query, offset, limit);
        return loadCachedTracksThenNetwork(request, key, result,
                networkResult -> client.getLibrary(request.bridge, query, offset, limit, kind, id,
                        networkResult));
    }

    RequestHandle getCollections(String kind, String query, int offset, int limit,
                                 BridgeClient.Result<BridgeClient.LibraryCollectionPage> result) {
        BridgeSession.Request request = capture();
        if (request == null) return RequestHandle.NONE;
        String key = libraryRequestKey(kind, "", query, offset, limit);
        if (libraryCache == null) {
            BridgeClient.Cancellation network = client.getCollections(
                    request.bridge, kind, query, offset, limit,
                    guarded(request, result));
            return network::cancel;
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<BridgeClient.Cancellation> network =
                new AtomicReference<>(BridgeClient.Cancellation.NONE);
        LibraryCacheStore.LoadHandle load = libraryCache.loadCollections(
                BridgeSession.cacheScope(request.bridge), key, cached -> {
                    if (cancelled.get() || !session.isCurrent(request)) return;
                    if (cached != null) result.success(cached);
                    BridgeClient.Cancellation started = client.getCollections(request.bridge,
                            kind, query, offset, limit,
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
            BridgeClient.Result<BridgeClient.LibraryPage> result,
            TrackNetworkStart networkStart) {
        if (libraryCache == null) {
            BridgeClient.Cancellation network = networkStart.start(guarded(request, result));
            return network::cancel;
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<BridgeClient.Cancellation> network =
                new AtomicReference<>(BridgeClient.Cancellation.NONE);
        LibraryCacheStore.LoadHandle load = libraryCache.loadTracks(
                BridgeSession.cacheScope(request.bridge), key, cached -> {
                    if (cancelled.get() || !session.isCurrent(request)) return;
                    if (cached != null) result.success(cached);
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

    private BridgeClient.Result<BridgeClient.LibraryPage> cacheTracksResult(
            BridgeSession.Request request, String key, AtomicBoolean cancelled,
            BridgeClient.Result<BridgeClient.LibraryPage> result) {
        return new BridgeClient.Result<>() {
            @Override public void success(BridgeClient.LibraryPage value) {
                if (cancelled.get() || !session.isCurrent(request)) return;
                libraryCache.saveTracks(BridgeSession.cacheScope(request.bridge), key, value);
                result.success(value);
            }

            @Override public void failure(String message, boolean unauthorized) {
                if (!cancelled.get() && session.isCurrent(request))
                    result.failure(message, unauthorized);
            }
        };
    }

    private BridgeClient.Result<BridgeClient.LibraryCollectionPage> cacheCollectionsResult(
            BridgeSession.Request request, String key, AtomicBoolean cancelled,
            BridgeClient.Result<BridgeClient.LibraryCollectionPage> result) {
        return new BridgeClient.Result<>() {
            @Override public void success(BridgeClient.LibraryCollectionPage value) {
                if (cancelled.get() || !session.isCurrent(request)) return;
                libraryCache.saveCollections(BridgeSession.cacheScope(request.bridge), key, value);
                result.success(value);
            }

            @Override public void failure(String message, boolean unauthorized) {
                if (!cancelled.get() && session.isCurrent(request))
                    result.failure(message, unauthorized);
            }
        };
    }

    private static String libraryRequestKey(String kind, String collectionId, String query,
                                            int offset, int limit) {
        return kind + "\u001f" + (collectionId == null ? "" : collectionId) + "\u001f"
                + (query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT))
                + "\u001f" + Math.max(0, offset) + "\u001f" + Math.max(1, limit);
    }

    void playTrack(String trackId, String collectionKind, String collectionId,
                   BridgeClient.Result<Boolean> result) {
        BridgeSession.Request request = capture();
        if (request != null) client.playTrack(request.bridge, trackId,
                collectionKind, collectionId,
                refreshAfterSuccess(request, result));
    }

    Bitmap cachedArtwork(String artworkId, int size) {
        SecureStore.SavedBridge bridge = current;
        return bridge == null ? null : artworkCache.get(
                BridgeSession.artworkCacheKey(bridge, artworkId, size));
    }

    RequestHandle getArtwork(String artworkId, int size, BridgeClient.Result<Bitmap> result) {
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
        Bitmap cached = artworkCache.get(cacheKey);
        if (cached != null) {
            result.success(cached);
            return RequestHandle.NONE;
        }
        PendingArtwork pending = pendingArtwork.get(cacheKey);
        ArtworkObserver observer = new ArtworkObserver(result);
        if (pending != null) {
            PendingArtwork joined = pending;
            joined.observers.add(observer);
            return () -> cancelArtworkObserver(cacheKey, joined, observer);
        }
        pending = new PendingArtwork(request);
        pending.observers.add(observer);
        pendingArtwork.put(cacheKey, pending);
        PendingArtwork started = pending;
        if (artworkDiskCache != null) {
            started.diskRequest = artworkDiskCache.load(
                    BridgeSession.cacheScope(request.bridge), cacheKey, bitmap -> {
                        if (pendingArtwork.get(cacheKey) != started
                                || !session.isCurrent(started.request)) return;
                        if (bitmap == null) {
                            startArtworkNetwork(cacheKey, artworkId, size, started);
                            return;
                        }
                        if (!pendingArtwork.remove(cacheKey, started)) return;
                        artworkCache.put(cacheKey, bitmap);
                        for (ArtworkObserver target : started.observers) {
                            if (!target.cancelled) target.result.success(bitmap);
                        }
                    });
        } else {
            startArtworkNetwork(cacheKey, artworkId, size, started);
        }
        return () -> cancelArtworkObserver(cacheKey, started, observer);
    }

    private void startArtworkNetwork(String cacheKey, String artworkId, int size,
                                     PendingArtwork started) {
        started.networkRequest = client.getArtwork(started.request.bridge, artworkId, size,
                new BridgeClient.Result<>() {
            @Override
            public void success(Bitmap bitmap) {
                if (!pendingArtwork.remove(cacheKey, started)
                        || !session.isCurrent(started.request)) return;
                if (bitmap != null) {
                    missingArtwork.remove(cacheKey);
                    artworkCache.put(cacheKey, bitmap);
                    if (artworkDiskCache != null) artworkDiskCache.save(
                            BridgeSession.cacheScope(started.request.bridge), cacheKey, bitmap);
                } else {
                    missingArtwork.put(cacheKey, SystemClock.elapsedRealtime());
                }
                for (ArtworkObserver target : started.observers) {
                    if (!target.cancelled) target.result.success(bitmap);
                }
            }

            @Override
            public void failure(String message, boolean unauthorized) {
                if (!pendingArtwork.remove(cacheKey, started)
                        || !session.isCurrent(started.request)) return;
                for (ArtworkObserver target : started.observers) {
                    if (!target.cancelled) target.result.failure(message, unauthorized);
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
        final SecureStore.PendingRevocation pending;
        try {
            pending = store.prepareForget();
        } catch (Exception persistenceFailure) {
            result.failure(persistenceFailure.getMessage() == null
                    ? "Could not safely remove this computer"
                    : persistenceFailure.getMessage(), false);
            return;
        }
        clearActiveRuntime();
        if (pending == null) {
            result.success(true);
            return;
        }
        client.revokePairing(pending.bridge, new BridgeClient.Result<>() {
            @Override public void success(Boolean value) {
                try {
                    store.removePendingRevocation(pending.revocationId);
                    result.success(true);
                } catch (Exception persistenceFailure) {
                    result.failure("Removed on the computer, but cleanup will be retried", false);
                }
            }

            @Override public void failure(String message, boolean unauthorized) {
                result.failure("Removed from this phone, but still authorized on the computer. "
                        + message, unauthorized);
            }
        });
    }

    int pendingRevocationCount() {
        return store.pendingRevocationCount();
    }

    void retryPendingRevocations(BridgeClient.Result<Integer> result) {
        if (result != null) revocationRetryObservers.add(result);
        if (retryingRevocations) return;
        List<SecureStore.PendingRevocation> pending = store.pendingRevocations();
        if (pending.isEmpty()) {
            finishRevocationRetry(0, null);
            return;
        }
        retryingRevocations = true;
        retryPendingRevocation(pending, 0, 0, null);
    }

    private void retryPendingRevocation(List<SecureStore.PendingRevocation> pending, int index,
                                        int removed, String firstFailure) {
        if (index >= pending.size()) {
            retryingRevocations = false;
            finishRevocationRetry(removed, firstFailure);
            return;
        }
        SecureStore.PendingRevocation item = pending.get(index);
        client.revokePairing(item.bridge, new BridgeClient.Result<>() {
            @Override public void success(Boolean ignored) {
                try {
                    store.removePendingRevocation(item.revocationId);
                    retryPendingRevocation(pending, index + 1, removed + 1, firstFailure);
                } catch (Exception persistenceFailure) {
                    retryPendingRevocation(pending, index + 1, removed,
                            firstFailure == null ? "Could not save revocation cleanup" : firstFailure);
                }
            }

            @Override public void failure(String message, boolean unauthorized) {
                retryPendingRevocation(pending, index + 1, removed,
                        firstFailure == null ? message : firstFailure);
            }
        });
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
                if (session.isCurrent(request)) result.failure(message, unauthorized);
            }
        };
    }

    @Override
    public void close() {
        cancelDiscovery();
        cancelManualResolution();
        cancelPairing();
        cancelRelocation();
        stopStateUpdates();
        client.close();
        if (libraryCache != null) libraryCache.close();
        if (artworkDiskCache != null) artworkDiskCache.close();
        artworkCache.evictAll();
        missingArtwork.evictAll();
        cancelPendingArtwork();
    }
}
