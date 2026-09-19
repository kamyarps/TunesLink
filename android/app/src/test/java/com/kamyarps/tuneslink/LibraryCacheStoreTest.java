package com.kamyarps.tuneslink;

import static org.junit.Assert.*;

import org.junit.Test;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class LibraryCacheStoreTest {
    @Test
    public void unavailableDatabaseAlwaysFallsBackAndDoesNotKillTheWorker() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger uncaught = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task);
            thread.setUncaughtExceptionHandler((ignored, error) -> uncaught.incrementAndGet());
            return thread;
        });
        LibraryCacheStore cache = new LibraryCacheStore(() -> {
            opens.incrementAndGet();
            throw new IllegalStateException("Disk full or database corrupt");
        }, () -> {
            closes.incrementAndGet();
            throw new IllegalStateException("Close failed");
        }, executor, Runnable::run);
        CountDownLatch callbacks = new CountDownLatch(2);
        try {
            cache.loadTracks("bridge", "first", page -> { assertNull(page); callbacks.countDown(); });
            cache.saveTracks("bridge", "write", new BridgeClient.LibraryPage(
                    List.of(), 0, 60, 0, false, "revision"));
            cache.clearScope("bridge");
            cache.loadCollections("bridge", "after-failed-write", page -> {
                assertNull(page);
                callbacks.countDown();
            });
            assertTrue("Both failed reads must unblock network fetching", callbacks.await(3, TimeUnit.SECONDS));
        } finally {
            cache.close();
        }
        assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        assertEquals(4, opens.get());
        assertEquals(1, closes.get());
        assertEquals(0, uncaught.get());
        cache.clearScope("bridge");
        cache.close();
    }

    @Test
    public void rejectedReadStillReportsCacheMiss() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.shutdown();
        AtomicInteger callbacks = new AtomicInteger();
        try (LibraryCacheStore cache = new LibraryCacheStore(() -> {
            throw new AssertionError("Rejected operation must not run");
        }, () -> { }, executor, Runnable::run)) {
            cache.loadTracks("bridge", "read", page -> { assertNull(page); callbacks.incrementAndGet(); });
        }
        assertEquals(1, callbacks.get());
    }

    @Test
    public void cancelledReadCannotDeliverAnObsoletePage() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> {
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        AtomicInteger callbacks = new AtomicInteger();
        LibraryCacheStore cache = new LibraryCacheStore(() -> {
            throw new IllegalStateException("Unavailable");
        }, () -> { }, executor, Runnable::run);
        cache.loadTracks("bridge", "read", ignored -> callbacks.incrementAndGet()).cancel();
        release.countDown();
        executor.submit(() -> { }).get(3, TimeUnit.SECONDS);
        cache.close();
        assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        assertEquals(0, callbacks.get());
    }

    @Test
    public void artworkReadsNeverExtendFreshnessAndClockRollbackExpiresEntries() {
        long fetchedAt = 1000;
        assertTrue(ArtworkDiskCache.isFresh(fetchedAt, fetchedAt));
        assertTrue(ArtworkDiskCache.isFresh(fetchedAt, fetchedAt + ArtworkDiskCache.MAX_AGE_MS - 1));
        assertFalse(ArtworkDiskCache.isFresh(fetchedAt, fetchedAt + ArtworkDiskCache.MAX_AGE_MS));
        assertFalse(ArtworkDiskCache.isFresh(fetchedAt, fetchedAt - 1));
    }
}
