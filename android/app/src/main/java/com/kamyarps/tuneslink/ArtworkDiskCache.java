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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Private, bounded second-level artwork cache. Android may evict it under storage pressure. */
final class ArtworkDiskCache implements AutoCloseable {
    private static final long MAX_BYTES = 128L * 1024 * 1024;
    static final long REFRESH_INTERVAL_MS = 5L * 60 * 1000;
    static final long MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000;

    static boolean isFresh(long fetchedAt, long now) {
        return now >= fetchedAt && now - fetchedAt < REFRESH_INTERVAL_MS;
    }

    static boolean isRetained(long fetchedAt, long now) {
        return now >= fetchedAt && now - fetchedAt < MAX_AGE_MS;
    }

    interface Loaded { void accept(Bitmap bitmap, long fetchedAt); }
    interface Handle { void cancel(); }
    interface Decoder { Bitmap decode(File file, BooleanSupplier cancelled); }

    private final File directory;
    private final ThreadPoolExecutor executor;
    private final Consumer<Runnable> dispatch;
    private final Decoder decoder;
    private final AtomicBoolean closed = new AtomicBoolean();

    ArtworkDiskCache(Context context) {
        this(new File(context.getCacheDir(), "artwork-v1"), newExecutor(),
                new Handler(Looper.getMainLooper())::post, ArtworkDiskCache::decode);
    }

    ArtworkDiskCache(File directory, ThreadPoolExecutor executor, Consumer<Runnable> dispatch,
                     Decoder decoder) {
        this.directory = directory;
        this.executor = executor;
        this.dispatch = dispatch;
        this.decoder = decoder;
    }

    static ThreadPoolExecutor newExecutor() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16));
    }

    private static Bitmap decode(File file, BooleanSupplier cancelled) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (cancelled.getAsBoolean()
                || !BridgeClient.isSafeArtworkDimensions(bounds.outWidth, bounds.outHeight)) return null;
        return BitmapFactory.decodeFile(file.getAbsolutePath());
    }

    Handle load(String scope, String key, Loaded loaded) {
        AtomicBoolean cancelled = new AtomicBoolean();
        if (closed.get()) return () -> { };
        FutureTask<Void> task = new FutureTask<>(() -> {
            if (cancelled.get() || closed.get()) return;
            Bitmap bitmap = null;
            long fetchedAt = 0;
            try {
                File file = file(scope, key);
                long now = System.currentTimeMillis();
                if (file.isFile()) {
                    if (isRetained(file.lastModified(), now)) {
                        bitmap = decoder.decode(file, () -> cancelled.get() || closed.get());
                        if (cancelled.get() || closed.get()) return;
                        if (bitmap != null) fetchedAt = file.lastModified();
                        else file.delete();
                    } else {
                        file.delete();
                    }
                }
            } catch (Exception ignored) {
                // A failed optional disk read is a miss; always let the caller fetch it.
            }
            Bitmap result = bitmap;
            long storedAt = fetchedAt;
            dispatch.accept(() -> {
                if (!cancelled.get() && !closed.get()) loaded.accept(result, storedAt);
            });
        }, null);
        try { executor.execute(task); }
        catch (java.util.concurrent.RejectedExecutionException full) {
            // A busy optional cache is a miss, never an observer left waiting indefinitely.
            dispatch.accept(() -> {
                if (!cancelled.get() && !closed.get()) loaded.accept(null, 0);
            });
        }
        return () -> {
            cancelled.set(true);
            task.cancel(false);
            executor.remove(task);
        };
    }

    void save(String scope, String key, Bitmap bitmap) {
        if (closed.get() || bitmap == null) return;
        executeBestEffort(() -> {
            if (!directory.exists() && !directory.mkdirs()) return;
            File destination = file(scope, key);
            File temporary = new File(directory, destination.getName() + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)) return;
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
        executeMaintenance(() -> {
            File[] files = directory.listFiles(file -> file.getName().startsWith(prefix));
            if (files == null) return;
            for (File file : files) file.delete();
        });
    }

    void remove(String scope, String key) {
        if (!closed.get()) executeMaintenance(() -> file(scope, key).delete());
    }

    private void executeMaintenance(Runnable operation) {
        Runnable task = () -> {
            try { operation.run(); } catch (Exception ignored) { }
        };
        while (!closed.get()) {
            try {
                executor.execute(task);
                return;
            } catch (java.util.concurrent.RejectedExecutionException full) {
                if (executor.isShutdown()) return;
                // Preserve deletion order without blocking the UI or dropping queued readers.
                // Fold it into the tail slot when the bounded optional-work queue is full.
                Runnable[] queued = executor.getQueue().toArray(new Runnable[0]);
                if (queued.length == 0) continue;
                Runnable tail = queued[queued.length - 1];
                if (executor.remove(tail)) {
                    Runnable deletion = task;
                    task = () -> { tail.run(); deletion.run(); };
                }
            }
        }
    }

    private void executeBestEffort(Runnable operation) {
        try {
            executor.execute(() -> {
                try { operation.run(); } catch (Exception ignored) { }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Optional maintenance/writes may be skipped when the cache is busy or closing.
        }
    }

    private void trim() {
        File[] files = directory.listFiles(file -> file.isFile() && !file.getName().endsWith(".tmp"));
        if (files == null) return;
        long now = System.currentTimeMillis();
        long total = 0;
        for (File file : files) {
            if (!isRetained(file.lastModified(), now) && file.delete()) continue;
            total += file.length();
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
        return new File(directory, digest(scope) + "-" + digest(key) + ".jpg");
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
