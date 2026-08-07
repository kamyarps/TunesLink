package com.kamyarps.tuneslink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public final class PendingPairingTest {
    private static final String FINGERPRINT =
            "00112233445566778899AABBCCDDEEFF00112233445566778899AABBCCDDEEFF";

    @Test
    public void rotationReplacesObserverWithoutStartingAnotherPairRequest() {
        PendingPairing pairing = new PendingPairing();
        BridgeClient.BridgeInfo bridge = bridge("bridge-1");
        RecordingResult firstActivity = new RecordingResult();
        RecordingResult rotatedActivity = new RecordingResult();

        assertEquals(PendingPairing.BeginResult.START, pairing.begin(bridge, firstActivity));
        assertEquals(PendingPairing.BeginResult.ATTACHED,
                pairing.begin(bridge, rotatedActivity));
        assertSame(rotatedActivity, pairing.finish());
    }

    @Test
    public void observerCannotAttachToAnotherBridge() {
        PendingPairing pairing = new PendingPairing();
        assertEquals(PendingPairing.BeginResult.START,
                pairing.begin(bridge("bridge-1"), new RecordingResult()));
        assertEquals(PendingPairing.BeginResult.BUSY,
                pairing.begin(bridge("bridge-2"), new RecordingResult()));
    }

    @Test
    public void cancelledPairingRejectsLateCompletion() {
        PendingPairing pairing = new PendingPairing();
        PendingPairing.Begin begin = pairing.beginOperation(
                bridge("bridge-1"), new RecordingResult());

        pairing.cancel();

        assertFalse(pairing.isCurrent(begin.generation));
        assertSame(null, pairing.finish(begin.generation));
    }

    private static BridgeClient.BridgeInfo bridge(String id) {
        return new BridgeClient.BridgeInfo(id, "Laptop", "192.168.1.10", 45832,
                FINGERPRINT);
    }

    private static final class RecordingResult
            implements BridgeClient.Result<SecureStore.SavedBridge> {
        @Override public void success(SecureStore.SavedBridge value) { }
        @Override public void failure(String message, boolean unauthorized) { }
    }
}
