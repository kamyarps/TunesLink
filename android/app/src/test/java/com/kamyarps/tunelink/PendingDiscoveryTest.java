package com.kamyarps.tuneslink;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public final class PendingDiscoveryTest {
    @Test
    public void recreatedObserverReplacesTheDestroyedActivityObserver() {
        PendingDiscovery pending = new PendingDiscovery();
        BridgeClient.Result<List<BridgeClient.BridgeInfo>> original = observer();
        BridgeClient.Result<List<BridgeClient.BridgeInfo>> recreated = observer();

        assertTrue(pending.begin(original));
        assertFalse(pending.begin(recreated));
        assertSame(recreated, pending.finish());
    }

    @Test
    public void completedDiscoveryAllowsAnotherScan() {
        PendingDiscovery pending = new PendingDiscovery();
        BridgeClient.Result<List<BridgeClient.BridgeInfo>> first = observer();
        BridgeClient.Result<List<BridgeClient.BridgeInfo>> second = observer();

        assertTrue(pending.begin(first));
        assertSame(first, pending.finish());
        assertTrue(pending.begin(second));
    }

    @Test
    public void cancelledGenerationCannotDeliverLateResult() {
        PendingDiscovery pending = new PendingDiscovery();
        PendingDiscovery.Begin begin = pending.beginOperation(observer());

        pending.cancel();

        assertFalse(pending.isCurrent(begin.generation));
        assertSame(null, pending.finish(begin.generation));
        assertTrue(pending.begin(observer()));
    }

    private static BridgeClient.Result<List<BridgeClient.BridgeInfo>> observer() {
        return new BridgeClient.Result<>() {
            @Override public void success(List<BridgeClient.BridgeInfo> value) { }
            @Override public void failure(String message, boolean unauthorized) { }
        };
    }
}
