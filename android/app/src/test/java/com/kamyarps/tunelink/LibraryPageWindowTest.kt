package com.kamyarps.tuneslink

import org.junit.Assert.assertEquals
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
}
