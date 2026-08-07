package com.kamyarps.tuneslink;

final class BridgeSession {
    static final class Request {
        final SecureStore.SavedBridge bridge;
        private final int generation;

        private Request(SecureStore.SavedBridge bridge, int generation) {
            this.bridge = bridge;
            this.generation = generation;
        }
    }

    private SecureStore.SavedBridge current;
    private int generation;

    void activate(SecureStore.SavedBridge bridge) {
        current = bridge;
        generation++;
    }

    void clear() {
        current = null;
        generation++;
    }

    Request capture() {
        return current == null ? null : new Request(current, generation);
    }

    boolean isCurrent(Request request) {
        return request != null
                && request.generation == generation
                && request.bridge == current;
    }

    static String artworkCacheKey(SecureStore.SavedBridge bridge, String artworkId, int size) {
        return cacheScope(bridge) + ":" + artworkId + ":" + size;
    }

    static String cacheScope(SecureStore.SavedBridge bridge) {
        return bridge.id + ":" + bridge.tlsFingerprint;
    }
}
