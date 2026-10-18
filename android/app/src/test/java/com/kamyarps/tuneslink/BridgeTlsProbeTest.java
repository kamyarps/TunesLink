package com.kamyarps.tuneslink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/**
 * Runs the real probe and pinning code against a local TLS server with a throwaway self-signed
 * certificate. The probe must report the leaf fingerprint without completing a handshake that
 * trusts an unverified certificate, and pinned requests must reject any other identity.
 */
public final class BridgeTlsProbeTest {
    private static final String PASSWORD = "tuneslink-test";

    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    private SSLServerSocket server;
    private Thread acceptor;
    private String expectedFingerprint;

    @Before
    public void startServer() throws Exception {
        File keystore = new File(folder.getRoot(), "bridge.p12");
        File keytool = new File(System.getProperty("java.home"), "bin/keytool"
                + (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? ".exe" : ""));
        assumeTrue("keytool is required to create the test certificate", keytool.isFile());
        Process process = new ProcessBuilder(keytool.getPath(), "-genkeypair", "-alias", "bridge",
                "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=TunesLink Test",
                "-validity", "2", "-storetype", "PKCS12", "-keystore", keystore.getPath(),
                "-storepass", PASSWORD, "-keypass", PASSWORD)
                .redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());

        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = new FileInputStream(keystore)) {
            store.load(input, PASSWORD.toCharArray());
        }
        expectedFingerprint = sha256((X509Certificate) store.getCertificate("bridge"));
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, PASSWORD.toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        server = (SSLServerSocket) context.getServerSocketFactory()
                .createServerSocket(0, 8, InetAddress.getLoopbackAddress());
        acceptor = new Thread(this::serve, "tls-probe-test-server");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @After
    public void stopServer() throws Exception {
        if (server != null) server.close();
        if (acceptor != null) acceptor.join(5_000);
    }

    @Test
    public void probeReportsLeafFingerprintWithoutTrustingIt() throws Exception {
        assertEquals(expectedFingerprint,
                BridgeHttpClient.probeFingerprint("127.0.0.1", server.getLocalPort()));
    }

    @Test
    public void pinnedRequestSucceedsOnlyForProbedIdentity() throws Exception {
        BridgeHttpClient client = new BridgeHttpClient();
        String fingerprint = BridgeHttpClient.probeFingerprint("127.0.0.1", server.getLocalPort());
        BridgeHttpClient.Response response = client.request("127.0.0.1", server.getLocalPort(),
                fingerprint, null, "GET", "/api/info", null, null);
        assertEquals(200, response.status);
        assertEquals("{}", new String(response.body, StandardCharsets.UTF_8));

        String other = "00".repeat(32);
        assertNotEquals(fingerprint, other);
        try {
            new BridgeHttpClient().request("127.0.0.1", server.getLocalPort(), other, null,
                    "GET", "/api/info", null, null);
            fail("A connection pinned to another identity must fail");
        } catch (IOException expected) {
            // Expected: the pinned trust manager rejects the certificate.
        }
    }

    private void serve() {
        while (!server.isClosed()) {
            try (SSLSocket socket = (SSLSocket) server.accept()) {
                socket.setSoTimeout(5_000);
                try {
                    socket.startHandshake();
                } catch (IOException rejectedByClient) {
                    continue;
                }
                InputStream input = socket.getInputStream();
                StringBuilder head = new StringBuilder();
                int value;
                while ((value = input.read()) != -1) {
                    head.append((char) value);
                    if (head.toString().endsWith("\r\n\r\n")) break;
                }
                OutputStream output = socket.getOutputStream();
                output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                        + "Content-Length: 2\r\nConnection: close\r\n\r\n{}")
                        .getBytes(StandardCharsets.US_ASCII));
                output.flush();
            } catch (IOException closed) {
                // The server was closed or a client abandoned its connection.
            }
        }
    }

    private static String sha256(X509Certificate certificate) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        StringBuilder value = new StringBuilder(64);
        for (byte item : digest) value.append(String.format(Locale.ROOT, "%02X", item & 0xff));
        return value.toString();
    }
}
