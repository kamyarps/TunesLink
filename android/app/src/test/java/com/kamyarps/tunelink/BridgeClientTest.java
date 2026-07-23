package com.kamyarps.tuneslink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import java.net.InetAddress;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URL;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;

public final class BridgeClientTest {
    private static final String FINGERPRINT =
            "00112233445566778899AABBCCDDEEFF00112233445566778899AABBCCDDEEFF";

    @Test
    public void fingerprintValidationRejectsMalformedValues() {
        assertTrue(BridgeClient.isValidFingerprint(FINGERPRINT));
        assertTrue(BridgeClient.isValidFingerprint(FINGERPRINT.toLowerCase()));
        assertFalse(BridgeClient.isValidFingerprint("001122"));
        assertFalse(BridgeClient.isValidFingerprint("Z".repeat(64)));
        assertFalse(BridgeClient.isValidFingerprint(null));
    }

    @Test
    public void manualAddressParserAcceptsOnlyPrivateIpv4Literals() {
        assertNotNull(BridgeClient.parsePrivateIpv4("192.168.1.20"));
        assertNotNull(BridgeClient.parsePrivateIpv4("100.64.0.1"));
        assertNull(BridgeClient.parsePrivateIpv4("8.8.8.8"));
        assertNull(BridgeClient.parsePrivateIpv4("laptop.local"));
        assertNull(BridgeClient.parsePrivateIpv4("https://192.168.1.20"));
    }

    @Test
    public void localAddressBoundaryMatchesProtocol() throws Exception {
        assertTrue(BridgeClient.isLanAddress(InetAddress.getByName("10.0.0.1")));
        assertTrue(BridgeClient.isLanAddress(InetAddress.getByName("172.31.255.255")));
        assertTrue(BridgeClient.isLanAddress(InetAddress.getByName("192.168.1.10")));
        assertTrue(BridgeClient.isLanAddress(InetAddress.getByName("100.64.0.1")));
        assertFalse(BridgeClient.isLanAddress(InetAddress.getByName("8.8.8.8")));
        assertFalse(BridgeClient.isLanAddress(InetAddress.getByName("172.32.0.1")));
    }

    @Test
    public void clientIdentityRequiresAStableOpaqueValue() {
        assertTrue(BridgeClient.isValidClientId("4ff125a1-0958-42de-b67f-d5c970b61ae6"));
        assertFalse(BridgeClient.isValidClientId("phone"));
        assertFalse(BridgeClient.isValidClientId("contains spaces and personal data"));
    }

    @Test
    public void discoveredBridgeMetadataIsBoundedAndDisplaySafe() {
        assertTrue(BridgeClient.isValidBridgeId("9d684ce8-bridge_1"));
        assertFalse(BridgeClient.isValidBridgeId("contains spaces"));
        assertFalse(BridgeClient.isValidBridgeId("x".repeat(129)));
        assertTrue(BridgeClient.isValidPort(45832));
        assertFalse(BridgeClient.isValidPort(0));
        assertEquals("My computer", BridgeClient.safeBridgeName("\n\t"));
        assertEquals(80, BridgeClient.safeBridgeName("x".repeat(100)).length());
    }

    @Test
    public void endpointTimeoutsCoverBridgeOperationBudgets() {
        assertEquals(130_000, BridgeClient.readTimeoutFor("/api/library?offset=0"));
        assertEquals(130_000, BridgeClient.readTimeoutFor("/api/collections?kind=artists"));
        assertEquals(14_000, BridgeClient.readTimeoutFor("/api/artwork?id=track"));
        assertEquals(14_000, BridgeClient.readTimeoutFor("/api/play"));
        assertEquals(10_000, BridgeClient.readTimeoutFor("/api/state"));
    }

    @Test
    public void libraryRevisionIsAdditiveAndBackwardCompatible() {
        BridgeClient.LibraryPage revised = new BridgeClient.LibraryPage(
                List.of(), 0, 60, 0, false, "catalog-123");
        BridgeClient.LibraryPage legacy = new BridgeClient.LibraryPage(
                List.of(), 0, 60, 0, false);

        assertEquals("catalog-123", revised.revision);
        assertEquals("", legacy.revision);
    }

    @Test
    public void collectionRevisionIsStoredWithItsPage() {
        BridgeClient.LibraryCollectionPage page = new BridgeClient.LibraryCollectionPage(
                List.of(), 0, 60, 0, false, "catalog-456");

        assertEquals("catalog-456", page.revision);
    }

    @Test
    public void artworkRequestSizeIsBounded() {
        assertEquals(64, BridgeClient.artworkRequestSize(-1));
        assertEquals(64, BridgeClient.artworkRequestSize(0));
        assertEquals(180, BridgeClient.artworkRequestSize(180));
        assertEquals(1000, BridgeClient.artworkRequestSize(Integer.MAX_VALUE));
    }

    @Test
    public void artworkDimensionsRejectInvalidAndDecompressionBombs() {
        assertFalse(BridgeClient.isSafeArtworkDimensions(0, 100));
        assertFalse(BridgeClient.isSafeArtworkDimensions(100, -1));
        assertTrue(BridgeClient.isSafeArtworkDimensions(1000, 1000));
        assertTrue(BridgeClient.isSafeArtworkDimensions(2000, 2000));
        assertFalse(BridgeClient.isSafeArtworkDimensions(2001, 2000));
        assertFalse(BridgeClient.isSafeArtworkDimensions(5000, 10));
    }

    @Test
    public void artworkSamplingAlwaysStartsAtAValidNonZeroFactor() {
        assertEquals(1, BridgeClient.artworkSampleSize(128, 128, 128));
        assertEquals(1, BridgeClient.artworkSampleSize(1000, 1000, 900));
        assertEquals(4, BridgeClient.artworkSampleSize(2048, 2048, 256));
    }

    @Test
    public void certificatePinMismatchIsClassifiedAsIdentityChange() {
        IOException wrapped = new IOException("TLS handshake failed",
                new CertificateException("Bridge security identity does not match"));
        assertTrue(BridgeClient.isIdentityFailure(wrapped));
        assertFalse(BridgeClient.isIdentityFailure(new IOException("Connection timed out")));
    }

    @Test
    public void discoveryDeduplicatesOnlyTheSameVerifiedIdentity() {
        BridgeClient.BridgeInfo original = new BridgeClient.BridgeInfo(
                "shared-id", "Computer", "192.168.1.10", 45832, FINGERPRINT);
        BridgeClient.BridgeInfo sameCertificate = new BridgeClient.BridgeInfo(
                "shared-id", "Computer", "192.168.1.20", 45832, FINGERPRINT);
        BridgeClient.BridgeInfo conflictingCertificate = new BridgeClient.BridgeInfo(
                "shared-id", "Computer", "192.168.1.10", 45832, "AA".repeat(32));

        assertEquals(BridgeClient.discoveryIdentityKey(original),
                BridgeClient.discoveryIdentityKey(sameCertificate));
        assertFalse(BridgeClient.discoveryIdentityKey(original).equals(
                BridgeClient.discoveryIdentityKey(conflictingCertificate)));
    }

    @Test
    public void artworkCancellationDisconnectsAnActiveRequest() throws Exception {
        BridgeClient.ConnectionCancellation cancellation =
                new BridgeClient.ConnectionCancellation();
        DisconnectTrackingConnection connection = new DisconnectTrackingConnection();
        cancellation.attach(connection);

        cancellation.cancel();

        assertTrue(connection.disconnected);
        try {
            cancellation.attach(new DisconnectTrackingConnection());
            throw new AssertionError("Cancelled request accepted another connection");
        } catch (InterruptedIOException expected) {
            assertTrue(cancellation.isCancelled());
        }
    }

    private static final class DisconnectTrackingConnection extends HttpsURLConnection {
        boolean disconnected;

        DisconnectTrackingConnection() throws Exception {
            super(new URL("https://192.168.1.10/"));
        }

        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
        @Override public String getCipherSuite() { return ""; }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public Certificate[] getServerCertificates() { return null; }
    }
}
