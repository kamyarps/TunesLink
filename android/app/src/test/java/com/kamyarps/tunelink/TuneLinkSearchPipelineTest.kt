package com.kamyarps.tuneslink

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TunesLinkSearchPipelineTest {
    @Test
    fun clearingDuringDebounceCommitsTheFullLibraryImmediately() = runTest {
        val edits = MutableSharedFlow<String>(extraBufferCapacity = 4)
        val committed = mutableListOf<String>()
        val collection = launch { edits.committedSearchQueries().collect(committed::add) }
        runCurrent()

        edits.emit("Beatles")
        advanceTimeBy(100)
        edits.emit("")
        runCurrent()

        assertEquals(listOf(""), committed)
        advanceTimeBy(250)
        runCurrent()
        assertEquals(listOf(""), committed)
        collection.cancel()
    }
}
