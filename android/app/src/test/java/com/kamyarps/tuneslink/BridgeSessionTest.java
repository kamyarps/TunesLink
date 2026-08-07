package com.kamyarps.tuneslink;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class BridgeSessionTest {
    private static final String FINGERPRINT_A =
            "00112233445566778899AABBCCDDEEFF00112233445566778899AABBCCDDEEFF";
    private static final String FINGERPRINT_B =
            "FFEEDDCCBBAA99887766554433221100FFEEDDCCBBAA99887766554433221100";

    @Test
    public void oldRequestBecomesInvalidAfterBridgeReplacement() {
        BridgeSession session = new BridgeSession();
        SecureStore.SavedBridge first = bridge("bridge-a", FINGERPRINT_A, "token-a");
        SecureStore.SavedBridge replacement = bridge("bridge-b", FINGERPRINT_B, "token-b");
        session.activate(first);
        BridgeSession.Request oldRequest = session.capture();

        session.activate(replacement);

        assertFalse(session.isCurrent(oldRequest));
        assertTrue(session.isCurrent(session.capture()));
    }

    @Test
    public void oldRequestBecomesInvalidAfterClearAndRepair() {
        BridgeSession session = new BridgeSession();
        SecureStore.SavedBridge bridge = bridge("bridge-a", FINGERPRINT_A, "token-a");
        session.activate(bridge);
        BridgeSession.Request oldRequest = session.capture();

        session.clear();
        session.activate(bridge("bridge-a", FINGERPRINT_A, "token-new"));

        assertFalse(session.isCurrent(oldRequest));
    }

    @Test
    public void artworkCacheKeyIsScopedToBridgeIdentity() {
        SecureStore.SavedBridge first = bridge("bridge-a", FINGERPRINT_A, "token-a");
        SecureStore.SavedBridge second = bridge("bridge-b", FINGERPRINT_B, "token-b");

        assertNotEquals(
                BridgeSession.artworkCacheKey(first, "track-1", 180),
                BridgeSession.artworkCacheKey(second, "track-1", 180));
    }

    private static SecureStore.SavedBridge bridge(String id, String fingerprint, String token) {
        return new SecureStore.SavedBridge(id, id, "192.168.1.10", 45832, fingerprint, token);
    }
}
