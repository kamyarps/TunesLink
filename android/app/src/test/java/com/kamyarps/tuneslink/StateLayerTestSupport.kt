package com.kamyarps.tuneslink

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.MutableStateFlow
import sun.misc.Unsafe

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

/**
 * Builds the real ViewModel without Android construction so its reducers and flows run against
 * an in-memory repository. Only flows that do not read string resources can run this way.
 */
internal fun testViewModel(
    repository: BridgeRepository,
    state: TunesLinkUiState = TunesLinkUiState(),
): TunesLinkViewModel {
    val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
    val unsafe = unsafeField.get(null) as Unsafe
    val vm = unsafe.allocateInstance(TunesLinkViewModel::class.java) as TunesLinkViewModel
    // Resource lookups on this stub application return null; flows under test avoid them.
    val application = unsafe.allocateInstance(TunesLinkApplication::class.java)
    var type: Class<*>? = TunesLinkViewModel::class.java.superclass
    while (type != null) {
        type.declaredFields.filter { Application::class.java.isAssignableFrom(it.type) }.forEach {
            it.isAccessible = true
            it.set(vm, application)
        }
        type = type.superclass
    }
    for (field in TunesLinkViewModel::class.java.declaredFields) {
        field.isAccessible = true
        when (field.type) {
            BridgeRepository.RequestHandle::class.java -> field.set(vm, BridgeRepository.RequestHandle.NONE)
            Map::class.java -> field.set(vm, LinkedHashMap<Any, Any>())
        }
    }
    fun set(name: String, value: Any?) = TunesLinkViewModel::class.java.getDeclaredField(name).run {
        isAccessible = true
        set(vm, value)
    }
    set("mutableState", MutableStateFlow(state))
    set("savedStateHandle", SavedStateHandle())
    set("repository", repository)
    set("editingQuery", MutableStateFlow(state.library.editingQuery))
    set("restoreDestination", TunesLinkDestination.Library)
    set("artworkSession", MutableStateFlow(0L))
    set("artworkFailureId", "")
    return vm
}

internal fun TunesLinkViewModel.field(name: String): Any? =
    TunesLinkViewModel::class.java.getDeclaredField(name).run {
        isAccessible = true
        get(this@field)
    }

internal fun TunesLinkViewModel.setField(name: String, value: Any?) =
    TunesLinkViewModel::class.java.getDeclaredField(name).run {
        isAccessible = true
        set(this@setField, value)
    }
