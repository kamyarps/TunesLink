package com.kamyarps.tuneslink

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** The real constructor, init and SavedStateHandle restore, as after process death. */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelRestoreTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun mainDispatcher() = Dispatchers.setMain(dispatcher)
    @After fun resetDispatcher() = Dispatchers.resetMain()

    @Test fun processDeathRestoresDestinationModalAndInputs() {
        val client = FakeClient()
        val repository = BridgeRepository(client, pairedStore())
        val saved = SavedStateHandle(mapOf(
            "destination" to "Search",
            "modal" to "privacy",
            "modal_return" to "connection",
            "switching_computer" to true,
            "manual_address" to "192.168.1.9",
            TunesLinkViewModel.KEY_EDITING_QUERY to "abba",
        ))
        val vm = testViewModel(repository, state = null, savedStateHandle = saved, connectOnCreate = true)
        val state = vm.mutableState.value
        assertEquals(TunesLinkDestination.Search, state.navigation.destination)
        assertEquals(TunesLinkModal.Privacy, state.modal)
        assertEquals(TunesLinkModal.ConnectionDetails, state.modalPresentation?.returnTo)
        assertEquals("192.168.1.9", state.manualAddress)
        assertEquals("abba", state.library.editingQuery)
        assertEquals("abba", vm.editingQuery.value)
        // Choosing another computer survives too: offer the way back instead of reconnecting.
        assertTrue(state.navigation.switchingComputer)
        assertEquals("PC", state.returnComputer)
        assertEquals(0, client.starts)
        repository.close()
    }

    @Test fun aPairedPhoneReconnectsOnCreate() {
        val client = FakeClient()
        val repository = BridgeRepository(client, pairedStore())
        val vm = testViewModel(repository, state = null, connectOnCreate = true)
        assertEquals(1, client.starts)
        assertEquals("PC", vm.mutableState.value.bridgeName)
        repository.close()
    }

    @Test fun pcSetupGuideOpensAndSurvivesProcessDeath() {
        val repository = BridgeRepository(FakeClient(), SecureStore(MemoryBackend(), CountingCrypto()))
        val vm = testViewModel(repository, state = null)
        vm.showPcSetup()
        assertEquals(TunesLinkModal.PcSetup, vm.mutableState.value.modal)

        val restored = testViewModel(
            repository,
            state = null,
            savedStateHandle = SavedStateHandle(mapOf("modal" to "pc-setup")),
        )
        assertEquals(TunesLinkModal.PcSetup, restored.mutableState.value.modal)
        repository.close()
    }

    @Test fun unknownSavedValuesFallBackSafely() {
        val repository = BridgeRepository(FakeClient(), SecureStore(MemoryBackend(), CountingCrypto()))
        val saved = SavedStateHandle(mapOf("destination" to "Removed", "modal" to "gone"))
        val vm = testViewModel(repository, state = null, savedStateHandle = saved)
        assertEquals(TunesLinkDestination.Library, vm.mutableState.value.navigation.destination)
        assertNull(vm.mutableState.value.modal)
        repository.close()
    }

    @Test fun aPermissionContinuationSurvivesInTheRecoveryStore() {
        val repository = BridgeRepository(FakeClient(), SecureStore(MemoryBackend(), CountingCrypto()))
        val recovery = MemoryRecoveryStore().apply { values["pending_permission_action"] = "Discover" }
        val vm = testViewModel(repository, state = null, recovery = recovery)
        assertEquals(PendingPermissionAction.Discover, vm.pendingPermissionAction)
        vm.localNetworkPermissionResult(granted = false)
        assertNull(vm.pendingPermissionAction)
        assertFalse("pending_permission_action" in recovery.values)
        repository.close()
    }

    @Test fun pairingCooldownCountsDownWithTheMonotonicClock() {
        val repository = BridgeRepository(FakeClient(), SecureStore(MemoryBackend(), CountingCrypto()))
        val clock = FakeClock()
        val bridge = testBridge()
        val saved = SavedStateHandle(mapOf(
            "pairingCooldownEndpoint" to "${bridge.host}:${bridge.port}",
            "pairingCooldownUntil" to clock.now + 5_000L,
        ))
        val vm = testViewModel(repository, savedStateHandle = saved, clock = clock)
        vm.chooseBridge(bridge)
        assertEquals(5, vm.mutableState.value.pairing.retryAfterSeconds)

        clock.now += 3_000L
        dispatcher.scheduler.advanceTimeBy(1_001L)
        assertEquals(2, vm.mutableState.value.pairing.retryAfterSeconds)

        clock.now += 2_000L
        dispatcher.scheduler.advanceTimeBy(1_001L)
        assertEquals(0, vm.mutableState.value.pairing.retryAfterSeconds)
        repository.close()
    }

    @Test fun aCooldownForAnotherComputerDoesNotApply() {
        val repository = BridgeRepository(FakeClient(), SecureStore(MemoryBackend(), CountingCrypto()))
        val clock = FakeClock()
        val saved = SavedStateHandle(mapOf(
            "pairingCooldownEndpoint" to "192.168.1.99:45832",
            "pairingCooldownUntil" to clock.now + 60_000L,
        ))
        val vm = testViewModel(repository, savedStateHandle = saved, clock = clock)
        vm.chooseBridge(testBridge())
        assertEquals(0, vm.mutableState.value.pairing.retryAfterSeconds)
        repository.close()
    }

    @Test fun aBlockedPermissionOffersSettingsUntilAccessIsGranted() {
        val repository = BridgeRepository(FakeClient(), SecureStore(MemoryBackend(), CountingCrypto()))
        val vm = testViewModel(repository, TunesLinkUiState(route = TunesLinkRoute.LocalNetworkPermission))
        vm.pendingPermissionAction = PendingPermissionAction.Discover
        vm.localNetworkPermissionResult(granted = false, blocked = true)
        val blocked = vm.mutableState.value
        assertTrue(blocked.localNetworkBlocked)
        assertEquals(TunesLinkRoute.Welcome, blocked.route)
        assertEquals("resource:${R.string.local_network_blocked_error}:[]", blocked.discoveryError)

        // The user allowed it in Settings and came back.
        assertTrue(vm.ensureLocalNetworkPermission(hasAccess = true))
        assertFalse(vm.mutableState.value.localNetworkBlocked)
        assertNull(vm.mutableState.value.discoveryError)
        repository.close()
    }

    @Test fun aFirstDenialKeepsTheSystemPromptPath() {
        val repository = BridgeRepository(FakeClient(), SecureStore(MemoryBackend(), CountingCrypto()))
        val vm = testViewModel(repository, TunesLinkUiState(route = TunesLinkRoute.LocalNetworkPermission))
        vm.localNetworkPermissionResult(granted = false, blocked = false)
        assertFalse(vm.mutableState.value.localNetworkBlocked)
        assertEquals("resource:${R.string.local_network_denied_error}:[]", vm.mutableState.value.discoveryError)
        repository.close()
    }
}
