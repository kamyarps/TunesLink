package com.kamyarps.tuneslink

import org.junit.Assert.*
import org.junit.Test

class LibraryPageWindowTest {
    @Test
    fun appendingBeyondLimitEvictsOldestAbsoluteRows() {
        val window = mergePageWindow(
            existing = (0 until 480).toList(),
            existingStart = 0,
            incoming = (480 until 540).toList(),
            incomingStart = 480,
            replace = false,
            maximumItems = 480,
        )

        assertEquals(60, window.startOffset)
        assertEquals((60 until 540).toList(), window.items)
    }

    @Test
    fun prependingEvictedPageKeepsBoundAndDropsNewestRows() {
        val window = mergePageWindow(
            existing = (60 until 540).toList(),
            existingStart = 60,
            incoming = (0 until 60).toList(),
            incomingStart = 0,
            replace = false,
            maximumItems = 480,
        )

        assertEquals(0, window.startOffset)
        assertEquals((0 until 480).toList(), window.items)
    }

    @Test
    fun refreshedPageReplacesItsAbsoluteSlotsWithoutDuplicates() {
        val window = mergePageWindow(
            existing = (0 until 120).map { "old-$it" },
            existingStart = 0,
            incoming = (60 until 120).map { "new-$it" },
            incomingStart = 60,
            replace = false,
            maximumItems = 480,
        )

        assertEquals(120, window.items.size)
        assertEquals("old-59", window.items[59])
        assertEquals("new-60", window.items[60])
        assertEquals("new-119", window.items[119])
    }

    @Test
    fun nonContiguousResponseStartsANewSafeWindow() {
        val window = mergePageWindow(
            existing = (0 until 60).toList(),
            existingStart = 0,
            incoming = (180 until 240).toList(),
            incomingStart = 180,
            replace = false,
            maximumItems = 480,
        )

        assertEquals(180, window.startOffset)
        assertEquals((180 until 240).toList(), window.items)
    }

    @Test
    fun aSixHundredAndOneSongLibraryCanBeBrowsedForwardAndBackWithoutLosingRows() {
        var window = PageWindow((0 until 60).toList(), 0)
        var cursor = settledCursor(LibraryPageCursor(), window, 601, "revision", true)
        val seen = window.items.toMutableSet()
        while (cursor.hasMore) {
            assertEquals(PageDirection.Next, pageDirection(cursor, atStart = false, atEnd = true))
            val offset = window.startOffset + window.items.size
            window = mergePageWindow(window.items, window.startOffset,
                (offset until minOf(offset + 60, 601)).toList(), offset, false, 480)
            seen.addAll(window.items)
            cursor = settledCursor(cursor, window, 601, "revision", false)
            assertTrue(window.items.size <= 480)
        }
        assertEquals((0 until 601).toSet(), seen)
        assertEquals(121, window.startOffset)
        while (cursor.hasPrevious) {
            assertEquals(PageDirection.Previous, pageDirection(cursor, atStart = true, atEnd = false))
            val offset = (window.startOffset - 60).coerceAtLeast(0)
            window = mergePageWindow(window.items, window.startOffset,
                (offset until window.startOffset).toList(), offset, false, 480)
            cursor = settledCursor(cursor, window, 601, "revision", false)
        }
        assertEquals((0 until 480).toList(), window.items)
        assertEquals(0, window.startOffset)
        assertEquals(PageDirection.Next, pageDirection(cursor, atStart = false, atEnd = true))
    }

    @Test
    fun paginationWaitsForRetryAfterFailureAndNeverStartsTwoLoads() {
        val cursor = LibraryPageCursor(hasMore = true, hasPrevious = true)
        assertNull(pageDirection(cursor.copy(error = "Connection lost"), true, true))
        assertNull(pageDirection(cursor.copy(isLoadingMore = true), true, true))
        assertNull(pageDirection(cursor.copy(isLoadingPrevious = true), true, true))
        assertEquals(PageDirection.Previous, pageDirection(cursor, true, true))
    }

    @Test
    fun nonAdvancingPagesStopButAnExhaustedShrunkenLibraryIsAccepted() {
        assertFalse(pageAdvances(100060, 100000, 60, true))
        assertFalse(pageAdvances(60, 60, 0, true))
        assertTrue(pageAdvances(100060, 100060, 60, true))
        assertTrue(pageAdvances(100060, 0, 0, false))
        assertTrue(pageAdvances(Int.MAX_VALUE, Int.MAX_VALUE, 1, true))
        val window = mergePageWindow(listOf(1), 0, listOf(2), Int.MAX_VALUE - 1, false, 480)
        assertEquals(listOf(2), window.items)
    }

    @Test fun authoritativeEmptyPageRemovesTheLastDeletedTrack() {
        val window = mergePageWindow(listOf("deleted"), 0, emptyList(), 0, false, 480, total = 0)
        assertTrue(window.items.isEmpty())
        val cursor = settledCursor(LibraryPageCursor(isLoadingMore = true), window, 0, "", false)
        assertFalse(cursor.isBusy)
        assertFalse(cursor.hasMore)
        assertFalse(cursor.hasPrevious)
    }

    @Test fun clampedEmptyPageTrimsAnUnversionedWindow() {
        val window = mergePageWindow((0 until 60).toList(), 0, emptyList(), 30, false, 480, total = 30)
        assertEquals((0 until 30).toList(), window.items)
        assertEquals(0, window.startOffset)
        assertFalse(settledCursor(LibraryPageCursor(), window, 30, "", false).hasMore)
        val evicted = mergePageWindow((240 until 300).toList(), 240, emptyList(), 30, false, 480, total = 30)
        assertTrue(evicted.items.isEmpty())
        assertEquals(30, evicted.startOffset)
    }

}
