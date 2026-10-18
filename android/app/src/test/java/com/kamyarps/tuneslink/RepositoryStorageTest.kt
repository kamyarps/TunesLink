package com.kamyarps.tuneslink

import java.util.ArrayDeque
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryStorageTest {
    @Before fun setDispatcher() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun resetDispatcher() = Dispatchers.resetMain()
    private class Lane : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    private class Client : FakeClient() {
        var pairing: Result<String>? = null
        var pairStarts = 0
        var pairCancels = 0
        var closed = false
        val sent = mutableListOf<String>()
        override fun pair(bridge: BridgeInfo, code: String, clientId: String, result: Result<String>): Cancellation {
            pairStarts++
            pairing = result
            return Cancellation { pairCancels++ }
        }
        override fun playTrack(bridge: SecureStore.SavedBridge, trackId: String, collectionKind: String?,
            collectionId: String?, sequence: Long, result: Result<Boolean>): Cancellation {
            sent += "play:$sequence"
            return Cancellation.NONE
        }
        override fun command(bridge: SecureStore.SavedBridge, command: String, value: Double?,
            result: Result<Boolean>): Cancellation {
            sent += command
            return Cancellation.NONE
        }
        override fun close() { closed = true; super.close() }
    }

    private fun <T> unexpected() = object : BridgeClient.Result<T> {
        override fun success(value: T) = fail("Cancelled observer received success")
        override fun failure(message: String, unauthorized: Boolean) = fail(message)
    }

    @Test fun initializationDoesNotReadCredentialsUntilStorageWorkerRuns() {
        val crypto = CountingCrypto()
        val store = pairedStore(crypto)
        val before = crypto.decrypts
        val worker = Lane()
        val ui = Lane()
        BridgeRepository(Client(), store, worker, ui::execute).use { repository ->
            assertEquals(before, crypto.decrypts)
            assertFalse(repository.isInitialized)
            var ready = false
            repository.whenReady { ready = true }
            worker.drain()
            assertFalse(ready)
            assertNull(repository.current())
            ui.drain()
            assertTrue(ready)
            assertEquals("test-pc", repository.current()!!.id)
        }
    }

    @Test fun playIsDurableBeforeSendAndFollowingCommandCannotOvertakeIt() {
        val worker = Lane()
        val ui = Lane()
        val client = Client()
        val store = pairedStore()
        BridgeRepository(client, store, worker, ui::execute).use { repository ->
            worker.drain(); ui.drain()
            repository.playTrack("track", null, null, unexpected())
            repository.command("pause", null, unexpected())
            assertTrue(client.sent.isEmpty())
            worker.drain()
            assertTrue(client.sent.isEmpty())
            assertEquals(2L, store.nextPlaybackSequence()) // selection's 1 was committed
            ui.drain()
            assertEquals(listOf("play:1", "pause"), client.sent)
        }
    }

    @Test fun cancelledDeferredSelectionNeverReachesTransport() {
        val worker = Lane()
        val client = Client()
        val store = pairedStore()
        BridgeRepository(client, store, worker, Runnable::run).use { repository ->
            worker.drain()
            repository.playTrack("track", null, null, unexpected()).cancel()
            worker.drain()
            assertTrue(client.sent.isEmpty())
            assertEquals(1L, store.nextPlaybackSequence())
        }
    }

    @Test fun cancellationBeforeIdentityCommitDoesNotSendPairing() {
        val worker = Lane()
        val client = Client()
        BridgeRepository(client, pairedStore(), worker, Runnable::run).use { repository ->
            worker.drain()
            repository.pair(testBridge(), "123456", unexpected())
            repository.cancelPairing()
            worker.drain()
            assertEquals(0, client.pairStarts)
        }
    }

    @Test fun latePairingSuccessIsDurablyRevokedAfterObserverCancellation() {
        val client = Client()
        val store = SecureStore(MemoryBackend(), CountingCrypto())
        BridgeRepository(client, store).use { repository ->
            repository.pair(testBridge(), "123456", unexpected())
            repository.cancelPairing()
            assertEquals(0, client.pairCancels)
            client.pairing!!.success("issued-token-" + "b".repeat(40))
            assertNull(store.load())
            assertEquals(1, client.revocations)
            client.revocation!!.failure("Offline", false)
            assertEquals(1, repository.pendingRevocationCount())
            assertEquals("issued-token-" + "b".repeat(40), store.pendingRevocations().single().bridge.token)
        }
    }

    @Test fun cancellationDuringSaveRestoresPreviousComputerWithoutRevokingIt() {
        val worker = Lane()
        val ui = Lane()
        val store = pairedStore()
        val client = Client()
        BridgeRepository(client, store, worker, ui::execute).use { repository ->
            worker.drain(); ui.drain()
            val old = repository.current()!!
            val next = BridgeClient.BridgeInfo("other-pc", "Other", "192.168.1.21", 45832, "BB".repeat(32))
            repository.pair(next, "123456", unexpected())
            worker.drain(); ui.drain()
            client.pairing!!.success("issued-token-" + "b".repeat(40))
            worker.drain() // persisted, but not yet delivered on main
            repository.cancelPairing()
            ui.drain(); worker.drain(); ui.drain()
            assertEquals(old.token, store.load()!!.token)
            assertEquals("other-pc", store.pendingRevocations().single().bridge.id)
            client.revocation!!.success(true)
            worker.drain(); ui.drain()
            assertEquals(0, repository.pendingRevocationCount())
        }
    }

    @Test fun closeWaitsForAnIssuedCredentialToBeSettled() {
        val client = Client()
        val store = SecureStore(MemoryBackend(), CountingCrypto())
        val repository = BridgeRepository(client, store)
        repository.pair(testBridge(), "123456", unexpected())
        repository.close()
        assertFalse(client.closed)
        client.pairing!!.success("issued-token-" + "b".repeat(40))
        client.revocation!!.success(true)
        assertTrue(client.closed)
        assertEquals(0, store.pendingRevocationCount())
    }

    @Test fun settlingPairingAfterCloseCannotDeliverUnrelatedRequests() {
        val client = Client()
        val repository = BridgeRepository(client, pairedStore())
        repository.getLibrary("", 0, 60, object : BridgeRepository.PageResult<BridgeClient.LibraryPage> {
            override fun page(value: BridgeClient.LibraryPage, authoritative: Boolean) = fail("Late library success")
            override fun failure(message: String, unauthorized: Boolean) = fail(message)
        })
        repository.pair(testBridge(), "123456", unexpected())
        repository.close()
        assertFalse(client.closed)
        client.library!!.failure("Late library response", false)
        client.pairing!!.failure("Offline", false)
        assertTrue(client.closed)
    }

    @Test fun cancelledRepairCannotRestoreTheInvalidatedTokenForTheSameComputer() {
        val client = Client()
        val store = pairedStore()
        BridgeRepository(client, store).use { repository ->
            val vm = testViewModel(repository, TunesLinkUiState(
                route = TunesLinkRoute.Connected(TunesLinkDestination.Library),
                connection = ConnectionState.Connected("PC")))
            repository.pair(testBridge(), "123456", unexpected())
            repository.cancelPairing()
            client.pairing!!.success("new-token-" + "b".repeat(40))
            assertNull(repository.current())
            assertNull(store.load())
            assertEquals("new-token-" + "b".repeat(40), store.pendingRevocations().single().bridge.token)
            client.revocation!!.failure("Offline", false)
            assertEquals(TunesLinkRoute.Welcome, vm.mutableState.value.route)
            assertEquals(ConnectionState.Unpaired, vm.mutableState.value.connection)
            assertEquals(1, vm.mutableState.value.pendingRevocationCount)
        }
    }

    @Test fun failedSequenceCommitCannotSendPlayback() {
        val backend = MemoryBackend()
        var failWrites = false
        val store = SecureStore(object : SecureStore.Backend by backend {
            override fun commit(values: Map<String, Any>, removals: Set<String>) =
                if (failWrites) false else backend.commit(values, removals)
        }, CountingCrypto())
        store.save(testBridge(), "test-token-" + "a".repeat(40))
        val worker = Lane()
        val client = Client()
        BridgeRepository(client, store, worker, Runnable::run).use { repository ->
            worker.drain()
            failWrites = true
            var failed = false
            repository.playTrack("track", null, null, object : BridgeClient.Result<Boolean> {
                override fun success(value: Boolean) = fail("Uncommitted selection was sent")
                override fun failure(message: String, unauthorized: Boolean) { failed = true }
            })
            worker.drain()
            assertTrue(failed)
            assertTrue(client.sent.isEmpty())
        }
    }

    @Test fun aPairCannotRaceAnUncommittedForget() {
        val worker = Lane()
        val client = Client()
        BridgeRepository(client, pairedStore(), worker, Runnable::run).use { repository ->
            worker.drain()
            repository.forgetFromBridge(unexpected())
            var refused = false
            repository.pair(testBridge(), "123456", object : BridgeClient.Result<SecureStore.SavedBridge> {
                override fun success(value: SecureStore.SavedBridge) = fail("Overlapping pairing started")
                override fun failure(message: String, unauthorized: Boolean) { refused = true }
            })
            assertTrue(refused)
            worker.drain()
            assertNull(repository.current())
            assertEquals(0, client.pairStarts)
            assertEquals(1, repository.pendingRevocationCount())
        }
    }

    @Test fun permissionIsRecheckedAfterCredentialsFinishLoading() {
        val worker = Lane()
        val client = Client()
        BridgeRepository(client, pairedStore(), worker, Runnable::run).use { repository ->
            val vm = testViewModel(repository, state = null)
            var allowed = true
            vm.onForeground { allowed }
            assertEquals(0, client.starts)
            allowed = false
            worker.drain()
            assertEquals(0, client.starts)
            assertEquals(TunesLinkRoute.LocalNetworkPermission, vm.mutableState.value.route)
            vm.onBackground()
        }
    }

    @Test fun backgroundingDuringInitializationCannotStartANetworkConnection() {
        val worker = Lane()
        val client = Client()
        BridgeRepository(client, pairedStore(), worker, Runnable::run).use { repository ->
            val vm = testViewModel(repository, state = null)
            vm.onForeground()
            vm.onBackground()
            worker.drain()
            assertEquals(0, client.starts)
            vm.onForeground()
            assertEquals(1, client.starts)
            vm.onBackground()
        }
    }
}
