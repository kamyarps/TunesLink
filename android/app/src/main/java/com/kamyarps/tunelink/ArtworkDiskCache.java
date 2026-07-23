package com.kamyarps.tuneslink;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Private, bounded second-level artwork cache. Android may evict it under storage pressure. */
final class ArtworkDiskCache implements AutoCloseable {
    private static final long MAX_BYTES = 128L * 1024 * 1024;
    private static final long MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000;

    interface Loaded { void accept(Bitmap bitmap); }
    interface Handle { void cancel(); }

    private final File directory;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean closed = new AtomicBoolean();

    ArtworkDiskCache(Context context) {
        directory = new File(context.getCacheDir(), "artwork-v1");
    }

    Handle load(String scope, String key, Loaded loaded) {
        AtomicBoolean cancelled = new AtomicBoolean();
        if (closed.get()) return () -> { };
        executor.execute(() -> {
            Bitmap bitmap = null;
            File file = file(scope, key);
            long now = System.currentTimeMillis();
            if (file.isFile()) {
                if (now - file.lastModified() <= MAX_AGE_MS) {
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
                    if (BridgeClient.isSafeArtworkDimensions(bounds.outWidth, bounds.outHeight))
                        bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
                    if (bitmap != null) file.setLastModified(now);
                    else file.delete();
                } else {
                    file.delete();
                }
            }
            Bitmap result = bitmap;
            main.post(() -> {
                if (!cancelled.get() && !closed.get()) loaded.accept(result);
            });
        });
        return () -> cancelled.set(true);
    }

    void save(String scope, String key, Bitmap bitmap) {
        if (closed.get() || bitmap == null) return;
        executor.execute(() -> {
            if (!directory.exists() && !directory.mkdirs()) return;
            File destination = file(scope, key);
            File temporary = new File(directory, destination.getName() + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) return;
                output.getFD().sync();
                if (destination.exists() && !destination.delete()) return;
                if (!temporary.renameTo(destination)) return;
                destination.setLastModified(System.currentTimeMillis());
            } catch (Exception ignored) {
                // Artwork remains available in memory and can be fetched again.
            } finally {
                if (temporary.exists()) temporary.delete();
            }
            trim();
        });
    }

    void clearScope(String scope) {
        if (closed.get()) return;
        String prefix = digest(scope) + "-";
        executor.execute(() -> {
            File[] files = directory.listFiles(file -> file.getName().startsWith(prefix));
            if (files == null) return;
            for (File file : files) file.delete();
        });
    }

    private void trim() {
        File[] files = directory.listFiles(file -> file.isFile() && !file.getName().endsWith(".tmp"));
        if (files == null) return;
        long now = System.currentTimeMillis();
        long total = 0;
        for (File file : files) {
            if (now - file.lastModified() > MAX_AGE_MS) file.delete();
            else total += file.length();
        }
        if (total <= MAX_BYTES) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File file : files) {
            if (!file.exists()) continue;
            long length = file.length();
            if (file.delete()) total -= length;
            if (total <= MAX_BYTES) break;
        }
    }

    private File file(String scope, String key) {
        return new File(directory, digest(scope) + "-" + digest(key) + ".png");
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(hash.length * 2);
            for (byte item : hash) output.append(String.format("%02x", item & 0xff));
            return output.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) executor.shutdown();
    }
}
