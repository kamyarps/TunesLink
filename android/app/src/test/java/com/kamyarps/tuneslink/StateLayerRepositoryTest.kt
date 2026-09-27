package com.kamyarps.tuneslink

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test

/** Transport and repository fixes from the 2026-09-27 state-layer pass. */
class StateLayerRepositoryTest {
    @Test fun reconnectsRetryPendingRevocationsOnceWithBackoffAndNoPerFrameDecrypt() {
        val crypto = CountingCrypto()
        val store = pairedStore(crypto)
        // Pairing a second computer queues the first one's revocation.
        store.save(BridgeClient.BridgeInfo("new-pc", "New", "192.168.1.30", 45832, "BB".repeat(32)),
            "new-token-" + "b".repeat(40))
        val client = FakeClient()
        BridgeRepository(client, store).use { repository ->
            repository.startStateUpdates(updatesListener())
            val decryptsBefore = crypto.decrypts
            client.listener!!.connectionChanged(true, null)
            assertEquals(1, client.revocations)
            client.revocation!!.failure("Offline", false)
            // Frames and counts never touch the keystore once the count is known.
            repeat(20) { repository.pendingRevocationCount() }
            client.listener!!.state(playerState())
            // A later reconnect inside the backoff window does not contact the old PC again.
            client.listener!!.connectionChanged(false, "Offline")
            client.listener!!.connectionChanged(true, null)
            assertEquals(1, client.revocations)
            assertEquals(1, crypto.decrypts - decryptsBefore)
        }
    }

    @Test fun pendingRevocationCountIsCachedAndRefreshedByWrites() {
        val crypto = CountingCrypto()
        val store = pairedStore(crypto)
        val before = crypto.decrypts
        repeat(5) { assertEquals(0, store.pendingRevocationCount()) }
        assertEquals(before, crypto.decrypts)
        store.prepareForget()
        assertEquals(1, store.pendingRevocationCount())
        val pending = store.pendingRevocations().single()
        store.removePendingRevocation(pending.revocationId)
        assertEquals(0, store.pendingRevocationCount())
    }

    @Test fun stoppingUpdatesCancelsRelocationAndALateResultDoesNotRestartTheStream() {
        val client = FakeClient()
        BridgeRepository(client, pairedStore()).use { repository ->
            repository.startStateUpdates(updatesListener())
            client.listener!!.connectionChanged(false, "Offline")
            val discovery = client.discovery!!
            repository.stopStateUpdates() // backgrounded
            discovery.success(listOf(testBridge("192.168.1.21")))
            client.verification?.success(testBridge("192.168.1.21"))
            assertEquals(1, client.starts)
            assertEquals("192.168.1.20", repository.current()!!.host)
        }
    }

    @Test fun relocationRestartsTheStreamWhileUpdatesAreWanted() {
        val client = FakeClient()
        BridgeRepository(client, pairedStore()).use { repository ->
            repository.startStateUpdates(updatesListener())
            client.listener!!.connectionChanged(false, "Offline")
            client.discovery!!.success(listOf(testBridge("192.168.1.21")))
            client.verification!!.success(testBridge("192.168.1.21"))
            assertEquals(2, client.starts)
            assertEquals("192.168.1.21", repository.current()!!.host)
        }
    }

    @Test fun networkAvailabilityWakesTheStreamAndRelocatesOnlyAfterAFailure() {
        val client = FakeClient()
        BridgeRepository(client, pairedStore()).use { repository ->
            repository.startStateUpdates(updatesListener())
            repository.networkAvailable()
            assertEquals(1, client.networkWakes)
            assertEquals(0, client.discoveries)
            client.listener!!.connectionChanged(false, "Offline")
            assertEquals(1, client.discoveries)
            repository.networkAvailable() // one relocation stays in flight
            assertEquals(1, client.discoveries)
            client.discovery!!.failure("Nothing found", false)
            repository.networkAvailable()
            assertEquals(2, client.discoveries)
            assertEquals(3, client.networkWakes)
            repository.stopStateUpdates()
            repository.networkAvailable()
            assertEquals(3, client.networkWakes)
        }
    }

    @Test fun anArtworkObserverMayCancelAnotherDuringDelivery() {
        val client = FakeClient()
        BridgeRepository(client, pairedStore()).use { repository ->
            var second: BridgeRepository.RequestHandle = BridgeRepository.RequestHandle.NONE
            var delivered = 0
            repository.getArtwork("art", 900, object : BridgeClient.Result<Bitmap> {
                override fun success(value: Bitmap?) {
                    delivered++
                    second.cancel()
                }
                override fun failure(message: String, unauthorized: Boolean) = fail(message)
            })
            second = repository.getArtwork("art", 900, object : BridgeClient.Result<Bitmap> {
                override fun success(value: Bitmap?) { delivered++ }
                override fun failure(message: String, unauthorized: Boolean) = fail(message)
            })
            client.artwork!!.success(null)
            assertEquals(1, delivered)
        }
    }

    @Test fun commandsReturnACancellableHandle() {
        val client = FakeClient()
        BridgeRepository(client, pairedStore()).use { repository ->
            val handle = repository.command("volume", 20.0, object : BridgeClient.Result<Boolean> {
                override fun success(value: Boolean) = Unit
                override fun failure(message: String, unauthorized: Boolean) = Unit
            })
            handle.cancel()
            assertEquals(listOf("volume" to 20.0), client.commands)
            assertEquals(1, client.commandCancels)
        }
    }

    @Test fun typedBridgeFailuresReachPageObservers() {
        val client = FakeClient()
        BridgeRepository(client, pairedStore()).use { repository ->
            var code = BridgeClient.ErrorCode.NONE
            repository.getLibrary("", 0, 60, object : BridgeRepository.PageResult<BridgeClient.LibraryPage> {
                override fun page(value: BridgeClient.LibraryPage, authoritative: Boolean) = fail()
                override fun failure(message: String, unauthorized: Boolean) = fail(message)
                override fun failure(message: String, unauthorized: Boolean, received: BridgeClient.ErrorCode) {
                    code = received
                }
            })
            client.library!!.failure("iTunes is busy", false, BridgeClient.ErrorCode.ITUNES_BUSY)
            assertEquals(BridgeClient.ErrorCode.ITUNES_BUSY, code)
        }
    }

    @Test fun wireErrorCodesParseAndUnknownCodesFallBack() {
        val codes = mapOf(
            "pairing_rate_limited" to BridgeClient.ErrorCode.PAIRING_RATE_LIMITED,
            "pairing_closed" to BridgeClient.ErrorCode.PAIRING_CLOSED,
            "pairing_code_incorrect" to BridgeClient.ErrorCode.PAIRING_CODE_INCORRECT,
            "pairing_device_limit" to BridgeClient.ErrorCode.PAIRING_DEVICE_LIMIT,
            "pairing_save_failed" to BridgeClient.ErrorCode.PAIRING_SAVE_FAILED,
            "busy" to BridgeClient.ErrorCode.BUSY,
            "itunes_busy" to BridgeClient.ErrorCode.ITUNES_BUSY,
            "itunes_unavailable" to BridgeClient.ErrorCode.ITUNES_UNAVAILABLE,
            "not_found" to BridgeClient.ErrorCode.NOT_FOUND,
            "invalid_request" to BridgeClient.ErrorCode.INVALID_REQUEST,
            "not_paired" to BridgeClient.ErrorCode.NOT_PAIRED,
            "superseded" to BridgeClient.ErrorCode.SUPERSEDED,
            "bridge_error" to BridgeClient.ErrorCode.BRIDGE_ERROR,
        )
        codes.forEach { (wire, code) -> assertEquals(code, BridgeClient.ErrorCode.fromWire(wire)) }
        assertEquals(BridgeClient.ErrorCode.NONE, BridgeClient.ErrorCode.fromWire(null))
        assertEquals(BridgeClient.ErrorCode.NONE, BridgeClient.ErrorCode.fromWire("something_new"))
        assertEquals(BridgeClient.ErrorCode.ITUNES_BUSY, BridgeClient.codeOf(
            BridgeClient.BridgeFailure("x", BridgeClient.ErrorCode.ITUNES_BUSY)))
        assertEquals(BridgeClient.ErrorCode.NONE, BridgeClient.codeOf(java.io.IOException("timed out")))
    }

    @Test fun onlyMissingStreamRoutesFallBackToPolling() {
        val sse = "text/event-stream; charset=utf-8"
        assertNull(BridgeClient.streamStatusFor(200, sse))
        assertEquals(BridgeClient.StreamStatus.UNAUTHORIZED, BridgeClient.streamStatusFor(401, "application/json"))
        assertEquals(BridgeClient.StreamStatus.UNSUPPORTED, BridgeClient.streamStatusFor(404, "application/json"))
        assertEquals(BridgeClient.StreamStatus.UNSUPPORTED, BridgeClient.streamStatusFor(405, null))
        assertEquals(BridgeClient.StreamStatus.UNSUPPORTED, BridgeClient.streamStatusFor(200, "application/json"))
        assertEquals(BridgeClient.StreamStatus.DISCONNECTED, BridgeClient.streamStatusFor(500, "application/json"))
        assertEquals(BridgeClient.StreamStatus.DISCONNECTED, BridgeClient.streamStatusFor(503, null))
    }

    @Test fun blankComputerNamesStayBlankForLocalizedDisplay() {
        assertEquals("", BridgeClient.safeBridgeName(null))
        assertEquals("Studio PC", BridgeClient.safeBridgeName(" Studio PC\n"))
    }
}
