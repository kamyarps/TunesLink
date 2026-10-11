package com.kamyarps.tuneslink

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
class CommandAcknowledgementTest {
    @Before fun setup() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun cleanup() = Dispatchers.resetMain()

    @Test fun earlyPreviousFrameCannotConfirmOrHideARejectedRequest() {
        exercisePrevious(accept = false)
    }

    @Test fun previousNeedsAcceptanceAndAFollowingStateFrame() {
        exercisePrevious(accept = true)
    }

    private fun exercisePrevious(accept: Boolean) {
        var completion: BridgeClient.Result<Boolean>? = null
        var onRefresh: (() -> Unit)? = null
        var refreshes = 0
        val client = object : FakeClient() {
            override fun requestStateRefresh() {
                refreshes++
                onRefresh?.invoke()
            }
            override fun command(bridge: SecureStore.SavedBridge, command: String, value: Double?,
                                 result: Result<Boolean>): Cancellation {
                completion = result
                return Cancellation.NONE
            }
        }
        val repository = BridgeRepository(client, pairedStore())
        try {
            val vm = testViewModel(repository, TunesLinkUiState(
                connection = ConnectionState.Connected("PC"),
                player = PlayerUiState(trackId = "track", title = "Song", position = 1.0)))
            vm.previous()
            val frame = playerState(trackId = "track", position = 1.5, artworkId = "")
            // The repository requests refresh before forwarding HTTP success to the ViewModel.
            // Even a synchronously delivered frame at that boundary must not settle the command.
            onRefresh = { vm.applyAuthoritativeState(frame) }
            vm.applyAuthoritativeState(frame)
            assertNotNull(vm.mutableState.value.player.pending(PlaybackAction.Previous))
            assertFalse(vm.mutableState.value.announcement?.hapticIntent == HapticIntent.Confirm)
            if (accept) {
                completion!!.success(true)
                assertEquals(1, refreshes)
                assertTrue(vm.mutableState.value.player.pending(PlaybackAction.Previous)!!.requestSucceeded)
                // Acceptance alone must not reuse the pre-request frame as confirmation.
                assertNotNull(vm.mutableState.value.player.pending(PlaybackAction.Previous))
                vm.applyAuthoritativeState(frame)
                assertNull(vm.mutableState.value.player.pending(PlaybackAction.Previous))
                assertEquals(HapticIntent.Confirm, vm.mutableState.value.announcement!!.hapticIntent)
            } else {
                completion!!.failure("iTunes rejected Previous", false, BridgeClient.ErrorCode.ITUNES_BUSY)
                assertNull(vm.mutableState.value.player.pending(PlaybackAction.Previous))
                assertEquals(HapticIntent.Reject, vm.mutableState.value.announcement!!.hapticIntent)
                assertEquals(R.string.error_itunes_busy, vm.mutableState.value.announcement!!.messageRes)
                assertEquals("resource:${R.string.error_itunes_busy}:[]", vm.mutableState.value.player.commandError)
                assertEquals(1.5, vm.mutableState.value.player.position, 0.0)
            }
        } finally {
            repository.close()
        }
    }

    @Test fun everyMutationWaitsForHttpAcceptanceEvenWhenAFrameAlreadyMatches() {
        val state = playerState(position = 1.0)
        for (action in PlaybackAction.entries) {
            val mutation = PendingMutation(1, action, action.playerFields(),
                expectedBoolean = action == PlaybackAction.PlayPause,
                expectedNumber = if (action == PlaybackAction.Volume) 50.0 else 1.0,
                expectedRepeat = RepeatMode.Off, expectedTrackId = "track", previousTrackId = "old",
                startedAtMillis = 0)
            assertFalse(action.name, mutation.matches(state))
            assertTrue(action.name, mutation.copy(requestSucceeded = true).matches(state))
        }
    }
}
