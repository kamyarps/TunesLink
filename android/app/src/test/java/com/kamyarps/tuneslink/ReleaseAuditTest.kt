package com.kamyarps.tuneslink

import org.junit.Assert.*
import org.junit.Test

class ReleaseAuditTest {
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

    @Test fun reconnectSettlesEveryBrowseLevelBeforeRetry() {
        val busy = LibraryPageCursor(isLoading = true, isLoadingMore = true, isLoadingPrevious = true)
        val browse = LibraryBrowseUiState(kind = LibraryBrowseKind.Albums,
            collectionsCursor = busy, tracksCursor = busy,
            parentBrowse = LibraryBrowseUiState(kind = LibraryBrowseKind.Artists, collectionsCursor = busy))
        val restored = browse.afterReconnect()
        assertFalse(restored.collectionsCursor.isBusy)
        assertFalse(restored.tracksCursor.isBusy)
        assertFalse(restored.parentBrowse!!.collectionsCursor.isBusy)
        assertEquals(browse.kind, restored.kind)
    }

    @Test fun failedDiscoveryRetriesAndOnlyOneDiscoveryRunsAtATime() {
        val client = ProbeClient()
        BridgeRepository(client, store()).use { repository ->
            repository.startStateUpdates(listener())
            client.listener!!.connectionChanged(false, "Offline")
            repeat(4) { client.listener!!.connectionChanged(false, "Offline") }
            assertEquals(1, client.discoveries)
            client.discovery!!.success(emptyList())
            client.listener!!.connectionChanged(false, "Offline")
            assertEquals(2, client.discoveries)
            client.discovery!!.failure("Offline", false)
            client.listener!!.connectionChanged(false, "Offline")
            assertEquals(3, client.discoveries)
            repository.cancelRelocation()
            client.listener!!.connectionChanged(false, "Offline")
            assertEquals(4, client.discoveries)
        }
    }

    @Test fun relocationDropsOldPagesAndAcceptsPagesFromTheVerifiedEndpoint() {
        val client = ProbeClient()
        BridgeRepository(client, store()).use { repository ->
            var pages = 0
            val result = object : BridgeRepository.PageResult<BridgeClient.LibraryPage> {
                override fun page(value: BridgeClient.LibraryPage, authoritative: Boolean) { pages++ }
                override fun failure(message: String, unauthorized: Boolean) = fail(message)
            }
            repository.getLibrary("", 0, 60, result)
            val oldPage = client.library!!
            repository.startStateUpdates(listener())
            client.listener!!.connectionChanged(false, "Offline")
            val relocated = bridge("192.168.1.21")
            client.discovery!!.success(listOf(relocated))
            client.verification!!.success(relocated)
            val empty = BridgeClient.LibraryPage(emptyList(), 0, 60, 0, false)
            oldPage.success(empty)
            assertEquals(0, pages)
            repository.getLibrary("", 0, 60, result)
            assertEquals(relocated.host, client.libraryHost)
            client.library!!.success(empty)
            assertEquals(1, pages)
        }
    }

    @Test fun streamRecoveryCancelsAnObsoleteDiscovery() {
        val client = ProbeClient()
        BridgeRepository(client, store()).use { repository ->
            repository.startStateUpdates(listener())
            client.listener!!.connectionChanged(false, "Offline")
            client.listener!!.state(BridgeClient.PlayerState(true, false, "", "", "", 0.0, 0.0, 50, "", "", false, "off"))
            client.discovery!!.success(listOf(bridge("192.168.1.21")))
            assertNull(client.verification)
            assertEquals("192.168.1.20", repository.current()!!.host)
        }
    }

    @Test fun pairingCooldownReachesTheObserverAndBlocksSubmission() {
        val client = ProbeClient()
        BridgeRepository(client, store()).use { repository ->
            var cooldown = 0
            repository.pair(bridge(), "123456", object : BridgeClient.Result<SecureStore.SavedBridge> {
                override fun success(value: SecureStore.SavedBridge) = fail("Unexpected pairing")
                override fun failure(message: String, unauthorized: Boolean) = fail(message)
                override fun rateLimited(retryAfterSeconds: Int) { cooldown = retryAfterSeconds }
            })
            client.pairing!!.rateLimited(39)
            assertEquals(39, cooldown)
            val state = PairingUiState("123456", retryAfterSeconds = cooldown)
            assertFalse(state.canSubmit)
            assertTrue(state.copy(retryAfterSeconds = 0).canSubmit)
            assertFalse(state.copy(code = "١٢٣٤٥٦", retryAfterSeconds = 0).canSubmit)
        }
        assertEquals(39, BridgeHttpClient.retryAfterSeconds("39"))
        assertEquals(60, BridgeHttpClient.retryAfterSeconds(null))
        assertEquals(3600, BridgeHttpClient.retryAfterSeconds("9999999"))
    }

    @Test fun playbackSequencesSurviveStoreRecreation() {
        val backend = MemoryBackend()
        val first = SecureStore(backend, crypto())
        assertEquals(1L, first.nextPlaybackSequence())
        assertEquals(2L, SecureStore(backend, crypto()).nextPlaybackSequence())
    }

    @Test fun emptySearchUsesTheSameBackDecisionAsOtherDestinations() {
        val active = searchBackEnabled(TunesLinkDestination.Search, false, "")
        assertEquals(NavigationBackAction.ShowLibrary,
            navigationBackAction(false, active, TunesLinkDestination.Search))
        assertEquals(NavigationBackAction.System,
            navigationBackAction(false, false, TunesLinkDestination.Library))
        assertEquals(NavigationBackAction.DismissModal,
            navigationBackAction(true, true, TunesLinkDestination.Search))
    }

    private fun bridge(host: String = "192.168.1.20") =
        BridgeClient.BridgeInfo("audit-pc", "PC", host, 45832, "AA".repeat(32))

    private fun store() = SecureStore(MemoryBackend(), crypto()).apply {
        save(bridge(), "test-token-" + "a".repeat(40))
    }

    private fun crypto() = object : SecureStore.Crypto {
        override fun encrypt(plaintext: ByteArray) = SecureStore.Envelope(plaintext.clone(), byteArrayOf(1))
        override fun decrypt(ciphertext: ByteArray, iv: ByteArray) = ciphertext.clone()
    }

    private class MemoryBackend : SecureStore.Backend {
        private val values = mutableMapOf<String, Any>()
        override fun getString(key: String, fallback: String?): String? = values[key] as? String ?: fallback
        override fun getInt(key: String, fallback: Int) = values[key] as? Int ?: fallback
        override fun commit(additions: Map<String, Any>, removals: Set<String>): Boolean {
            removals.forEach(values::remove)
            values.putAll(additions)
            return true
        }
    }

    private fun listener() = object : BridgeRepository.StateUpdatesListener {
        override fun state(state: BridgeClient.PlayerState) = Unit
        override fun connectionChanged(bridge: SecureStore.SavedBridge, connected: Boolean, message: String?) = Unit
        override fun unauthorized(bridge: SecureStore.SavedBridge, message: String) = Unit
    }

    private class ProbeClient : BridgeClient() {
        var library: Result<LibraryPage>? = null
        var libraryHost = ""
        var discovery: Result<List<BridgeInfo>>? = null
        var verification: Result<BridgeInfo>? = null
        var pairing: Result<String>? = null
        var listener: StateListener? = null
        var discoveries = 0
        override fun getLibrary(bridge: SecureStore.SavedBridge, query: String, offset: Int, limit: Int,
            result: Result<LibraryPage>): Cancellation {
            library = result
            libraryHost = bridge.host
            return Cancellation.NONE
        }
        override fun discover(result: Result<List<BridgeInfo>>): Cancellation {
            discoveries++
            discovery = result
            return Cancellation.NONE
        }
        override fun verifyIdentity(candidate: BridgeInfo, id: String, fingerprint: String,
            result: Result<BridgeInfo>): Cancellation {
            verification = result
            return Cancellation.NONE
        }
        override fun pair(bridge: BridgeInfo, code: String, clientId: String, result: Result<String>): Cancellation {
            pairing = result
            return Cancellation.NONE
        }
        override fun startStateUpdates(bridge: SecureStore.SavedBridge, listener: StateListener) { this.listener = listener }
        override fun stopStateUpdates() = Unit
    }
}
