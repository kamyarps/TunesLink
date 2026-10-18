package com.kamyarps.tuneslink;

/**
 * Stand-in for the Google Play reviewer demo, used by every build except the Play bundle.
 * ENABLED is a compile-time constant, so the guarded demo branches are removed at compile time.
 */
final class DemoBridge {
    static final boolean ENABLED = false;

    private DemoBridge() {
    }

    static boolean isDemoAddress(String input) {
        return false;
    }

    static boolean isDemoHost(String host) {
        return false;
    }

    static BridgeClient.BridgeInfo info() {
        throw new UnsupportedOperationException();
    }

    static BridgeHttpClient.Response respond(String method, String target, byte[] body,
                                             String token) {
        throw new UnsupportedOperationException();
    }
}
