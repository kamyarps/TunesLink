package com.kamyarps.tuneslink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class SecureStoreTest {
    private static final String FINGERPRINT =
            "00112233445566778899AABBCCDDEEFF00112233445566778899AABBCCDDEEFF";
    private static final String TOKEN = "token-" + "a".repeat(40);

    @Test
    public void activePairingRoundTripsInsideVersionedEncryptedRecord() throws Exception {
        MemoryBackend backend = new MemoryBackend();
        SecureStore store = new SecureStore(backend, new PassthroughCrypto());

        store.save(info("bridge-1", "192.168.1.10"), TOKEN);

        SecureStore.SavedBridge saved = store.load();
        assertEquals("bridge-1", saved.id);
        assertEquals("192.168.1.10", saved.host);
        assertEquals(TOKEN, saved.token);
        assertEquals(2, backend.getInt("secureRecordVersion", 0));
        assertTrue(backend.contains("secureRecord"));
    }

    @Test
    public void legacyTokenMigratesOnlyAfterNewRecordCommits() {
        MemoryBackend backend = legacyBackend();
        SecureStore store = new SecureStore(backend, new PassthroughCrypto());

        SecureStore.SavedBridge saved = store.load();

        assertEquals("legacy-bridge", saved.id);
        assertEquals(TOKEN, saved.token);
        assertTrue(backend.contains("secureRecord"));
        assertFalse(backend.contains("token"));
        assertFalse(backend.contains("iv"));
    }

    @Test
    public void failedLegacyMigrationKeepsLegacyPairingReadable() {
        MemoryBackend backend = legacyBackend();
        backend.failNextCommit = true;
        SecureStore store = new SecureStore(backend, new PassthroughCrypto());

        assertNull(store.load());

        assertTrue(backend.contains("token"));
        assertFalse(backend.contains("secureRecord"));
    }

    @Test
    public void forgetAtomicallyMovesCredentialToPendingQueue() throws Exception {
        SecureStore store = new SecureStore(new MemoryBackend(), new PassthroughCrypto());
        store.save(info("bridge-1", "192.168.1.10"), TOKEN);

        SecureStore.PendingRevocation pending = store.prepareForget();

        assertNull(store.load());
        assertEquals(1, store.pendingRevocationCount());
        assertEquals(TOKEN, pending.bridge.token);
        assertTrue(store.removePendingRevocation(pending.revocationId));
        assertEquals(0, store.pendingRevocationCount());
    }

    @Test
    public void pairingAnotherComputerQueuesThePreviousAuthorization() throws Exception {
        SecureStore store = new SecureStore(new MemoryBackend(), new PassthroughCrypto());
        store.save(info("bridge-old", "192.168.1.10"), TOKEN);

        store.save(info("bridge-new", "192.168.1.20"), TOKEN + "new");

        assertEquals("bridge-new", store.load().id);
        assertEquals(1, store.pendingRevocationCount());
        assertEquals("bridge-old", store.pendingRevocations().get(0).bridge.id);
        assertEquals(TOKEN, store.pendingRevocations().get(0).bridge.token);
    }

    @Test
    public void repairingSameComputerDoesNotQueueAnAlreadyInvalidToken() throws Exception {
        SecureStore store = new SecureStore(new MemoryBackend(), new PassthroughCrypto());
        store.save(info("bridge-1", "192.168.1.10"), TOKEN);

        store.save(info("bridge-1", "192.168.1.44"), TOKEN + "replacement");

        assertEquals(TOKEN + "replacement", store.load().token);
        assertEquals(0, store.pendingRevocationCount());
    }

    @Test
    public void fullQueueRejectsForgetWithoutClearingActivePairing() throws Exception {
        SecureStore store = new SecureStore(new MemoryBackend(), new PassthroughCrypto());
        for (int index = 0; index < SecureStore.MAX_PENDING_REVOCATIONS; index++) {
            store.save(info("bridge-" + index, "192.168.1." + (10 + index)),
                    TOKEN + index);
            store.prepareForget();
        }
        store.save(info("bridge-current", "192.168.1.99"), TOKEN + "current");

        assertThrows(IllegalStateException.class, store::prepareForget);

        assertEquals("bridge-current", store.load().id);
        assertEquals(SecureStore.MAX_PENDING_REVOCATIONS, store.pendingRevocationCount());
    }

    @Test
    public void failedWritePreservesPreviouslyCommittedPairing() throws Exception {
        MemoryBackend backend = new MemoryBackend();
        SecureStore store = new SecureStore(backend, new PassthroughCrypto());
        store.save(info("bridge-old", "192.168.1.10"), TOKEN);
        backend.failNextCommit = true;

        assertThrows(IllegalStateException.class,
                () -> store.save(info("bridge-new", "192.168.1.20"), TOKEN + "new"));

        assertEquals("bridge-old", store.load().id);
    }

    @Test
    public void endpointRelocationPreservesCredentialAndRejectsNewIdentity() throws Exception {
        SecureStore store = new SecureStore(new MemoryBackend(), new PassthroughCrypto());
        store.save(info("bridge-1", "192.168.1.10"), TOKEN);
        SecureStore.SavedBridge old = store.load();

        SecureStore.SavedBridge moved = store.updateEndpoint(old,
                info("bridge-1", "192.168.1.44"));

        assertEquals("192.168.1.44", moved.host);
        assertEquals(TOKEN, moved.token);
        BridgeClient.BridgeInfo impostor = new BridgeClient.BridgeInfo(
                "bridge-1", "Impostor", "192.168.1.55", 45832, "AA".repeat(32));
        assertThrows(IllegalArgumentException.class,
                () -> store.updateEndpoint(moved, impostor));
        assertEquals("192.168.1.44", store.load().host);
    }

    @Test
    public void corruptEncryptedRecordIsRetainedAndCannotBeSilentlyOverwritten() {
        MemoryBackend backend = new MemoryBackend();
        backend.values.put("secureRecordVersion", 2);
        backend.values.put("secureRecord", Base64.getEncoder().encodeToString(new byte[]{1, 2}));
        backend.values.put("secureRecordIv", Base64.getEncoder().encodeToString(new byte[]{1}));
        SecureStore store = new SecureStore(backend, new PassthroughCrypto());

        assertNull(store.load());
        assertThrows(Exception.class,
                () -> store.save(info("bridge-1", "192.168.1.10"), TOKEN));
        assertTrue(backend.contains("secureRecord"));
        assertTrue(store.requiresRecovery());
    }

    private static BridgeClient.BridgeInfo info(String id, String host) {
        return new BridgeClient.BridgeInfo(id, "Laptop", host, 45832, FINGERPRINT);
    }

    private static MemoryBackend legacyBackend() {
        MemoryBackend backend = new MemoryBackend();
        backend.values.put("id", "legacy-bridge");
        backend.values.put("name", "Legacy Laptop");
        backend.values.put("host", "192.168.1.12");
        backend.values.put("port", 45832);
        backend.values.put("tlsFingerprint", FINGERPRINT);
        backend.values.put("token", Base64.getEncoder().encodeToString(
                TOKEN.getBytes(StandardCharsets.UTF_8)));
        backend.values.put("iv", Base64.getEncoder().encodeToString(new byte[]{1}));
        return backend;
    }

    private static final class PassthroughCrypto implements SecureStore.Crypto {
        @Override public SecureStore.Envelope encrypt(byte[] plaintext) {
            return new SecureStore.Envelope(plaintext.clone(), new byte[]{1});
        }

        @Override public byte[] decrypt(byte[] ciphertext, byte[] iv) {
            return ciphertext.clone();
        }
    }

    private static final class MemoryBackend implements SecureStore.Backend {
        final Map<String, Object> values = new HashMap<>();
        boolean failNextCommit;

        @Override public String getString(String key, String fallback) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : fallback;
        }

        @Override public int getInt(String key, int fallback) {
            Object value = values.get(key);
            return value instanceof Integer ? (Integer) value : fallback;
        }

        boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override public boolean commit(Map<String, Object> additions, Set<String> removals) {
            if (failNextCommit) {
                failNextCommit = false;
                return false;
            }
            Map<String, Object> next = new HashMap<>(values);
            for (String key : removals) next.remove(key);
            next.putAll(additions);
            values.clear();
            values.putAll(next);
            return true;
        }
    }
}
