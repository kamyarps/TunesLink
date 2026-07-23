package com.kamyarps.tuneslink

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.core.content.edit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the complete mobile presentation model. The process-scoped repository remains responsible
 * for the unchanged TunesLink-3 transport and encrypted credential storage.
 */
internal class TunesLinkViewModel(
    application: Application,
    internal val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    internal val repository = (application as TunesLinkApplication).session().repository
    private val uiRecovery = application.getSharedPreferences(UI_RECOVERY_PREFERENCES, 0)
    internal val mutableState = MutableStateFlow(
        TunesLinkUiState(
            manualAddress = savedStateHandle[KEY_MANUAL_ADDRESS] ?: "",
            library = LibraryUiState(editingQuery = savedStateHandle[KEY_EDITING_QUERY] ?: ""),
            navigation = NavigationState(
                destination = savedDestination(savedStateHandle[KEY_DESTINATION]),
                switchingComputer = savedStateHandle[KEY_SWITCHING_COMPUTER] ?: false,
            ),
            modalPresentation = restoredModalPresentation(
                savedStateHandle[KEY_MODAL],
                savedStateHandle[KEY_MODAL_RETURN],
            ),
            pendingRevocationCount = repository.pendingRevocationCount(),
            discoveryError = if (repository.secureStorageRequiresRecovery()) {
                application.getString(R.string.secure_storage_recovery_required)
            } else {
                null
            },
        ),
    )
    val state: StateFlow<TunesLinkUiState> = mutableState.asStateFlow()

    internal val editingQuery = MutableStateFlow(savedStateHandle[KEY_EDITING_QUERY] ?: "")
    internal var libraryGeneration = 0
    internal var browseGeneration = 0
    private var connectedOnce = false
    private var stateUpdatesActive = false
    internal var artworkRequest: BridgeRepository.RequestHandle = BridgeRepository.RequestHandle.NONE
    internal var libraryRequest: BridgeRepository.RequestHandle = BridgeRepository.RequestHandle.NONE
    internal var browseRequest: BridgeRepository.RequestHandle = BridgeRepository.RequestHandle.NONE
    private var restoreDestination = mutableState.value.navigation.destination
    private var pendingPermissionAction: PendingPermissionAction? = savedStateHandle
        .get<String>(KEY_PENDING_PERMISSION_ACTION)
        .orEmpty()
        .ifBlank { uiRecovery.getString(KEY_PENDING_PERMISSION_ACTION, null).orEmpty() }
        .let { runCatching { PendingPermissionAction.valueOf(it) }.getOrNull() }
    internal val mutationTimeoutJobs = mutableMapOf<Long, Job>()
    internal var nextOperationId = 0L
    private var nextAnnouncementId = 0L
    internal var latestAuthoritativeState: BridgeClient.PlayerState? = null
    private var discoveryRequest: BridgeRepository.RequestHandle = BridgeRepository.RequestHandle.NONE
    private var manualRequest: BridgeRepository.RequestHandle = BridgeRepository.RequestHandle.NONE
    private var relocationRequest: BridgeRepository.RequestHandle = BridgeRepository.RequestHandle.NONE
    private var discoveryGeneration = 0L
    private var manualGeneration = 0L
    private var pairingGeneration = 0L
    private var relocationGeneration = 0L
    private var resetAfterModalDismiss = false
    private var resetAnnouncement: UiAnnouncement? = null
    private var lastAvailabilityKind: ConnectionAvailabilityKind? = null

    init {
        viewModelScope.launch {
            editingQuery.committedSearchQueries().collectLatest { commitSearch(it) }
        }
        repository.current()?.takeUnless { mutableState.value.navigation.switchingComputer }?.let { saved ->
            updateBridge(saved.name, saved.host, saved.port)
            connect(initial = true)
        }
    }

    fun onForeground() {
        mutableState.update { it.copy(pendingRevocationCount = repository.pendingRevocationCount()) }
        if (repository.pendingRevocationCount() > 0) repository.retryPendingRevocations(null)
        if (!mutableState.value.navigation.switchingComputer && repository.current() != null) {
            if (stateUpdatesActive) repository.requestStateRefresh()
            else connect(initial = false)
        }
    }

    fun ensureLocalNetworkPermission(hasAccess: Boolean): Boolean {
        val savedBridge = repository.current()
        val hasPendingRevocations = repository.pendingRevocationCount() > 0
        if (hasAccess || (savedBridge == null && !hasPendingRevocations)) return true
        setPendingPermissionAction(
            if (savedBridge == null) PendingPermissionAction.RetryRevocations
            else PendingPermissionAction.Reconnect,
        )
        stateUpdatesActive = false
        repository.stopStateUpdates()
        mutableState.update {
            it.copy(
                route = TunesLinkRoute.LocalNetworkPermission,
                connection = savedBridge?.let { bridge ->
                    ConnectionState.RecoverableFailure(bridge.name)
                } ?: ConnectionState.Unpaired,
            )
        }
        return false
    }

    fun onBackground() {
        cancelTransientOperations()
    }

    fun requestDiscovery(hasLocalNetworkAccess: Boolean) {
        if (!hasLocalNetworkAccess) {
            setPendingPermissionAction(PendingPermissionAction.Discover)
            mutableState.update { it.copy(route = TunesLinkRoute.LocalNetworkPermission) }
            return
        }
        discover()
    }

    fun requestManualAddress(hasLocalNetworkAccess: Boolean) {
        if (!hasLocalNetworkAccess) {
            setPendingPermissionAction(PendingPermissionAction.ManualAddress)
            mutableState.update { it.copy(route = TunesLinkRoute.LocalNetworkPermission) }
            return
        }
        openManualAddress()
    }

    fun localNetworkPermissionResult(granted: Boolean) {
        Log.d(NAVIGATION_TAG, "local-network-result granted=$granted pending=$pendingPermissionAction")
        if (!granted) {
            mutableState.update {
                it.copy(
                    route = TunesLinkRoute.Welcome,
                    discoveryError = getApplication<Application>().getString(R.string.local_network_denied_error),
                )
            }
            setPendingPermissionAction(null)
            announce(R.string.local_network_denied_announcement, haptic = HapticIntent.Reject)
            return
        }
        val action = pendingPermissionAction
        mutableState.update { it.copy(route = TunesLinkRoute.Welcome, discoveryError = null) }
        viewModelScope.launch {
            // Present app UI on the next settled frame after the system permission
            // window closes. This prevents the grant gesture from dismissing a
            // newly-created modal on API 37 while keeping the continuation durable.
            delay(120)
            when (action) {
                PendingPermissionAction.Discover -> discover()
                PendingPermissionAction.ManualAddress -> openManualAddress()
                PendingPermissionAction.Reconnect -> connect(initial = false)
                PendingPermissionAction.RetryRevocations -> retryPendingRevocations()
                null -> Unit
            }
            Log.d(NAVIGATION_TAG, "local-network-continuation completed=$action")
            setPendingPermissionAction(null)
        }
    }

    fun discover() {
        repository.cancelDiscovery()
        discoveryRequest.cancel()
        val generation = ++discoveryGeneration
        mutableState.update {
            it.copy(
                route = TunesLinkRoute.Welcome,
                connection = ConnectionState.Discovering,
                discoveryError = null,
                discovered = emptyList(),
            )
        }
        discoveryRequest = repository.discover(object : BridgeClient.Result<List<BridgeClient.BridgeInfo>> {
            override fun success(value: List<BridgeClient.BridgeInfo>) {
                if (generation != discoveryGeneration || mutableState.value.modal != null) return
                when (value.size) {
                    0 -> mutableState.update {
                        it.copy(
                            connection = ConnectionState.Unpaired,
                            discoveryError = getApplication<Application>().getString(R.string.no_computer_found_detail),
                        )
                    }
                    1 -> openPairing(value.first())
                    else -> mutableState.update {
                        it.copy(connection = ConnectionState.Unpaired, discovered = value)
                    }
                }
            }

            override fun failure(message: String, unauthorized: Boolean) {
                if (generation != discoveryGeneration) return
                mutableState.update {
                    it.copy(connection = ConnectionState.Unpaired, discoveryError = localizedFailure(message))
                }
            }
        })
    }

    fun chooseBridge(bridge: BridgeClient.BridgeInfo) = openPairing(bridge)

    fun openManualAddress() {
        repository.cancelDiscovery()
        discoveryRequest.cancel()
        discoveryGeneration++
        Log.d(NAVIGATION_TAG, "present manual-address")
        setModalPresentation(ModalPresentation(TunesLinkModal.ManualAddress))
        mutableState.update { it.copy(manualAddressError = null) }
    }

    fun updateManualAddress(value: String) {
        val retained = value.take(MAX_ADDRESS_LENGTH)
        savedStateHandle[KEY_MANUAL_ADDRESS] = retained
        mutableState.update {
            it.copy(manualAddress = retained, manualAddressError = manualAddressError(retained))
        }
    }

    fun resolveManualAddress() {
        if (mutableState.value.manualResolutionBusy) return
        val input = mutableState.value.manualAddress.trim()
        val validation = manualAddressError(input)
        if (validation != null) {
            mutableState.update { it.copy(manualAddressError = validation) }
            return
        }
        repository.cancelManualResolution()
        manualRequest.cancel()
        val generation = ++manualGeneration
        mutableState.update { it.copy(manualAddressError = null, manualResolutionBusy = true) }
        manualRequest = repository.resolveManual(input, object : BridgeClient.Result<BridgeClient.BridgeInfo> {
            override fun success(value: BridgeClient.BridgeInfo) {
                if (generation != manualGeneration) return
                mutableState.update { it.copy(manualResolutionBusy = false) }
                openPairing(value)
            }
            override fun failure(message: String, unauthorized: Boolean) {
                if (generation != manualGeneration) return
                mutableState.update {
                    it.copy(manualAddressError = localizedFailure(message), manualResolutionBusy = false)
                }
            }
        })
    }

    fun updatePairingCode(value: String) {
        mutableState.update {
            it.copy(
                pairing = it.pairing.copy(
                    code = value.filter(Char::isDigit).take(PAIRING_CODE_LENGTH),
                    codeError = null,
                    phase = PairingPhase.Editing,
                ),
            )
        }
    }

    fun pair() {
        val snapshot = mutableState.value
        val bridge = (snapshot.modal as? TunesLinkModal.Pairing)?.bridge ?: return
        when {
            snapshot.pairing.code.length != PAIRING_CODE_LENGTH -> {
                mutableState.update {
                    it.copy(
                        pairing = it.pairing.copy(
                            codeError = getApplication<Application>().getString(
                                R.string.pairing_code_incomplete,
                            ),
                        ),
                    )
                }
                return
            }
        }
        mutableState.update {
            it.copy(
                connection = ConnectionState.Pairing,
                pairing = it.pairing.copy(
                    phase = PairingPhase.Submitting,
                    codeError = null,
                ),
            )
        }
        repository.cancelPairing()
        val generation = ++pairingGeneration
        repository.pair(bridge, snapshot.pairing.code, object : BridgeClient.Result<SecureStore.SavedBridge> {
            override fun success(value: SecureStore.SavedBridge) {
                if (generation != pairingGeneration) return
                updateBridge(value.name, value.host, value.port)
                mutableState.update {
                    it.copy(
                        pairing = it.pairing.copy(phase = PairingPhase.Success),
                        library = LibraryUiState(editingQuery = it.library.editingQuery),
                        browse = LibraryBrowseUiState(),
                        player = PlayerUiState(),
                        navigation = it.navigation.copy(switchingComputer = false),
                    )
                }
                savedStateHandle[KEY_SWITCHING_COMPUTER] = false
                announce(
                    R.string.paired_securely_with,
                    listOf(value.name),
                    HapticIntent.Confirm,
                )
                connect(initial = true)
                requestModalDismiss()
            }

            override fun failure(message: String, unauthorized: Boolean) {
                if (generation != pairingGeneration) return
                mutableState.update {
                    it.copy(
                        connection = ConnectionState.Unpaired,
                        pairing = it.pairing.copy(
                            phase = PairingPhase.Editing,
                            codeError = localizedFailure(message, R.string.error_pairing_code),
                        ),
                    )
                }
                announce(R.string.pairing_failed, haptic = HapticIntent.Reject)
            }
        })
    }

    fun requestModalDismiss() {
        cancelOperationForModal(mutableState.value.modal)
        Log.d(NAVIGATION_TAG, "request modal dismiss=${mutableState.value.modal?.javaClass?.simpleName}")
        mutableState.update { current ->
            val presentation = current.modalPresentation ?: return@update current
            current.copy(modalPresentation = presentation.copy(dismissRequested = true))
        }
    }

    fun completeModalDismiss() {
        if (resetAfterModalDismiss) {
            resetAfterModalDismiss = false
            val retainedAddress = mutableState.value.manualAddress
            mutableState.value = TunesLinkUiState(
                manualAddress = retainedAddress,
                announcement = resetAnnouncement,
                pendingRevocationCount = repository.pendingRevocationCount(),
            )
            resetAnnouncement = null
            savedStateHandle[KEY_SWITCHING_COMPUTER] = false
            savedStateHandle[KEY_DESTINATION] = TunesLinkDestination.Library.name
            savedStateHandle[KEY_MODAL] = null
            savedStateHandle[KEY_MODAL_RETURN] = null
            return
        }
        val presentation = mutableState.value.modalPresentation
        val next = presentation?.replacement ?: presentation?.returnTo?.let(::ModalPresentation)
        publishModalPresentation(next)
        mutableState.update {
            it.copy(
                pairing = if (next == null) PairingUiState() else it.pairing,
                manualAddressError = null,
            )
        }
    }

    fun closeModal() = requestModalDismiss()

    fun showConnectionDetails() {
        setModalPresentation(ModalPresentation(TunesLinkModal.ConnectionDetails))
    }

    fun showPrivacy() {
        val parent = if (mutableState.value.modal == TunesLinkModal.ConnectionDetails) {
            TunesLinkModal.ConnectionDetails
        } else null
        setModalPresentation(ModalPresentation(TunesLinkModal.Privacy, returnTo = parent))
    }

    fun requestForget() {
        setModalPresentation(
            ModalPresentation(
                TunesLinkModal.ForgetConfirmation,
                returnTo = TunesLinkModal.ConnectionDetails,
            ),
        )
    }

    fun forget() {
        if (mutableState.value.forgetBusy) return
        mutableState.update { it.copy(forgetBusy = true, forgetError = null) }
        repository.forgetFromBridge(object : BridgeClient.Result<Boolean> {
            override fun success(value: Boolean) {
                finishLocalForget(
                    R.string.computer_forgotten,
                    HapticIntent.Confirm,
                )
            }

            override fun failure(message: String, unauthorized: Boolean) {
                if (repository.current() != null) {
                    mutableState.update {
                        it.copy(forgetBusy = false, forgetError = localizedFailure(message))
                    }
                    announce(R.string.forget_failed, haptic = HapticIntent.Reject)
                } else {
                    finishLocalForget(
                        R.string.computer_removed_revocation_pending,
                        HapticIntent.Reject,
                    )
                }
            }
        })
    }

    fun retryPendingRevocations() {
        if (mutableState.value.revocationRetryBusy || repository.pendingRevocationCount() == 0) return
        mutableState.update { it.copy(revocationRetryBusy = true, forgetError = null) }
        repository.retryPendingRevocations(object : BridgeClient.Result<Int> {
            override fun success(value: Int) {
                mutableState.update {
                    it.copy(
                        revocationRetryBusy = false,
                        pendingRevocationCount = repository.pendingRevocationCount(),
                    )
                }
                announce(R.string.revocation_retry_complete, haptic = HapticIntent.Confirm)
            }

            override fun failure(message: String, unauthorized: Boolean) {
                mutableState.update {
                    it.copy(
                        revocationRetryBusy = false,
                        pendingRevocationCount = repository.pendingRevocationCount(),
                        forgetError = localizedFailure(message, R.string.error_revocation_pending),
                    )
                }
                announce(R.string.revocation_retry_failed, haptic = HapticIntent.Reject)
            }
        })
    }

    fun requestRetryPendingRevocations(hasLocalNetworkAccess: Boolean) {
        if (!hasLocalNetworkAccess) {
            setPendingPermissionAction(PendingPermissionAction.RetryRevocations)
            mutableState.update { it.copy(route = TunesLinkRoute.LocalNetworkPermission) }
            return
        }
        retryPendingRevocations()
    }

    fun tryAgain() = connect(initial = false)

    fun pairAgain() {
        repository.current() ?: run {
            chooseAnotherComputer()
            return
        }
        repository.stopStateUpdates()
        stateUpdatesActive = false
        relocationRequest.cancel()
        libraryRequest.cancel()
        browseRequest.cancel()
        repository.cancelRelocation()
        val generation = ++relocationGeneration
        mutableState.update { it.copy(connection = ConnectionState.Connecting) }
        relocationRequest = repository.relocateCurrent(object : BridgeClient.Result<BridgeRepository.Relocation> {
            override fun success(value: BridgeRepository.Relocation) {
                if (generation != relocationGeneration) return
                when (value.status) {
                    BridgeRepository.Relocation.Status.RELOCATED -> {
                        val relocated = value.relocated ?: return
                        updateBridge(
                            relocated.name,
                            relocated.host,
                            relocated.port,
                        )
                        connect(initial = false)
                    }
                    BridgeRepository.Relocation.Status.IDENTITY_CHANGED -> {
                        val observed = value.observed ?: return
                        openPairing(observed)
                    }
                }
            }

            override fun failure(message: String, unauthorized: Boolean) {
                if (generation != relocationGeneration) return
                val computer = repository.current()?.name.orEmpty()
                mutableState.update {
                    it.copy(
                        connection = ConnectionState.RecoverableFailure(computer),
                        discoveryError = localizedFailure(message),
                    )
                }
                announce(R.string.recovery_failed, haptic = HapticIntent.Reject)
            }
        })
    }

    fun chooseAnotherComputer() {
        cancelTransientOperations()
        libraryRequest.cancel()
        browseRequest.cancel()
        repository.stopStateUpdates()
        stateUpdatesActive = false
        savedStateHandle[KEY_SWITCHING_COMPUTER] = true
        savedStateHandle[KEY_MODAL] = null
        savedStateHandle[KEY_MODAL_RETURN] = null
        mutableState.update {
            it.copy(
                route = TunesLinkRoute.Welcome,
                connection = ConnectionState.Unpaired,
                discoveryError = null,
                modalPresentation = null,
                navigation = it.navigation.copy(switchingComputer = true),
            )
        }
    }

    fun navigate(destination: TunesLinkDestination) {
        restoreDestination = destination
        savedStateHandle[KEY_DESTINATION] = destination.name
        mutableState.update {
            it.copy(
                route = TunesLinkRoute.Connected(destination),
                navigation = it.navigation.copy(destination = destination),
            )
        }
        if (destination == TunesLinkDestination.Search
            && mutableState.value.library.editingQuery.isNotBlank()
        ) {
            commitSearch(mutableState.value.library.editingQuery)
        }
    }

    fun togglePlayback() = sendCommand(PlaybackAction.PlayPause)

    fun previous() = sendCommand(PlaybackAction.Previous)

    fun next() = sendCommand(PlaybackAction.Next)

    fun seek(position: Double) = sendCommand(PlaybackAction.Position, position)

    fun setVolume(volume: Int) = sendCommand(PlaybackAction.Volume, volume.coerceIn(0, 100).toDouble())

    fun toggleShuffle() = sendCommand(
        PlaybackAction.Shuffle,
        if (mutableState.value.player.shuffleEnabled) 0.0 else 1.0,
    )

    fun cycleRepeat() {
        val next = mutableState.value.player.repeatMode.next()
        sendCommand(PlaybackAction.Repeat, next.wireValue)
    }

    fun consumeAnnouncement(id: Long) {
        mutableState.update { current ->
            if (current.announcement?.id == id) current.copy(announcement = null) else current
        }
    }

    private fun connect(initial: Boolean) {
        val bridge = repository.current() ?: run {
            mutableState.update { it.copy(route = TunesLinkRoute.Welcome, connection = ConnectionState.Unpaired) }
            return
        }
        repository.stopStateUpdates()
        stateUpdatesActive = false
        mutableState.update {
            it.copy(
                route = if (initial && !connectedOnce) TunesLinkRoute.Connecting else it.route,
                connection = ConnectionState.Connecting,
                discoveryError = null,
            )
        }
        repository.startStateUpdates(object : BridgeRepository.StateUpdatesListener {
            override fun state(state: BridgeClient.PlayerState) {
                latestAuthoritativeState = state
                val before = mutableState.value.player
                val completed = before.pendingMutations.values.filter { mutation ->
                    mutation.refreshRequested || mutation.matches(state)
                }
                val rejected = completed.filter { it.refreshRequested && !it.matches(state) }
                val confirmed = completed - rejected.toSet()
                val merged = mergePlaybackState(before, state)
                mutableState.update {
                    it.copy(
                        player = merged.copy(
                            commandError = when {
                                rejected.isNotEmpty() ->
                                    getApplication<Application>().getString(R.string.playback_change_not_applied)
                                confirmed.isNotEmpty() -> null
                                else -> merged.commandError
                            },
                        ),
                    )
                }
                completed.forEach { mutation ->
                    mutationTimeoutJobs.remove(mutation.operationId)?.cancel()
                    if (mutation in confirmed) announceMutationSuccess(mutation.action, merged)
                }
                if (rejected.isNotEmpty()) {
                    announce(R.string.playback_change_not_applied, haptic = HapticIntent.Reject)
                }
                if (before.artworkId != merged.artworkId) loadArtwork(merged.artworkId)
            }

            override fun connectionChanged(
                activeBridge: SecureStore.SavedBridge,
                connected: Boolean,
                message: String?,
            ) {
                if (connected) {
                    val restored = mutableState.value.connection !is ConnectionState.Connected
                    connectedOnce = true
                    updateBridge(
                        activeBridge.name,
                        activeBridge.host,
                        activeBridge.port,
                    )
                    mutableState.update {
                        it.copy(
                            route = TunesLinkRoute.Connected(restoreDestination),
                            connection = ConnectionState.Connected(activeBridge.name),
                        )
                    }
                    lastAvailabilityKind = ConnectionAvailabilityKind.Available
                    if (restored) {
                        announce(R.string.connected_to, listOf(activeBridge.name))
                        commitSearch(mutableState.value.library.editingQuery)
                    }
                } else {
                    val identityChanged = message == BridgeClient.IDENTITY_CHANGED_MESSAGE
                    val nextConnection = if (identityChanged) {
                        ConnectionState.IdentityChanged(activeBridge.name)
                    } else {
                        ConnectionState.RecoverableFailure(activeBridge.name)
                    }
                    mutableState.update {
                        it.copy(
                            route = TunesLinkRoute.Connected(restoreDestination),
                            connection = nextConnection,
                        )
                    }
                    announceAvailability(nextConnection)
                }
            }

            override fun unauthorized(activeBridge: SecureStore.SavedBridge, message: String) {
                val nextConnection = ConnectionState.Unauthorized(activeBridge.name)
                mutableState.update {
                    it.copy(
                        route = TunesLinkRoute.Connected(restoreDestination),
                        connection = nextConnection,
                    )
                }
                announceAvailability(nextConnection)
            }
        })
        stateUpdatesActive = true
    }

    private fun openPairing(bridge: BridgeClient.BridgeInfo) {
        repository.cancelDiscovery()
        repository.cancelManualResolution()
        discoveryRequest.cancel()
        manualRequest.cancel()
        discoveryGeneration++
        manualGeneration++
        savedStateHandle[KEY_SWITCHING_COMPUTER] = false
        savedStateHandle[KEY_MODAL] = null
        savedStateHandle[KEY_MODAL_RETURN] = null
        mutableState.update {
            val next = ModalPresentation(TunesLinkModal.Pairing(bridge))
            val currentModal = it.modalPresentation
            it.copy(
                connection = ConnectionState.Unpaired,
                modalPresentation = if (currentModal != null && !currentModal.dismissRequested) {
                    currentModal.copy(dismissRequested = true, replacement = next)
                } else {
                    next
                },
                discovered = emptyList(),
                pairing = PairingUiState(),
                manualResolutionBusy = false,
                navigation = it.navigation.copy(switchingComputer = false),
            )
        }
    }

    private fun finishLocalForget(messageRes: Int, haptic: HapticIntent) {
        artworkRequest.cancel()
        libraryRequest.cancel()
        browseRequest.cancel()
        mutationTimeoutJobs.values.forEach(Job::cancel)
        mutationTimeoutJobs.clear()
        connectedOnce = false
        stateUpdatesActive = false
        resetAfterModalDismiss = true
        resetAnnouncement = UiAnnouncement(
            id = ++nextAnnouncementId,
            messageRes = messageRes,
            hapticIntent = haptic,
        )
        mutableState.update { state ->
            state.copy(
                forgetBusy = false,
                pendingRevocationCount = repository.pendingRevocationCount(),
                modalPresentation = state.modalPresentation?.copy(
                    returnTo = null,
                    dismissRequested = true,
                    replacement = null,
                ),
            )
        }
        if (mutableState.value.modalPresentation == null) completeModalDismiss()
    }

    private fun cancelOperationForModal(modal: TunesLinkModal?) {
        when (modal) {
            TunesLinkModal.ManualAddress -> {
                manualGeneration++
                manualRequest.cancel()
                repository.cancelManualResolution()
                mutableState.update { it.copy(manualResolutionBusy = false) }
            }
            is TunesLinkModal.Pairing -> {
                pairingGeneration++
                repository.cancelPairing()
            }
            else -> Unit
        }
    }

    private fun cancelTransientOperations() {
        discoveryGeneration++
        manualGeneration++
        pairingGeneration++
        relocationGeneration++
        discoveryRequest.cancel()
        manualRequest.cancel()
        relocationRequest.cancel()
        repository.cancelDiscovery()
        repository.cancelManualResolution()
        repository.cancelPairing()
        repository.cancelRelocation()
        mutableState.update { it.copy(manualResolutionBusy = false) }
    }

    internal fun requestArtwork(
        artworkId: String,
        size: Int,
        result: BridgeClient.Result<Bitmap>,
    ): BridgeRepository.RequestHandle {
        if (artworkId.isBlank()) {
            result.success(null)
            return BridgeRepository.RequestHandle.NONE
        }
        repository.cachedArtwork(artworkId, size)?.let {
            result.success(it)
            return BridgeRepository.RequestHandle.NONE
        }
        return repository.getArtwork(artworkId, size, result)
    }

    private fun updateBridge(name: String, host: String, port: Int) {
        mutableState.update {
            it.copy(
                bridgeName = name,
                bridgeAddress = "$host:$port",
            )
        }
    }

    internal fun announce(
        messageRes: Int,
        arguments: List<Any> = emptyList(),
        haptic: HapticIntent = HapticIntent.None,
    ) {
        mutableState.update {
            it.copy(
                announcement = UiAnnouncement(
                    id = ++nextAnnouncementId,
                    messageRes = messageRes,
                    arguments = arguments,
                    hapticIntent = haptic,
                ),
            )
        }
    }

    internal fun announcePlural(messageRes: Int, quantity: Int, arguments: List<Any>) {
        mutableState.update {
            it.copy(
                announcement = UiAnnouncement(
                    id = ++nextAnnouncementId,
                    messageRes = messageRes,
                    arguments = arguments,
                    quantity = quantity,
                ),
            )
        }
    }

    private fun setPendingPermissionAction(action: PendingPermissionAction?) {
        pendingPermissionAction = action
        savedStateHandle[KEY_PENDING_PERMISSION_ACTION] = action?.name
        uiRecovery.edit {
            if (action == null) remove(KEY_PENDING_PERMISSION_ACTION)
            else putString(KEY_PENDING_PERMISSION_ACTION, action.name)
        }
    }

    private fun setModalPresentation(presentation: ModalPresentation?) {
        if (presentation != null) {
            discoveryGeneration++
            discoveryRequest.cancel()
            repository.cancelDiscovery()
        }
        val current = mutableState.value.modalPresentation
        if (presentation != null && current != null && !current.dismissRequested &&
            current.destination != presentation.destination
        ) {
            mutableState.update {
                it.copy(
                    modalPresentation = current.copy(
                        dismissRequested = true,
                        replacement = presentation,
                    ),
                )
            }
            return
        }
        publishModalPresentation(presentation)
    }

    private fun publishModalPresentation(presentation: ModalPresentation?) {
        savedStateHandle[KEY_MODAL] = persistedModalName(presentation?.destination)
        savedStateHandle[KEY_MODAL_RETURN] = persistedModalName(presentation?.returnTo)
        mutableState.update { it.copy(modalPresentation = presentation) }
    }

    private fun announceAvailability(connection: ConnectionState) {
        val availability = ConnectionAvailability.from(connection)
        if (lastAvailabilityKind == availability.kind) return
        lastAvailabilityKind = availability.kind
        when (availability.kind) {
            ConnectionAvailabilityKind.ConnectionLost ->
                announce(R.string.connection_lost, haptic = HapticIntent.Reject)
            ConnectionAvailabilityKind.PairingExpired ->
                announce(R.string.pairing_expired, haptic = HapticIntent.Reject)
            ConnectionAvailabilityKind.IdentityChanged ->
                announce(R.string.security_id_changed, haptic = HapticIntent.Reject)
            else -> Unit
        }
    }

    private fun manualAddressError(value: String): String? =
        validateManualAddress(value)?.let { getApplication<Application>().getString(it) }

    /**
     * Network and storage layers retain diagnostic text for logs and protocol compatibility. The
     * presentation layer never exposes that English text directly: known categories and the
     * conservative fallback are all resource-backed and therefore translation-ready.
     */
    internal fun localizedFailure(
        diagnostic: String,
        fallbackRes: Int = R.string.error_computer_unavailable,
    ): String {
        val normalized = diagnostic.lowercase()
        val messageRes = when {
            "already being paired" in normalized -> R.string.error_operation_in_progress
            "two paired phones" in normalized || "device limit" in normalized ->
                R.string.error_device_limit
            "no saved computer" in normalized -> R.string.error_no_saved_computer
            "could not find the paired computer" in normalized -> R.string.error_computer_not_found
            "local network" in normalized || "private ipv4" in normalized ->
                R.string.error_local_network_only
            "not a TunesLink bridge" in normalized -> R.string.error_not_TunesLink_bridge
            "identity" in normalized || "security" in normalized || "invalid token" in normalized ->
                R.string.error_bridge_identity
            "pairing" in normalized && ("failed" in normalized || "code" in normalized) ->
                R.string.error_pairing_code
            "secure storage" in normalized || "commit secure pairing" in normalized ->
                R.string.error_pairing_storage
            "queue" in normalized && "revocation" in normalized -> R.string.error_revocation_queue_full
            "revoke" in normalized || "revocation" in normalized || "still authorized" in normalized ->
                R.string.error_revocation_pending
            "library" in normalized || "itunes" in normalized -> R.string.error_library_unavailable
            else -> fallbackRes
        }
        Log.w(TAG, "Bridge operation failed (category=$messageRes)")
        return getApplication<Application>().getString(messageRes)
    }

    override fun onCleared() {
        cancelTransientOperations()
        artworkRequest.cancel()
        libraryRequest.cancel()
        browseRequest.cancel()
        mutationTimeoutJobs.values.forEach(Job::cancel)
        mutationTimeoutJobs.clear()
        stateUpdatesActive = false
        repository.stopStateUpdates()
    }

    companion object {
        internal const val TAG = "TunesLinkArtwork"
        private const val NAVIGATION_TAG = "TunesLinkNavigation"
        private const val KEY_MANUAL_ADDRESS = "manual_address"
        internal const val KEY_EDITING_QUERY = "editing_query"
        private const val KEY_DESTINATION = "destination"
        private const val KEY_SWITCHING_COMPUTER = "switching_computer"
        private const val KEY_PENDING_PERMISSION_ACTION = "pending_permission_action"
        private const val UI_RECOVERY_PREFERENCES = "TunesLink_ui_recovery"
        private const val KEY_MODAL = "modal"
        private const val KEY_MODAL_RETURN = "modal_return"
        private const val PAIRING_CODE_LENGTH = 6
        private const val MAX_ADDRESS_LENGTH = 64
        internal const val PAGE_SIZE = 60
        internal const val MAX_LIBRARY_WINDOW_ITEMS = PAGE_SIZE * 8
        internal const val ARTWORK_SIZE = 900

        private fun savedDestination(value: String?): TunesLinkDestination =
            runCatching { TunesLinkDestination.valueOf(value.orEmpty()) }
                .getOrDefault(TunesLinkDestination.Library)

        private fun persistedModalName(modal: TunesLinkModal?): String? = when (modal) {
            TunesLinkModal.ManualAddress -> "manual"
            TunesLinkModal.ConnectionDetails -> "connection"
            TunesLinkModal.Privacy -> "privacy"
            TunesLinkModal.ForgetConfirmation -> "forget"
            is TunesLinkModal.Pairing, null -> null
        }

        private fun restoredModal(value: String?): TunesLinkModal? = when (value) {
            "manual" -> TunesLinkModal.ManualAddress
            "connection" -> TunesLinkModal.ConnectionDetails
            "privacy" -> TunesLinkModal.Privacy
            "forget" -> TunesLinkModal.ForgetConfirmation
            else -> null
        }

        private fun restoredModalPresentation(value: String?, returnValue: String?): ModalPresentation? =
            restoredModal(value)?.let { ModalPresentation(it, restoredModal(returnValue)) }

        internal fun validateManualAddress(value: String): Int? {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) return R.string.address_required
            val parts = trimmed.split(':')
            if (parts.size !in 1..2) return R.string.address_format_error
            val octets = parts[0].split('.').mapNotNull(String::toIntOrNull)
            if (octets.size != 4 || octets.any { it !in 0..255 }) {
                return R.string.address_invalid_ipv4
            }
            if (parts.size == 2 && (parts[1].toIntOrNull() !in 1..65535)) {
                return R.string.address_invalid_port
            }
            val privateAddress = octets[0] == 10 ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 192 && octets[1] == 168) ||
                (octets[0] == 169 && octets[1] == 254) ||
                (octets[0] == 100 && octets[1] in 64..127) ||
                octets[0] == 127
            return if (privateAddress) null else R.string.address_not_private
        }
    }
}
