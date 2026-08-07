package com.kamyarps.tuneslink;

import java.util.List;

/** Keeps discovery attached to the currently visible Activity without starting duplicate scans. */
final class PendingDiscovery {
    static final class Begin {
        final boolean start;
        final long generation;

        Begin(boolean start, long generation) {
            this.start = start;
            this.generation = generation;
        }
    }

    private BridgeClient.Result<List<BridgeClient.BridgeInfo>> observer;
    private boolean inProgress;
    private long generation;

    synchronized boolean begin(
            BridgeClient.Result<List<BridgeClient.BridgeInfo>> candidateObserver) {
        return beginOperation(candidateObserver).start;
    }

    synchronized Begin beginOperation(
            BridgeClient.Result<List<BridgeClient.BridgeInfo>> candidateObserver) {
        observer = candidateObserver;
        if (inProgress) return new Begin(false, generation);
        inProgress = true;
        generation++;
        return new Begin(true, generation);
    }

    synchronized BridgeClient.Result<List<BridgeClient.BridgeInfo>> finish() {
        return finish(generation);
    }

    synchronized BridgeClient.Result<List<BridgeClient.BridgeInfo>> finish(long candidate) {
        if (!inProgress || candidate != generation) return null;
        inProgress = false;
        BridgeClient.Result<List<BridgeClient.BridgeInfo>> completedObserver = observer;
        observer = null;
        return completedObserver;
    }

    synchronized void cancel() {
        generation++;
        inProgress = false;
        observer = null;
    }

    synchronized boolean isCurrent(long candidate) {
        return inProgress && candidate == generation;
    }
}
