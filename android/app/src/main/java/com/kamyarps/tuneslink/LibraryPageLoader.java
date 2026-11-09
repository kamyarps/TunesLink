package com.kamyarps.tuneslink;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Delivers optional cached pages before authoritative network results for the current session. */
final class LibraryPageLoader {
    private final BridgeClient client;
    private final LibraryCacheStore libraryCache;
    private final BridgeSession session;

    LibraryPageLoader(BridgeClient client, LibraryCacheStore libraryCache, BridgeSession session) {
        this.client = client;
        this.libraryCache = libraryCache;
        this.session = session;
    }

    BridgeRepository.RequestHandle getCollections(BridgeSession.Request request, String kind, String parentId, String query, int offset, int limit,
                                 BridgeRepository.PageResult<BridgeClient.LibraryCollectionPage> result) {
        String key = requestKey(kind, parentId, query, offset, limit);
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

    interface TrackNetworkStart {
        BridgeClient.Cancellation start(BridgeClient.Result<BridgeClient.LibraryPage> result);
    }

    BridgeRepository.RequestHandle loadTracks(
            BridgeSession.Request request,
            String key,
            BridgeRepository.PageResult<BridgeClient.LibraryPage> result,
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
                                                         BridgeRepository.PageResult<T> result) {
        return pageNetworkResult(request, new AtomicBoolean(), result, null);
    }

    private BridgeClient.Result<BridgeClient.LibraryPage> cacheTracksResult(
            BridgeSession.Request request, String key, AtomicBoolean cancelled,
            BridgeRepository.PageResult<BridgeClient.LibraryPage> result) {
        return pageNetworkResult(request, cancelled, result, page ->
                libraryCache.saveTracks(BridgeSession.cacheScope(request.bridge), key, page));
    }

    private BridgeClient.Result<BridgeClient.LibraryCollectionPage> cacheCollectionsResult(
            BridgeSession.Request request, String key, AtomicBoolean cancelled,
            BridgeRepository.PageResult<BridgeClient.LibraryCollectionPage> result) {
        return pageNetworkResult(request, cancelled, result, page ->
                libraryCache.saveCollections(BridgeSession.cacheScope(request.bridge), key, page));
    }

    /** Delivers an authoritative page (optionally caching it) or a typed failure. */
    private <T> BridgeClient.Result<T> pageNetworkResult(
            BridgeSession.Request request, AtomicBoolean cancelled, BridgeRepository.PageResult<T> result,
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

    static String requestKey(String kind, String collectionId, String query,
                                            int offset, int limit) {
        return kind + "\u001f" + (collectionId == null ? "" : collectionId) + "\u001f"
                + (query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT))
                + "\u001f" + Math.max(0, offset) + "\u001f" + Math.max(1, limit);
    }

}
