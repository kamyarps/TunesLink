package com.kamyarps.tuneslink;

/** Keeps one in-flight pairing request attached to its latest observer. */
final class PendingPairing {
    enum BeginResult { START, ATTACHED, BUSY }

    static final class Begin {
        final BeginResult result;
        final long generation;

        Begin(BeginResult result, long generation) {
            this.result = result;
            this.generation = generation;
        }
    }

    private BridgeClient.BridgeInfo bridge;
    private BridgeClient.Result<SecureStore.SavedBridge> observer;
    private boolean inProgress;
    private long generation;

    synchronized BeginResult begin(BridgeClient.BridgeInfo candidate,
                                   BridgeClient.Result<SecureStore.SavedBridge> candidateObserver) {
        return beginOperation(candidate, candidateObserver).result;
    }

    synchronized Begin beginOperation(BridgeClient.BridgeInfo candidate,
                                      BridgeClient.Result<SecureStore.SavedBridge> candidateObserver) {
        if (inProgress) {
            if (!sameBridge(bridge, candidate)) return new Begin(BeginResult.BUSY, generation);
            observer = candidateObserver;
            return new Begin(BeginResult.ATTACHED, generation);
        }
        bridge = candidate;
        observer = candidateObserver;
        inProgress = true;
        generation++;
        return new Begin(BeginResult.START, generation);
    }

    synchronized BridgeClient.Result<SecureStore.SavedBridge> finish() {
        return finish(generation);
    }

    synchronized BridgeClient.Result<SecureStore.SavedBridge> finish(long candidate) {
        if (!inProgress || candidate != generation) return null;
        inProgress = false;
        bridge = null;
        BridgeClient.Result<SecureStore.SavedBridge> completedObserver = observer;
        observer = null;
        return completedObserver;
    }

    synchronized void cancel() {
        generation++;
        inProgress = false;
        bridge = null;
        observer = null;
    }

    synchronized boolean isCurrent(long candidate) {
        return inProgress && candidate == generation;
    }

    private static boolean sameBridge(BridgeClient.BridgeInfo left,
                                      BridgeClient.BridgeInfo right) {
        return left != null && right != null && left.id.equals(right.id)
                && left.host.equals(right.host) && left.port == right.port
                && left.tlsFingerprint.equals(right.tlsFingerprint);
    }
}
