package com.kamyarps.tuneslink;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;

class BridgeClientSupport {
    static final String IDENTITY_CHANGED_MESSAGE =
            "Bridge identity changed — verify and pair again";
    static final int DEFAULT_PORT = 45832;
    static final int DISCOVERY_PORT = 45831;
    static final String DISCOVERY_MESSAGE = "TunesLink_DISCOVER_V1";
    static final String PROTOCOL = "TunesLink-3";
    static final int CONNECT_TIMEOUT_MS = 3_000;
    static final int DEFAULT_READ_TIMEOUT_MS = 10_000;
    static final int MEDIA_READ_TIMEOUT_MS = 14_000;
    static final int PLAYBACK_READ_TIMEOUT_MS = 45_000;
    static final int LIBRARY_READ_TIMEOUT_MS = 130_000;

    static InetAddress parsePrivateIpv4(String input) {
        if (input == null) return null;
        String[] parts = input.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] address = new byte[4];
        for (int index = 0; index < parts.length; index++) {
            if (parts[index].isEmpty() || parts[index].length() > 3) return null;
            int octet;
            try {
                octet = Integer.parseInt(parts[index]);
            } catch (NumberFormatException invalidOctet) {
                return null;
            }
            if (octet < 0 || octet > 255) return null;
            address[index] = (byte) octet;
        }
        try {
            InetAddress parsed = InetAddress.getByAddress(address);
            return parsed instanceof Inet4Address && isLanAddress(parsed) ? parsed : null;
        } catch (IOException impossibleAddressLength) {
            return null;
        }
    }

    static boolean isValidFingerprint(String fingerprint) {
        return fingerprint != null && normalizeFingerprint(fingerprint).matches("[0-9A-F]{64}");
    }

    static boolean isValidClientId(String clientId) {
        return clientId != null && clientId.matches("[A-Za-z0-9._-]{16,80}");
    }

    static boolean isValidBridgeId(String bridgeId) {
        return bridgeId != null && bridgeId.matches("[A-Za-z0-9._-]{1,128}");
    }

    static boolean isValidPort(int port) {
        return port >= 1 && port <= 65_535;
    }

    static String safeBridgeName(String name) {
        String safe = name == null ? "" : name.replaceAll("\\p{Cntrl}", "").trim();
        if (safe.isBlank()) return "My computer";
        return safe.substring(0, Math.min(80, safe.length()));
    }

    static int readTimeoutFor(String path) {
        String route = path == null ? "" : path.split("\\?", 2)[0];
        return switch (route) {
            case "/api/library", "/api/collections" -> LIBRARY_READ_TIMEOUT_MS;
            case "/api/play" -> PLAYBACK_READ_TIMEOUT_MS;
            case "/api/artwork" -> MEDIA_READ_TIMEOUT_MS;
            default -> DEFAULT_READ_TIMEOUT_MS;
        };
    }

    static int artworkRequestSize(int size) {
        return clamp(size, 64, 1000);
    }

    static boolean isSafeArtworkDimensions(int width, int height) {
        return width > 0 && height > 0
                && width <= 4096 && height <= 4096
                && (long) width * height <= 4_000_000;
    }

    static int artworkSampleSize(int width, int height, int requestedSize) {
        int sampleSize = 1;
        while (width / sampleSize > requestedSize * 2
                || height / sampleSize > requestedSize * 2) {
            sampleSize *= 2;
        }
        return sampleSize;
    }

    static Bitmap decodeArtwork(byte[] bytes, int requestedSize) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (!isSafeArtworkDimensions(bounds.outWidth, bounds.outHeight))
            throw new IOException("Artwork dimensions were unexpectedly large");

        BitmapFactory.Options options = new BitmapFactory.Options();
        // Android leaves inSampleSize at zero. Treat the undecimated decode as
        // factor one before applying the bounded power-of-two downsampling loop.
        options.inSampleSize = artworkSampleSize(bounds.outWidth, bounds.outHeight, requestedSize);
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (decoded == null) throw new IOException("Artwork could not be decoded");
        int largest = Math.max(decoded.getWidth(), decoded.getHeight());
        if (largest <= requestedSize) return decoded;
        float scale = requestedSize / (float) largest;
        int width = Math.max(1, Math.round(decoded.getWidth() * scale));
        int height = Math.max(1, Math.round(decoded.getHeight() * scale));
        Bitmap resized = Bitmap.createScaledBitmap(decoded, width, height, true);
        if (resized != decoded) decoded.recycle();
        return resized;
    }

    static String normalizeFingerprint(String fingerprint) {
        return fingerprint == null ? "" : fingerprint.replaceAll("[^0-9A-Fa-f]", "")
                .toUpperCase(Locale.ROOT);
    }

    static Set<InetAddress> broadcastAddresses() {
        LinkedHashSet<InetAddress> addresses = new LinkedHashSet<>();
        try {
            addresses.add(InetAddress.getByName("255.255.255.255"));
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface network : Collections.list(interfaces)) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (InterfaceAddress address : network.getInterfaceAddresses()) {
                    if (address.getBroadcast() != null) addresses.add(address.getBroadcast());
                }
            }
        } catch (Exception ignored) {
        }
        return addresses;
    }

    static boolean isLanHost(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isLanAddress(address)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    static boolean isLanAddress(InetAddress address) {
        if (address.isSiteLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress()) return true;
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && (bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64;
    }

    static JSONObject parseObject(byte[] bytes) throws JSONException {
        if (bytes.length == 0) return new JSONObject();
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    static String normalizedRepeatMode(String value) {
        return switch (value) {
            case "one", "all" -> value;
            default -> "off";
        };
    }

    static String deviceName() {
        String maker = Build.MANUFACTURER == null ? "Android" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "phone" : Build.MODEL.trim();
        if (model.toLowerCase(Locale.ROOT).startsWith(maker.toLowerCase(Locale.ROOT))) return model;
        return maker + " " + model;
    }

    static String urlEncode(String text) {
        try {
            return java.net.URLEncoder.encode(text, StandardCharsets.UTF_8.toString());
        } catch (Exception impossible) {
            return "";
        }
    }

    static String friendly(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "Could not reach the computer";
        if (message.contains("timed out") || message.contains("failed to connect")) {
            return "Could not reach the computer. Check Wi-Fi and TunesLink Bridge.";
        }
        return message;
    }

    static boolean isIdentityFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) continue;
            String normalized = message.toLowerCase(Locale.ROOT);
            if (normalized.contains("bridge security identity does not match")
                    || normalized.contains("bridge security identity is invalid")) return true;
        }
        return false;
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
