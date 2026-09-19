package com.kamyarps.tuneslink;

import static org.junit.Assert.*;

import org.junit.Test;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PairAgainTest {
    private static final String FINGERPRINT = "AA".repeat(32);
    private static final String TOKEN = "revoked-token-" + "a".repeat(40);

    @Test
    public void reachableRevokedPairingOpensCodeEntryWithoutDiscoveryOrCredentialChange() throws Exception {
        SecureStore store = store();
        FakeClient client = new FakeClient();
        try (BridgeRepository repository = new BridgeRepository(client, store)) {
            Outcome outcome = new Outcome();
            repository.resolvePairingEndpoint(outcome);
            assertEquals("192.168.1.20:45832", client.address);
            client.manual.success(info("192.168.1.20", FINGERPRINT));
            assertNotNull(outcome.bridge);
            assertNull(client.discovery);
            assertEquals(TOKEN, store.load().token);
        }
    }

    @Test
    public void changedAddressRequiresPinnedVerificationBeforeCodeEntry() throws Exception {
        FakeClient client = new FakeClient();
        try (BridgeRepository repository = new BridgeRepository(client, store())) {
            Outcome outcome = new Outcome();
            repository.resolvePairingEndpoint(outcome);
            client.manual.failure("Old address unavailable", false);
            BridgeClient.BridgeInfo candidate = info("192.168.1.21", FINGERPRINT);
            client.discovery.success(List.of(info("192.168.1.22", "BB".repeat(32)), candidate));
            assertSame(candidate, client.verifying);
            assertNull(outcome.bridge);
            client.verified.success(candidate);
            assertSame(candidate, outcome.bridge);
            assertEquals("192.168.1.20", repository.current().host);
        }
    }

    @Test
    public void cancelledRecoveryIgnoresLateIdentityResults() throws Exception {
        FakeClient client = new FakeClient();
        try (BridgeRepository repository = new BridgeRepository(client, store())) {
            Outcome outcome = new Outcome();
            repository.resolvePairingEndpoint(outcome).cancel();
            client.manual.success(info("192.168.1.20", FINGERPRINT));
            client.manual.failure("Late failure", false);
            assertNull(outcome.bridge);
            assertNull(client.discovery);
            assertEquals(0, outcome.failures);
        }
    }

    private static BridgeClient.BridgeInfo info(String host, String fingerprint) {
        return new BridgeClient.BridgeInfo("saved-id", "PC", host, 45832, fingerprint);
    }

    private static SecureStore store() throws Exception {
        SecureStore store = new SecureStore(new SecureStore.Backend() {
            private final Map<String, Object> values = new HashMap<>();
            @Override public String getString(String key, String fallback) {
                return values.get(key) instanceof String value ? value : fallback;
            }
            @Override public int getInt(String key, int fallback) {
                return values.get(key) instanceof Integer value ? value : fallback;
            }
            @Override public boolean commit(Map<String, Object> additions, Set<String> removals) {
                removals.forEach(values::remove);
                values.putAll(additions);
                return true;
            }
        }, new SecureStore.Crypto() {
            @Override public SecureStore.Envelope encrypt(byte[] plaintext) {
                return new SecureStore.Envelope(plaintext.clone(), new byte[] { 1 });
            }
            @Override public byte[] decrypt(byte[] ciphertext, byte[] iv) { return ciphertext.clone(); }
        });
        store.save(info("192.168.1.20", FINGERPRINT), TOKEN);
        return store;
    }

    private static final class Outcome implements BridgeClient.Result<BridgeClient.BridgeInfo> {
        BridgeClient.BridgeInfo bridge;
        int failures;
        @Override public void success(BridgeClient.BridgeInfo value) { bridge = value; }
        @Override public void failure(String message, boolean unauthorized) { failures++; }
    }

    private static final class FakeClient extends BridgeClient {
        String address;
        Result<BridgeInfo> manual;
        Result<List<BridgeInfo>> discovery;
        Result<BridgeInfo> verified;
        BridgeInfo verifying;
        @Override Cancellation resolveManual(String input, Result<BridgeInfo> result) {
            address = input; manual = result; return Cancellation.NONE;
        }
        @Override Cancellation discover(Result<List<BridgeInfo>> result) {
            discovery = result; return Cancellation.NONE;
        }
        @Override Cancellation verifyIdentity(BridgeInfo candidate, String id, String fingerprint,
                                               Result<BridgeInfo> result) {
            assertEquals(candidate.id, id);
            assertEquals(candidate.tlsFingerprint, fingerprint);
            verifying = candidate; verified = result; return Cancellation.NONE;
        }
    }
}
