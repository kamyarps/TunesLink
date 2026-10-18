package com.kamyarps.tuneslink

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArtworkDiskCacheTest {
    @get:Rule val directory = TemporaryFolder()

    private fun finish(executor: ThreadPoolExecutor, release: CountDownLatch) {
        release.countDown()
        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
    }

    private fun entry(scope: String, key: String): File {
        fun digest(value: String) = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory.root, "${digest(scope)}-${digest(key)}.jpg")
            .apply { writeText("cover") }
    }

    @Test fun cancelledQueuedReadsAreRemovedBeforeTheyCanDecode() {
        val executor = ArtworkDiskCache.newExecutor()
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val decodes = AtomicInteger()
        executor.execute { started.countDown(); release.await(5, TimeUnit.SECONDS) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val cover = entry("pc", "old")
        try {
            ArtworkDiskCache(directory.root, executor, Runnable::run) { _, _ ->
                decodes.incrementAndGet(); null
            }.use { cache ->
                repeat(50) {
                    cache.load("pc", "old") { _, _ -> fail("Cancelled load delivered") }.cancel()
                }
                assertEquals(0, executor.queue.size)
                val visible = CountDownLatch(1)
                cache.load("pc", "visible") { _, _ -> visible.countDown() }
                release.countDown()
                assertTrue(visible.await(5, TimeUnit.SECONDS))
                assertEquals(0, decodes.get())
                assertTrue(cover.exists())
            }
        } finally { finish(executor, release) }
    }

    @Test fun fullQueueReturnsAMissInsteadOfStrandingTheObserver() {
        val executor = ArtworkDiskCache.newExecutor()
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        executor.execute { started.countDown(); release.await(5, TimeUnit.SECONDS) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        try {
            ArtworkDiskCache(directory.root, executor, Runnable::run) { _, _ -> null }.use { cache ->
                repeat(16) { cache.load("pc", "queued-$it") { _, _ -> } }
                var missed = false
                cache.load("pc", "visible") { bitmap, fetchedAt ->
                    assertNull(bitmap)
                    assertEquals(0L, fetchedAt)
                    missed = true
                }
                assertTrue(missed)
                assertEquals(16, executor.queue.size)
            }
        } finally { finish(executor, release) }
    }

    @Test fun cancellationDuringDecodeDoesNotDeleteTheCoverOrDeliverAResult() {
        val executor = ArtworkDiskCache.newExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val cover = entry("pc", "cover")
        val delivered = AtomicInteger()
        try {
            ArtworkDiskCache(directory.root, executor, Runnable::run) { _, cancelled ->
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                assertTrue(cancelled.asBoolean)
                null
            }.use { cache ->
                val handle = cache.load("pc", "cover") { _, _ -> delivered.incrementAndGet() }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                handle.cancel()
                release.countDown()
                executor.execute { drained.countDown() }
                assertTrue(drained.await(5, TimeUnit.SECONDS))
                assertTrue(cover.exists())
                assertEquals(0, delivered.get())
            }
        } finally { finish(executor, release) }
    }

    @Test fun aFullQueueCannotDropForgottenComputerCleanup() {
        val executor = ArtworkDiskCache.newExecutor()
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        executor.execute { started.countDown(); release.await(5, TimeUnit.SECONDS) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val cover = entry("forgotten-pc", "cover")
        try {
            ArtworkDiskCache(directory.root, executor, Runnable::run) { _, _ -> null }.use { cache ->
                repeat(16) { cache.load("other-pc", "queued-$it") { _, _ -> } }
                cache.clearScope("forgotten-pc")
                assertEquals(16, executor.queue.size)
            }
            release.countDown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            assertFalse(cover.exists())
        } finally { finish(executor, release) }
    }
}
