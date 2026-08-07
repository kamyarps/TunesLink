package com.kamyarps.tuneslink;

import android.annotation.SuppressLint;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

/** Performs bounded HTTPS requests and owns TunesLink's certificate-pinning implementation. */
final class BridgeHttpClient {
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 10_000;

    static final class Response {
        final int status;
        final byte[] body;
        final String contentType;

        Response(int status, byte[] body, String contentType) {
            this.status = status;
            this.body = body;
            this.contentType = contentType;
        }
    }

    private String cachedTlsFingerprint = "";
    private SSLSocketFactory cachedTlsFactory;

    Response request(String host, int port, String tlsFingerprint, String token,
                     String method, String path, byte[] body,
                     BridgeClient.ConnectionCancellation cancellation) throws IOException {
        HttpsURLConnection connection = openPinnedConnection(host, port, tlsFingerprint, path);
        if (cancellation != null) cancellation.attach(connection);
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(BridgeClient.readTimeoutFor(path));
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json, image/*");
            if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }
            }
            int status = connection.getResponseCode();
            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_RESPONSE_BYTES) {
                throw new IOException("Bridge response was unexpectedly large");
            }
            InputStream stream = status >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            byte[] response = stream == null ? new byte[0]
                    : readLimited(stream, MAX_RESPONSE_BYTES);
            String contentType = connection.getContentType();
            return new Response(status, response, contentType == null ? "" : contentType);
        } finally {
            if (cancellation != null) cancellation.detach(connection);
            connection.disconnect();
        }
    }

    HttpsURLConnection openPinnedConnection(String host, int port, String tlsFingerprint,
                                             String path) throws IOException {
        if (!BridgeClient.isLanHost(host)) {
            throw new IOException("TunesLink only connects inside your local network");
        }
        if (!BridgeClient.isValidFingerprint(tlsFingerprint)) {
            throw new IOException("Bridge security identity is invalid");
        }
        URL url = new URL("https", host, port, path);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        String expectedFingerprint = BridgeClient.normalizeFingerprint(tlsFingerprint);
        connection.setSSLSocketFactory(socketFactoryFor(expectedFingerprint));
        connection.setHostnameVerifier((ignoredHost, session) -> {
            try {
                Certificate[] peer = session.getPeerCertificates();
                return peer.length > 0 && peer[0] instanceof X509Certificate
                        && expectedFingerprint.equals(fingerprint((X509Certificate) peer[0]));
            } catch (Exception invalidPeer) {
                return false;
            }
        });
        return connection;
    }

    private synchronized SSLSocketFactory socketFactoryFor(String expectedFingerprint)
            throws IOException {
        if (cachedTlsFactory == null || !cachedTlsFingerprint.equals(expectedFingerprint)) {
            cachedTlsFactory = pinnedSocketFactory(expectedFingerprint);
            cachedTlsFingerprint = expectedFingerprint;
        }
        return cachedTlsFactory;
    }

    synchronized void clear() {
        cachedTlsFingerprint = "";
        cachedTlsFactory = null;
    }

    @SuppressLint("CustomX509TrustManager")
    static String probeFingerprint(String host, int port) throws Exception {
        return probeFingerprint(host, port, null);
    }

    @SuppressLint("CustomX509TrustManager")
    static String probeFingerprint(String host, int port,
                                   BridgeClient.ConnectionCancellation cancellation)
            throws Exception {
        X509TrustManager probeTrust = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {
                throw new UnsupportedOperationException();
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                if (chain == null || chain.length == 0) {
                    throw new CertificateException("Bridge sent no identity");
                }
                chain[0].checkValidity();
            }
            @Override public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new X509TrustManager[]{probeTrust}, null);
        try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket()) {
            if (cancellation != null) cancellation.attach(socket);
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(DEFAULT_READ_TIMEOUT_MS);
            socket.startHandshake();
            Certificate[] peer = socket.getSession().getPeerCertificates();
            if (peer.length == 0 || !(peer[0] instanceof X509Certificate certificate)) {
                throw new IOException("Bridge sent no security identity");
            }
            return fingerprint(certificate);
        } finally {
            // The try-with-resources closes the socket. Cancellation retains no stale reference
            // because attach() is only used for the duration of this bounded handshake.
        }
    }

    @SuppressLint("CustomX509TrustManager")
    private static SSLSocketFactory pinnedSocketFactory(String expectedFingerprint)
            throws IOException {
        try {
            X509TrustManager pinTrust = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    throw new UnsupportedOperationException();
                }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                        throws CertificateException {
                    if (chain == null || chain.length == 0) {
                        throw new CertificateException("Bridge sent no identity");
                    }
                    chain[0].checkValidity();
                    if (!expectedFingerprint.equals(fingerprint(chain[0]))) {
                        throw new CertificateException(
                                "Bridge security identity does not match");
                    }
                }
                @Override public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new X509TrustManager[]{pinTrust}, null);
            return context.getSocketFactory();
        } catch (Exception exception) {
            throw new IOException("Could not create a secure bridge connection", exception);
        }
    }

    private static String fingerprint(X509Certificate certificate) throws CertificateException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(certificate.getEncoded());
            StringBuilder value = new StringBuilder(64);
            for (byte item : digest) {
                value.append(String.format(Locale.ROOT, "%02X", item & 0xff));
            }
            return value.toString();
        } catch (Exception exception) {
            throw new CertificateException("Could not verify bridge identity", exception);
        }
    }

    private static byte[] readLimited(InputStream stream, int max) throws IOException {
        try (stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > max) {
                    throw new IOException("Bridge response was unexpectedly large");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }
}
