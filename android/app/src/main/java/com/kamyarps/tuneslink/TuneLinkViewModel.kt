package com.kamyarps.tuneslink

import android.app.Application
import android.os.SystemClock
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
    internal var announcedResultQuery: String? = null
    internal var browseCollectionsGeneration = 0
    internal var browseTracksGeneration = 0
    private var connectedOnce = false
    private var stateUpdatesActive = false
    /** Wall-clock time the current cover is due for an automatic reload (refresh or retry). */
    internal var artworkDueAt: Long = 0
    internal var artworkFailureId = ""
    internal var artworkFailures = 0
    internal var artworkRetryJob: Job? = null
    internal val artworkSession = MutableStateFlow(0L)
    internal var artworkRequest: BridgeRepository.RequestHandle = BridgeRepository.RequestHandle.NONE
    /** Queued or in-flight playback commands, by pending-mutation operation ID. */
    internal val commandRequests = mutableMapOf<Long, BridgeRepository.RequestHandle>()
    /** The connection shown before "Pair again", restored if the pairing sheet is dismissed. */
    private var pairAgainPrevious: ConnectionState? = null
    internal var libraryRequest: BridgeRepository.RequestHandle = BridgeRepository.RequestHandle.NONE
    internal var browseCollectionsRequest: BridgeRepository.RequestHandle =
        BridgeRepository.RequestHandle.NONE
    internal var browseTracksRequest: BridgeRepository.RequestHandle =
        BridgeRepository.RequestHandle.NONE
    internal var playTrackRequest: BridgeRepository.RequestHandle =
        BridgeRepository.RequestHandle.NONE
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
    private var backgroundStopJob: Job? = null
    private var artworkRefreshJob: Job? = null
    private var pairingCooldownJob: Job? = null

    init {
        viewModelScope.launch {
            editingQuery.committedSearchQueries().collectLatest { commitSearch(it) }
        }
        val saved = repository.current()
        if (saved != null && mutableState.value.navigation.switchingComputer) {
            mutableState.update { it.copy(returnComputer = displayName(saved.name)) }
        } else if (saved != null) {
            updateBridge(saved.name, saved.host, saved.port)
            connect(initial = true)
        }
    }

    fun onForeground() {
        backgroundStopJob?.cancel()
        backgroundStopJob = null
        artworkRefreshJob?.cancel()
        artworkRefreshJob = viewModelScope.launch {
            // Unchanged/paused playback produces no SSE frames, so refresh artwork independently.
            while (true) {
                val current = mutableState.value
                val canRefresh = current.connection is ConnectionState.Connected &&
                    current.player.artworkId.isNotBlank()
                if (canRefresh) refreshArtworkIfDue(current.player.artworkId, current.player.artworkId)
                val wait = if (canRefresh) {
                    (artworkDueAt - System.currentTimeMillis())
                        .coerceIn(1_000L, ArtworkDiskCache.REFRESH_INTERVAL_MS)
                } else ArtworkDiskCache.REFRESH_INTERVAL_MS
                delay(wait)
            }
        }
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
                    ConnectionState.RecoverableFailure(displayName(bridge.name))
                } ?: ConnectionState.Unpaired,
            )
        }
        return false
    }

    fun onBackground() {
        artworkRefreshJob?.cancel()
        artworkRefreshJob = null
        artworkRetryJob?.cancel()
        artworkRetryJob = null
        cancelTransientOperations()
        backgroundStopJob?.cancel()
        backgroundStopJob = viewModelScope.launch {
            delay(BACKGROUND_STREAM_GRACE_MILLIS)
            backgroundStopJob = null
            stateUpdatesActive = false
            repository.stopStateUpdates()
        }
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
        // A paired phone resumes its computer; only an unpaired one belongs on Welcome.
        val resuming = action == PendingPermissionAction.Reconnect && repository.current() != null &&
            !mutableState.value.navigation.switchingComputer
        mutableState.update {
            it.copy(
                route = if (resuming) resumeRoute() else TunesLinkRoute.Welcome,
                connection = if (resuming) ConnectionState.Connecting else it.connection,
                discoveryError = null,
            )
        }
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
                if (generation != discoveryGeneration) return
                val found = value.map(::withDisplayName)
                when {
                    found.isEmpty() -> mutableState.update {
                        it.copy(
                            connection = ConnectionState.Unpaired,
                            discoveryError = getApplication<Application>().getString(R.string.no_computer_found_detail),
                        )
                    }
                    // Never replace a dialog the user opened meanwhile; list the result instead.
                    found.size == 1 && mutableState.value.modal == null -> openPairing(found.first())
                    else -> mutableState.update {
                        it.copy(connection = ConnectionState.Unpaired, discovered = found)
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
            if (it.pairing.phase != PairingPhase.Editing) return@update it
            it.copy(
                pairing = it.pairing.copy(
                    code = value.filter { character -> character in '0'..'9' }.take(PAIRING_CODE_LENGTH),
                    codeError = null,
                    message = null,
                    phase = PairingPhase.Editing,
                ),
            )
        }
    }

    fun pair() {
        val snapshot = mutableState.value
        val bridge = (snapshot.modal as? TunesLinkModal.Pairing)?.bridge ?: return
        if (snapshot.pairing.phase != PairingPhase.Editing || snapshot.pairing.retryAfterSeconds > 0) return
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
                    message = null,
                ),
            )
        }
        repository.cancelPairing()
        val generation = ++pairingGeneration
        repository.pair(bridge, snapshot.pairing.code, object : BridgeClient.Result<SecureStore.SavedBridge> {
            override fun success(value: SecureStore.SavedBridge) {
                if (generation != pairingGeneration) return
                latestAuthoritativeState = null
                pairAgainPrevious = null
                cancelPendingCommands()
                mutationTimeoutJobs.values.forEach { it.cancel() }
                mutationTimeoutJobs.clear()
                updateBridge(value.name, value.host, value.port)
                mutableState.update {
                    it.copy(
                        pairing = it.pairing.copy(phase = PairingPhase.Success),
                        library = LibraryUiState(editingQuery = it.library.editingQuery),
                        browse = LibraryBrowseUiState(),
                        player = PlayerUiState(),
                        navigation = it.navigation.copy(switchingComputer = false),
                        returnComputer = null,
                    )
                }
                savedStateHandle[KEY_SWITCHING_COMPUTER] = false
                announce(
                    R.string.paired_securely_with,
                    listOf(displayName(value.name)),
                    HapticIntent.Confirm,
                )
                connect(initial = true)
                requestModalDismiss()
            }

            override fun failure(message: String, unauthorized: Boolean) =
                failure(message, unauthorized, BridgeClient.ErrorCode.NONE)

            override fun failure(message: String, unauthorized: Boolean, code: BridgeClient.ErrorCode) {
                if (generation != pairingGeneration) return
                // The sheet stays open. Only a rejected code is shown under the code field;
                // "pairing isn't open on the PC" and similar reasons are a separate message.
                val messageRes = failureMessageRes(message, R.string.error_pairing_code, code)
                val copy = localizedString(messageRes)
                val codeFailure = isPairingCodeFailure(code, messageRes)
                mutableState.update {
                    it.copy(
                        connection = ConnectionState.Unpaired,
                        pairing = it.pairing.copy(
                            phase = PairingPhase.Editing,
                            codeError = if (codeFailure) copy else null,
                            message = if (codeFailure) null else copy,
                        ),
                    )
                }
                announce(R.string.pairing_failed, haptic = HapticIntent.Reject)
            }

            override fun rateLimited(retryAfterSeconds: Int) {
                if (generation != pairingGeneration) return
                savedStateHandle["pairingCooldownEndpoint"] = bridge.host + ":" + bridge.port
                savedStateHandle["pairingCooldownUntil"] = SystemClock.elapsedRealtime() +
                    retryAfterSeconds.coerceIn(1, 3600) * 1000L
                mutableState.update { it.copy(connection = ConnectionState.Unpaired,
                    pairing = it.pairing.copy(phase = PairingPhase.Editing, codeError = null)) }
                startPairingCooldown(bridge)
                announce(R.string.pairing_rate_limited, haptic = HapticIntent.Reject)
            }
        })
    }

    private fun startPairingCooldown(bridge: BridgeClient.BridgeInfo) {
        pairingCooldownJob?.cancel()
        val endpoint = bridge.host + ":" + bridge.port
        val until = if (savedStateHandle.get<String>("pairingCooldownEndpoint") == endpoint) {
            savedStateHandle.get<Long>("pairingCooldownUntil") ?: 0L
        } else 0L
        fun remaining() = ((until - SystemClock.elapsedRealtime() + 999L) / 1000L).coerceIn(0, 3600).toInt()
        mutableState.update { it.copy(pairing = it.pairing.copy(retryAfterSeconds = remaining())) }
        pairingCooldownJob = viewModelScope.launch {
            while (remaining() > 0) {
                delay(1_000)
                mutableState.update { it.copy(pairing = it.pairing.copy(retryAfterSeconds = remaining())) }
            }
        }
    }

    fun dismissCommandError() {
        mutableState.update { it.copy(player = it.player.copy(commandError = null)) }
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
            // A later pairing starts at Library with an empty search, and typing the old query
            // again must still commit (the query flow is distinctUntilChanged).
            restoreDestination = TunesLinkDestination.Library
            pairAgainPrevious = null
            announcedResultQuery = null
            libraryGeneration++
            editingQuery.value = ""
            savedStateHandle[KEY_EDITING_QUERY] = ""
            savedStateHandle[KEY_SWITCHING_COMPUTER] = false
            savedStateHandle[KEY_DESTINATION] = TunesLinkDestination.Library.name
            savedStateHandle[KEY_MODAL] = null
            savedStateHandle[KEY_MODAL_RETURN] = null
            return
        }
        val presentation = mutableState.value.modalPresentation
        val next = presentation?.replacement ?: presentation?.returnTo?.let(::ModalPresentation)
        val abandonedPairing = presentation?.destination is TunesLinkModal.Pairing && next == null &&
            mutableState.value.pairing.phase != PairingPhase.Success
        publishModalPresentation(next)
        mutableState.update {
            it.copy(
                pairing = if (next == null) PairingUiState() else it.pairing,
                manualAddressError = null,
            )
        }
        if (abandonedPairing) restoreAfterAbandonedPairAgain()
    }

    /**
     * "Pair again" was dismissed without pairing: show the saved computer's recovery state again
     * (pairing expired / identity changed) or reconnect, instead of a silent, stopped session.
     */
    private fun restoreAfterAbandonedPairAgain() {
        val previous = pairAgainPrevious ?: return
        pairAgainPrevious = null
        if (repository.current() == null || mutableState.value.navigation.switchingComputer) return
        when (previous) {
            is ConnectionState.Unauthorized, is ConnectionState.IdentityChanged -> mutableState.update {
                it.copy(route = TunesLinkRoute.Connected(restoreDestination), connection = previous)
            }
            else -> connect(initial = false)
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
        browseCollectionsRequest.cancel()
        browseTracksRequest.cancel()
        repository.cancelRelocation()
        val generation = ++relocationGeneration
        mutableState.value.connection.takeIf { it !is ConnectionState.Connecting }
            ?.let { pairAgainPrevious = it }
        mutableState.update { it.copy(connection = ConnectionState.Connecting) }
        relocationRequest = repository.resolvePairingEndpoint(object : BridgeClient.Result<BridgeClient.BridgeInfo> {
            override fun success(value: BridgeClient.BridgeInfo) {
                if (generation != relocationGeneration) return
                openPairing(value)
            }

            override fun failure(message: String, unauthorized: Boolean) {
                if (generation != relocationGeneration) return
                pairAgainPrevious = null
                val computer = displayName(repository.current()?.name.orEmpty())
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
        browseCollectionsRequest.cancel()
        browseTracksRequest.cancel()
        repository.stopStateUpdates()
        stateUpdatesActive = false
        savedStateHandle[KEY_SWITCHING_COMPUTER] = true
        savedStateHandle[KEY_MODAL] = null
        savedStateHandle[KEY_MODAL_RETURN] = null
        // Choosing is not forgetting: the saved pairing stays until another one succeeds.
        val saved = repository.current()
        mutableState.update {
            it.copy(
                route = TunesLinkRoute.Welcome,
                connection = ConnectionState.Unpaired,
                discoveryError = null,
                modalPresentation = null,
                navigation = it.navigation.copy(switchingComputer = true),
                returnComputer = saved?.let { bridge -> displayName(bridge.name) },
            )
        }
    }

    /** Leaves "Choose another computer" and reconnects to the computer that is still paired. */
    fun returnToSavedComputer() {
        val saved = repository.current() ?: return
        cancelTransientOperations()
        savedStateHandle[KEY_SWITCHING_COMPUTER] = false
        savedStateHandle[KEY_MODAL] = null
        savedStateHandle[KEY_MODAL_RETURN] = null
        updateBridge(saved.name, saved.host, saved.port)
        mutableState.update {
            it.copy(
                route = resumeRoute(),
                discovered = emptyList(),
                discoveryError = null,
                modalPresentation = null,
                navigation = it.navigation.copy(switchingComputer = false),
                returnComputer = null,
            )
        }
        connect(initial = true)
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
        // Returning to Search keeps results (and their announcement) for an unchanged query.
        val library = mutableState.value.library
        if (destination == TunesLinkDestination.Search && library.searchNeedsCommit()) {
            commitSearch(library.editingQuery)
        }
    }

    fun togglePlayback() = sendCommand(PlaybackAction.PlayPause)

    fun previous() = sendCommand(PlaybackAction.Previous)

    fun next() = sendCommand(PlaybackAction.Next)

    fun seek(position: Double) = seek(position, mutableState.value.player.trackId)

    /**
     * Seeks within [trackId], the song the drag started on. A release after the song changed is
     * dropped rather than moving the new song.
     */
    fun seek(position: Double, trackId: String) {
        if (trackId != mutableState.value.player.trackId) return
        sendCommand(PlaybackAction.Position, position)
    }

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

    internal fun applyAuthoritativeState(state: BridgeClient.PlayerState) {
        latestAuthoritativeState = state
        val before = mutableState.value.player
        val confirmed = before.pendingMutations.values.filter { it.matches(state) }
        val merged = mergePlaybackState(before, state)
        mutableState.update {
            it.copy(
                player = merged.copy(
                    commandError = if (confirmed.isNotEmpty()) null else merged.commandError,
                ),
            )
        }
        confirmed.forEach { mutation ->
            mutationTimeoutJobs.remove(mutation.operationId)?.cancel()
            announceMutationSuccess(mutation.action, merged)
        }
        refreshArtworkIfDue(before.artworkId, merged.artworkId)
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
                applyAuthoritativeState(state)
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
                            connection = ConnectionState.Connected(displayName(activeBridge.name)),
                        )
                    }
                    lastAvailabilityKind = ConnectionAvailabilityKind.Available
                    if (restored) {
                        // Anything still queued belongs to the interrupted session.
                        cancelPendingCommands()
                        mutationTimeoutJobs.values.forEach { it.cancel() }
                        mutationTimeoutJobs.clear()
                        mutableState.update { state ->
                            state.copy(player = state.player.copy(
                                pendingMutations = emptyMap(),
                                commandError = if (state.player.pendingMutations.isNotEmpty()) {
                                    getApplication<Application>().getString(R.string.playback_connection_interrupted)
                                } else state.player.commandError,
                            ))
                        }
                        artworkRequest.cancel()
                        artworkDueAt = 0
                        artworkSession.value++
                        announce(R.string.connected_to, listOf(displayName(activeBridge.name)))
                        commitSearch(mutableState.value.library.editingQuery)
                        restoreBrowseAfterReconnect()
                    }
                } else {
                    val identityChanged = message == BridgeClient.IDENTITY_CHANGED_MESSAGE
                    val computer = displayName(activeBridge.name)
                    val nextConnection = if (identityChanged) {
                        ConnectionState.IdentityChanged(computer)
                    } else {
                        ConnectionState.RecoverableFailure(computer)
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
                val nextConnection = ConnectionState.Unauthorized(displayName(activeBridge.name))
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
        // switchingComputer stays set until pairing succeeds, so a dismissed sheet can still
        // return to the saved computer.
        savedStateHandle[KEY_MODAL] = null
        savedStateHandle[KEY_MODAL_RETURN] = null
        val named = withDisplayName(bridge)
        mutableState.update {
            val next = ModalPresentation(TunesLinkModal.Pairing(named))
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
            )
        }
        startPairingCooldown(bridge)
    }

    private fun finishLocalForget(messageRes: Int, haptic: HapticIntent) {
        latestAuthoritativeState = null
        artworkRequest.cancel()
        libraryRequest.cancel()
        browseCollectionsRequest.cancel()
        browseTracksRequest.cancel()
        cancelPendingCommands()
        artworkRetryJob?.cancel()
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
        mutableState.update(TunesLinkUiState::afterTransientCancellation)
    }

    private fun updateBridge(name: String, host: String, port: Int) {
        mutableState.update {
            it.copy(
                bridgeName = displayName(name),
                bridgeAddress = "$host:$port",
            )
        }
    }

    /** A computer that reports no name is shown with a localized default. */
    internal fun displayName(name: String): String =
        name.ifBlank { localizedString(R.string.default_computer_name) }

    private fun withDisplayName(bridge: BridgeClient.BridgeInfo): BridgeClient.BridgeInfo =
        if (bridge.name.isNotBlank()) bridge else BridgeClient.BridgeInfo(
            bridge.id, displayName(bridge.name), bridge.host, bridge.port, bridge.tlsFingerprint,
        )

    /** Where a paired phone waits while it reconnects: never the Welcome screen. */
    private fun resumeRoute(): TunesLinkRoute =
        if (connectedOnce) TunesLinkRoute.Connected(restoreDestination) else TunesLinkRoute.Connecting

    internal fun localizedString(res: Int): String = getApplication<Application>().getString(res)

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

    /** A dialog replaces discovery: stop it and release the "Finding computers…" state. */
    private fun cancelDiscoveryForModal() {
        discoveryGeneration++
        discoveryRequest.cancel()
        repository.cancelDiscovery()
        mutableState.update(TunesLinkUiState::afterDiscoveryCancelled)
    }

    private fun setModalPresentation(presentation: ModalPresentation?) {
        if (presentation != null) cancelDiscoveryForModal()
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
     * presentation layer never exposes that English text directly: a typed bridge code wins, then
     * the legacy categories, then the conservative fallback — all resource-backed.
     */
    internal fun localizedFailure(
        diagnostic: String,
        fallbackRes: Int = R.string.error_computer_unavailable,
        code: BridgeClient.ErrorCode = BridgeClient.ErrorCode.NONE,
    ): String {
        val messageRes = failureMessageRes(diagnostic, fallbackRes, code)
        Log.w(TAG, "Bridge operation failed (code=$code, category=$messageRes)")
        return localizedString(messageRes)
    }

    override fun onCleared() {
        cancelTransientOperations()
        artworkRequest.cancel()
        libraryRequest.cancel()
        browseCollectionsRequest.cancel()
        browseTracksRequest.cancel()
        cancelPendingCommands()
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
        private const val BACKGROUND_STREAM_GRACE_MILLIS = 15_000L
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
