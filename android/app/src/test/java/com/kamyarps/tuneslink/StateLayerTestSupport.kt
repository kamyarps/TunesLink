package com.kamyarps.tuneslink

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle

internal const val TEST_FINGERPRINT = "AA"

internal fun testBridge(host: String = "192.168.1.20", name: String = "PC") =
    BridgeClient.BridgeInfo("test-pc", name, host, 45832, TEST_FINGERPRINT.repeat(32))

internal class CountingCrypto : SecureStore.Crypto {
    var decrypts = 0
    override fun encrypt(plaintext: ByteArray) = SecureStore.Envelope(plaintext.clone(), byteArrayOf(1))
    override fun decrypt(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        decrypts++
        return ciphertext.clone()
    }
}

internal class MemoryBackend : SecureStore.Backend {
    private val values = mutableMapOf<String, Any>()
    override fun getString(key: String, fallback: String?): String? = values[key] as? String ?: fallback
    override fun getInt(key: String, fallback: Int) = values[key] as? Int ?: fallback
    override fun commit(additions: Map<String, Any>, removals: Set<String>): Boolean {
        removals.forEach(values::remove)
        values.putAll(additions)
        return true
    }
}

internal fun pairedStore(crypto: CountingCrypto = CountingCrypto()) =
    SecureStore(MemoryBackend(), crypto).apply { save(testBridge(), "test-token-" + "a".repeat(40)) }

/** A transport that records requests and lets the test complete them. */
internal open class FakeClient : BridgeClient() {
    var listener: StateListener? = null
    var starts = 0
    var networkWakes = 0
    var discoveries = 0
    var discovery: Result<List<BridgeInfo>>? = null
    var verification: Result<BridgeInfo>? = null
    var manual: Result<BridgeInfo>? = null
    var revocations = 0
    var revocation: Result<Boolean>? = null
    var commands = mutableListOf<Pair<String, Double?>>()
    var commandCancels = 0
    var library: Result<LibraryPage>? = null
    var libraryOffset = -1
    var artwork: Result<Bitmap>? = null

    override fun startStateUpdates(bridge: SecureStore.SavedBridge, listener: StateListener) {
        starts++
        this.listener = listener
    }
    override fun stopStateUpdates() = Unit
    override fun requestStateRefresh() = Unit
    override fun networkAvailable() {
        networkWakes++
    }
    override fun discover(result: Result<List<BridgeInfo>>): Cancellation {
        discoveries++
        discovery = result
        return Cancellation.NONE
    }
    override fun verifyIdentity(candidate: BridgeInfo, expectedId: String, expectedFingerprint: String,
        result: Result<BridgeInfo>): Cancellation {
        verification = result
        return Cancellation.NONE
    }
    override fun resolveManual(input: String, result: Result<BridgeInfo>): Cancellation {
        manual = result
        return Cancellation.NONE
    }
    override fun revokePairing(bridge: SecureStore.SavedBridge, result: Result<Boolean>): Cancellation {
        revocations++
        revocation = result
        return Cancellation.NONE
    }
    override fun command(bridge: SecureStore.SavedBridge, command: String, value: Double?,
        result: Result<Boolean>): Cancellation {
        commands += command to value
        return Cancellation { commandCancels++ }
    }
    override fun getLibrary(bridge: SecureStore.SavedBridge, query: String, offset: Int, limit: Int,
        result: Result<LibraryPage>): Cancellation {
        library = result
        libraryOffset = offset
        return Cancellation.NONE
    }
    override fun getArtwork(bridge: SecureStore.SavedBridge, artworkId: String, size: Int,
        result: Result<Bitmap>): Cancellation {
        artwork = result
        return Cancellation.NONE
    }
}

internal fun updatesListener(
    onConnection: (Boolean, String?) -> Unit = { _, _ -> },
) = object : BridgeRepository.StateUpdatesListener {
    override fun state(state: BridgeClient.PlayerState) = Unit
    override fun connectionChanged(bridge: SecureStore.SavedBridge, connected: Boolean, message: String?) =
        onConnection(connected, message)
    override fun unauthorized(bridge: SecureStore.SavedBridge, message: String) = Unit
}

internal fun playerState(trackId: String = "track", position: Double = 10.0, artworkId: String = "art") =
    BridgeClient.PlayerState(true, true, "Song", "Artist", "Album", 200.0, position, 50,
        artworkId, trackId, false, "off")

internal class MemoryRecoveryStore : UiRecoveryStore {
    val values = mutableMapOf<String, String>()
    override fun getString(key: String): String? = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/** A monotonic clock the test advances explicitly. */
internal class FakeClock(var now: Long = 1_000_000L) : () -> Long {
    override fun invoke(): Long = now
}

internal val testFormatString: (Int, List<Any>) -> String = { res, arguments -> "resource:$res:$arguments" }

/**
 * Builds the real ViewModel through its constructor (so init and the SavedStateHandle restore
 * run) against an in-memory repository. [connectOnCreate] is off by default so a test controls
 * when a paired repository starts connecting; [state] then replaces the initial presentation.
 */
internal fun testViewModel(
    repository: BridgeRepository,
    state: TunesLinkUiState? = TunesLinkUiState(),
    savedStateHandle: SavedStateHandle = SavedStateHandle(),
    clock: FakeClock = FakeClock(),
    recovery: UiRecoveryStore = MemoryRecoveryStore(),
    connectOnCreate: Boolean = false,
): TunesLinkViewModel {
    val vm = TunesLinkViewModel(savedStateHandle, repository, recovery, testFormatString, clock, connectOnCreate)
    if (state != null) {
        vm.mutableState.value = state
        vm.editingQuery.value = state.library.editingQuery
        vm.restoreDestination = TunesLinkDestination.Library
    }
    return vm
}
