package com.kamyarps.tuneslink;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypted, transactional persistence for the active bridge and deferred revocations. */
final class SecureStore {
    private static final String KEY_ALIAS = "TunesLink_bridge_token_v1";
    private static final String PREFS = "TunesLink_paired_bridge";
    private static final String CLIENT_ID = "clientId";
    private static final String RECORD = "secureRecord";
    private static final String RECORD_IV = "secureRecordIv";
    private static final String RECORD_VERSION = "secureRecordVersion";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int MAGIC = 0x544c5332; // TLS2
    private static final int SCHEMA_VERSION = 2;
    static final int MAX_PENDING_REVOCATIONS = 8;

    private static final Set<String> LEGACY_KEYS = Set.of(
            "id", "name", "host", "port", "tlsFingerprint", "token", "iv");

    static final class SavedBridge {
        final String id;
        final String name;
        final String host;
        final int port;
        final String tlsFingerprint;
        final String token;

        SavedBridge(String id, String name, String host, int port, String tlsFingerprint,
                    String token) {
            this.id = id;
            this.name = name;
            this.host = host;
            this.port = port;
            this.tlsFingerprint = BridgeClient.normalizeFingerprint(tlsFingerprint);
            this.token = token;
        }
    }

    static final class PendingRevocation {
        final String revocationId;
        final SavedBridge bridge;
        final long queuedAtMillis;

        PendingRevocation(String revocationId, SavedBridge bridge, long queuedAtMillis) {
            this.revocationId = revocationId;
            this.bridge = bridge;
            this.queuedAtMillis = queuedAtMillis;
        }
    }

    static final class Envelope {
        final byte[] ciphertext;
        final byte[] iv;

        Envelope(byte[] ciphertext, byte[] iv) {
            this.ciphertext = ciphertext;
            this.iv = iv;
        }
    }

    interface Crypto {
        Envelope encrypt(byte[] plaintext) throws Exception;
        byte[] decrypt(byte[] ciphertext, byte[] iv) throws Exception;
    }

    interface Backend {
        String getString(String key, String fallback);
        int getInt(String key, int fallback);
        boolean commit(Map<String, Object> values, Set<String> removals);
    }

    private static final class State {
        final SavedBridge active;
        final List<PendingRevocation> pending;

        State(SavedBridge active, List<PendingRevocation> pending) {
            this.active = active;
            this.pending = List.copyOf(pending);
        }
    }

    private final Backend backend;
    private final Crypto crypto;
    private boolean unreadableRecord;

    SecureStore(Context context) {
        this(new SharedPreferencesBackend(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)), new KeyStoreCrypto());
    }

    SecureStore(Backend backend, Crypto crypto) {
        this.backend = backend;
        this.crypto = crypto;
    }

    synchronized String clientId() {
        String existing = backend.getString(CLIENT_ID, "");
        if (BridgeClient.isValidClientId(existing)) return existing;
        String generated = UUID.randomUUID().toString();
        if (!backend.commit(Map.of(CLIENT_ID, generated), Collections.emptySet())) {
            throw new IllegalStateException("Could not commit phone identity");
        }
        return generated;
    }

    synchronized void save(BridgeClient.BridgeInfo bridge, String token) throws Exception {
        SavedBridge saved = validated(new SavedBridge(bridge.id, bridge.name, bridge.host,
                bridge.port, bridge.tlsFingerprint, token));
        State old = readStateForMutation();
        ArrayList<PendingRevocation> pending = new ArrayList<>(old.pending);
        if (old.active != null && !sameBridgeIdentity(old.active, saved)) {
            if (pending.size() >= MAX_PENDING_REVOCATIONS) {
                throw new IllegalStateException(
                        "Pending revocation queue is full. Retry an earlier removal first.");
            }
            pending.add(new PendingRevocation(UUID.randomUUID().toString(), old.active,
                    System.currentTimeMillis()));
        }
        writeState(new State(saved, pending), true);
    }

    synchronized SavedBridge load() {
        try {
            SavedBridge active = readState(true).active;
            unreadableRecord = false;
            return active;
        } catch (Exception invalidatedOrCorrupt) {
            // Keep an unreadable v2 record intact. It may contain revocation credentials and must
            // never be silently overwritten or discarded.
            unreadableRecord = true;
            return null;
        }
    }

    synchronized SavedBridge updateEndpoint(SavedBridge expected,
                                              BridgeClient.BridgeInfo verified) throws Exception {
        State old = readStateForMutation();
        SavedBridge active = old.active;
        if (active == null || !sameCredential(active, expected)) {
            throw new IllegalStateException("The saved pairing changed during relocation");
        }
        if (!active.id.equals(verified.id)
                || !active.tlsFingerprint.equals(verified.tlsFingerprint)) {
            throw new IllegalArgumentException("Bridge identity changed during relocation");
        }
        SavedBridge relocated = validated(new SavedBridge(active.id, verified.name, verified.host,
                verified.port, active.tlsFingerprint, active.token));
        writeState(new State(relocated, old.pending), true);
        return relocated;
    }

    synchronized PendingRevocation prepareForget() throws Exception {
        State old = readStateForMutation();
        if (old.active == null) return null;
        if (old.pending.size() >= MAX_PENDING_REVOCATIONS) {
            throw new IllegalStateException(
                    "Pending revocation queue is full. Retry an earlier removal first.");
        }
        PendingRevocation queued = new PendingRevocation(UUID.randomUUID().toString(),
                old.active, System.currentTimeMillis());
        ArrayList<PendingRevocation> pending = new ArrayList<>(old.pending);
        pending.add(queued);
        writeState(new State(null, pending), true);
        return queued;
    }

    synchronized List<PendingRevocation> pendingRevocations() {
        try {
            List<PendingRevocation> pending = readState(true).pending;
            unreadableRecord = false;
            return pending;
        } catch (Exception invalidatedOrCorrupt) {
            unreadableRecord = true;
            return Collections.emptyList();
        }
    }

    synchronized boolean requiresRecovery() {
        return unreadableRecord;
    }

    synchronized boolean removePendingRevocation(String revocationId) throws Exception {
        State old = readStateForMutation();
        ArrayList<PendingRevocation> retained = new ArrayList<>();
        boolean removed = false;
        for (PendingRevocation item : old.pending) {
            if (item.revocationId.equals(revocationId)) removed = true;
            else retained.add(item);
        }
        if (removed) writeState(new State(old.active, retained), true);
        return removed;
    }

    synchronized int pendingRevocationCount() {
        return pendingRevocations().size();
    }

    private State readStateForMutation() throws Exception {
        return readState(true);
    }

    private State readState(boolean migrateLegacy) throws Exception {
        String encrypted = backend.getString(RECORD, null);
        String iv = backend.getString(RECORD_IV, null);
        if (encrypted != null || iv != null) {
            if (encrypted == null || iv == null
                    || backend.getInt(RECORD_VERSION, 0) != SCHEMA_VERSION) {
                throw new IllegalStateException("Secure pairing record is incomplete");
            }
            byte[] plaintext = crypto.decrypt(Base64.getDecoder().decode(encrypted),
                    Base64.getDecoder().decode(iv));
            return decode(plaintext);
        }
        SavedBridge legacy = loadLegacy();
        State state = new State(legacy, Collections.emptyList());
        if (legacy != null && migrateLegacy) writeState(state, true);
        return state;
    }

    private SavedBridge loadLegacy() {
        String encrypted = backend.getString("token", null);
        String iv = backend.getString("iv", null);
        if (encrypted == null || iv == null) return null;
        try {
            String token = new String(crypto.decrypt(Base64.getDecoder().decode(encrypted),
                    Base64.getDecoder().decode(iv)), StandardCharsets.UTF_8);
            return validated(new SavedBridge(
                    backend.getString("id", ""),
                    backend.getString("name", "My computer"),
                    backend.getString("host", ""),
                    backend.getInt("port", BridgeClient.DEFAULT_PORT),
                    backend.getString("tlsFingerprint", ""), token));
        } catch (Exception invalidLegacy) {
            return null;
        }
    }

    private void writeState(State state, boolean removeLegacy) throws Exception {
        byte[] plaintext = encode(state);
        Envelope envelope = crypto.encrypt(plaintext);
        Map<String, Object> values = new HashMap<>();
        values.put(RECORD_VERSION, SCHEMA_VERSION);
        values.put(RECORD, Base64.getEncoder().encodeToString(envelope.ciphertext));
        values.put(RECORD_IV, Base64.getEncoder().encodeToString(envelope.iv));
        Set<String> removals = removeLegacy ? LEGACY_KEYS : Collections.emptySet();
        if (!backend.commit(values, removals)) {
            throw new IllegalStateException("Could not commit secure pairing");
        }
    }

    private static byte[] encode(State state) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(SCHEMA_VERSION);
            output.writeBoolean(state.active != null);
            if (state.active != null) writeBridge(output, state.active);
            output.writeInt(state.pending.size());
            for (PendingRevocation item : state.pending) {
                output.writeUTF(item.revocationId);
                output.writeLong(item.queuedAtMillis);
                writeBridge(output, item.bridge);
            }
        }
        return bytes.toByteArray();
    }

    private static State decode(byte[] bytes) throws Exception {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC || input.readInt() != SCHEMA_VERSION) {
                throw new IllegalStateException("Unsupported secure pairing schema");
            }
            SavedBridge active = input.readBoolean() ? readBridge(input) : null;
            int count = input.readInt();
            if (count < 0 || count > MAX_PENDING_REVOCATIONS) {
                throw new IllegalStateException("Invalid pending revocation count");
            }
            ArrayList<PendingRevocation> pending = new ArrayList<>(count);
            HashSet<String> ids = new HashSet<>();
            for (int index = 0; index < count; index++) {
                String id = input.readUTF();
                long queuedAt = input.readLong();
                if (!BridgeClient.isValidClientId(id) || !ids.add(id)) {
                    throw new IllegalStateException("Invalid pending revocation identity");
                }
                pending.add(new PendingRevocation(id, readBridge(input), queuedAt));
            }
            if (input.available() != 0) throw new IllegalStateException("Trailing secure data");
            return new State(active, pending);
        }
    }

    private static void writeBridge(DataOutputStream output, SavedBridge bridge) throws Exception {
        output.writeUTF(bridge.id);
        output.writeUTF(bridge.name);
        output.writeUTF(bridge.host);
        output.writeInt(bridge.port);
        output.writeUTF(bridge.tlsFingerprint);
        output.writeUTF(bridge.token);
    }

    private static SavedBridge readBridge(DataInputStream input) throws Exception {
        return validated(new SavedBridge(input.readUTF(), input.readUTF(), input.readUTF(),
                input.readInt(), input.readUTF(), input.readUTF()));
    }

    private static SavedBridge validated(SavedBridge bridge) {
        if (!BridgeClient.isValidBridgeId(bridge.id)
                || bridge.host == null || bridge.host.isBlank() || bridge.host.length() > 255
                || !BridgeClient.isValidPort(bridge.port)
                || !BridgeClient.isValidFingerprint(bridge.tlsFingerprint)
                || bridge.token == null || bridge.token.length() < 32
                || bridge.token.length() > 4096) {
            throw new IllegalStateException("Invalid saved bridge credentials");
        }
        return new SavedBridge(bridge.id, BridgeClient.safeBridgeName(bridge.name), bridge.host,
                bridge.port, bridge.tlsFingerprint, bridge.token);
    }

    private static boolean sameCredential(SavedBridge left, SavedBridge right) {
        return left != null && right != null && left.id.equals(right.id)
                && left.tlsFingerprint.equals(right.tlsFingerprint)
                && left.token.equals(right.token);
    }

    private static boolean sameBridgeIdentity(SavedBridge left, SavedBridge right) {
        return left != null && right != null && left.id.equals(right.id)
                && left.tlsFingerprint.equals(right.tlsFingerprint);
    }

    private static final class SharedPreferencesBackend implements Backend {
        private final SharedPreferences preferences;

        SharedPreferencesBackend(SharedPreferences preferences) {
            this.preferences = preferences;
        }

        @Override public String getString(String key, String fallback) {
            return preferences.getString(key, fallback);
        }

        @Override public int getInt(String key, int fallback) {
            return preferences.getInt(key, fallback);
        }

        @Override public boolean commit(Map<String, Object> values, Set<String> removals) {
            SharedPreferences.Editor editor = preferences.edit();
            for (String key : removals) editor.remove(key);
            for (Map.Entry<String, Object> item : values.entrySet()) {
                if (item.getValue() instanceof String text) editor.putString(item.getKey(), text);
                else if (item.getValue() instanceof Integer number)
                    editor.putInt(item.getKey(), number);
                else throw new IllegalArgumentException("Unsupported preference type");
            }
            return editor.commit();
        }
    }

    private static final class KeyStoreCrypto implements Crypto {
        @Override public Envelope encrypt(byte[] plaintext) throws Exception {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            return new Envelope(cipher.doFinal(plaintext), cipher.getIV());
        }

        @Override public byte[] decrypt(byte[] ciphertext, byte[] iv) throws Exception {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            return cipher.doFinal(ciphertext);
        }

        private SecretKey getOrCreateKey() throws Exception {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
            if (entry instanceof KeyStore.SecretKeyEntry secretEntry) {
                return secretEntry.getSecretKey();
            }
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                    "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            return generator.generateKey();
        }
    }
}
