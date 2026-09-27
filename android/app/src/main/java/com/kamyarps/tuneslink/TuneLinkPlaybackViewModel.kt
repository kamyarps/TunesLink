package com.kamyarps.tuneslink

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun TunesLinkViewModel.sendCommand(action: PlaybackAction, value: Double? = null) {
    val previous = mutableState.value.player
    previous.pending(action)?.let { replaced ->
        mutationTimeoutJobs.remove(replaced.operationId)?.cancel()
    }
    val mutation = pendingMutation(action, previous, value)
    val pending = previous.pendingMutations + (action to mutation)
    val optimistic = when (action) {
        PlaybackAction.PlayPause -> previous.copy(
            playing = !previous.playing,
            pendingMutations = pending,
            commandError = null,
        )
        PlaybackAction.Position -> previous.copy(
            position = value ?: previous.position,
            pendingMutations = pending,
            commandError = null,
        )
        PlaybackAction.Volume -> previous.copy(
            volume = (value ?: previous.volume.toDouble()).toInt(),
            pendingMutations = pending,
            commandError = null,
        )
        PlaybackAction.Shuffle -> previous.copy(
            shuffleEnabled = (value ?: 0.0) >= 0.5,
            pendingMutations = pending,
            commandError = null,
        )
        PlaybackAction.Repeat -> previous.copy(
            repeatMode = when ((value ?: 0.0).toInt()) {
                1 -> RepeatMode.One
                2 -> RepeatMode.All
                else -> RepeatMode.Off
            },
            pendingMutations = pending,
            commandError = null,
        )
        PlaybackAction.Previous, PlaybackAction.Next ->
            previous.copy(pendingMutations = pending, commandError = null)
        PlaybackAction.PlayTrack ->
            error("This action uses a dedicated endpoint")
    }
    mutableState.update { it.copy(player = optimistic) }
    // Commands share an ordered transport lane with play selections. Reconciliation starts
    // after acceptance, so time spent behind a slow queue build is not reported as failure.
    commandRequests[mutation.operationId] = repository.command(
        checkNotNull(action.wireCommand),
        value,
        commandResult(mutation, previous, R.string.playback_command_failed),
    )
}

/** Abandons queued and in-flight commands whose optimistic state has been discarded. */
internal fun TunesLinkViewModel.cancelPendingCommands() {
    val abandoned = commandRequests.values.toList()
    commandRequests.clear()
    abandoned.forEach(BridgeRepository.RequestHandle::cancel)
    playTrackRequest.cancel()
}

internal fun TunesLinkViewModel.commandResult(
    mutation: PendingMutation,
    rollback: PlayerUiState,
    failureRes: Int,
    failureArguments: List<Any> = emptyList(),
) =
    object : BridgeClient.Result<Boolean> {
        override fun success(value: Boolean) {
            commandRequests.remove(mutation.operationId)
            val player = mutableState.value.player
            if (player.pending(mutation.action)?.operationId != mutation.operationId) return
            val accepted = if (mutation.action == PlaybackAction.PlayTrack) {
                mutation.copy(requestSucceeded = true)
            } else mutation
            mutableState.update { state ->
                val current = state.player.pending(mutation.action)
                if (current?.operationId != mutation.operationId) state else state.copy(
                    player = state.player.copy(
                        pendingMutations = state.player.pendingMutations +
                            (mutation.action to accepted),
                    ),
                )
            }
            scheduleMutationReconciliation(accepted)
        }

        override fun failure(message: String, unauthorized: Boolean) =
            failure(message, unauthorized, BridgeClient.ErrorCode.NONE)

        override fun failure(message: String, unauthorized: Boolean, code: BridgeClient.ErrorCode) {
            commandRequests.remove(mutation.operationId)
            val current = mutableState.value.player
            if (current.pending(mutation.action)?.operationId != mutation.operationId) return
            mutationTimeoutJobs.remove(mutation.operationId)?.cancel()
            val authoritative = latestAuthoritativeState
            val withoutFailed = current.copy(
                pendingMutations = current.pendingMutations - mutation.action,
            )
            val restored = if (authoritative != null) {
                mergePlaybackState(
                    withoutFailed,
                    authoritative,
                )
            } else when (mutation.action) {
                PlaybackAction.PlayTrack -> rollback
                PlaybackAction.PlayPause -> current.copy(playing = rollback.playing)
                PlaybackAction.Position -> current.copy(position = rollback.position)
                PlaybackAction.Volume -> current.copy(volume = rollback.volume)
                PlaybackAction.Shuffle -> current.copy(shuffleEnabled = rollback.shuffleEnabled)
                PlaybackAction.Repeat -> current.copy(repeatMode = rollback.repeatMode)
                PlaybackAction.Previous, PlaybackAction.Next -> current
            }
            withoutFailed.pendingMutations.values
                .filter { it.action !in restored.pendingMutations }
                .forEach { settled ->
                    mutationTimeoutJobs.remove(settled.operationId)?.cancel()
                }
            // A typed bridge reason ("iTunes is showing a message…") says more than the generic
            // copy; legacy bridges keep the action-specific message.
            val reasonRes = playbackFailureRes(code)
            val failureCopy = getApplication<Application>().getString(
                reasonRes ?: failureRes,
                *(if (reasonRes == null) failureArguments else emptyList()).toTypedArray(),
            )
            mutableState.update {
                it.copy(player = restored.copy(
                    pendingMutations = restored.pendingMutations - mutation.action,
                    commandError = failureCopy,
                ))
            }
            if (mutation.action == PlaybackAction.PlayTrack) loadArtwork(restored.artworkId)
            if (reasonRes != null) announce(reasonRes, haptic = HapticIntent.Reject)
            else announce(failureRes, failureArguments, HapticIntent.Reject)
        }
    }

internal fun TunesLinkViewModel.pendingMutation(
    action: PlaybackAction,
    previous: PlayerUiState,
    value: Double? = null,
    expectedTrackId: String? = null,
): PendingMutation = PendingMutation(
    operationId = ++nextOperationId,
    action = action,
    affectedFields = action.playerFields(),
    expectedBoolean = when (action) {
        PlaybackAction.PlayPause -> !previous.playing
        PlaybackAction.Shuffle -> (value ?: 0.0) >= 0.5
        else -> null
    },
    expectedNumber = when (action) {
        PlaybackAction.Position, PlaybackAction.Volume -> value
        else -> null
    },
    expectedRepeat = if (action == PlaybackAction.Repeat) {
        when ((value ?: 0.0).toInt()) {
            1 -> RepeatMode.One
            2 -> RepeatMode.All
            else -> RepeatMode.Off
        }
    } else {
        null
    },
    expectedTrackId = expectedTrackId,
    previousTrackId = previous.trackId,
    startedAtMillis = System.currentTimeMillis(),
    deadlineMillis = when (action) {
        PlaybackAction.PlayTrack, PlaybackAction.Previous, PlaybackAction.Shuffle, PlaybackAction.Repeat ->
            BridgeClient.PLAYBACK_READ_TIMEOUT_MS.toLong()
        else -> 4_000L
    },
)

internal fun TunesLinkViewModel.scheduleMutationReconciliation(mutation: PendingMutation) {
    mutationTimeoutJobs.remove(mutation.operationId)?.cancel()
    mutationTimeoutJobs[mutation.operationId] = viewModelScope.launch {
        delay(mutation.timeoutMillis)
        if (!requestMutationRefresh(mutation)) return@launch
        delay(mutation.deadlineMillis)
        settleUnconfirmedMutation(mutation)
    }
}

private fun TunesLinkViewModel.requestMutationRefresh(mutation: PendingMutation): Boolean {
    if (mutableState.value.player.pending(mutation.action)?.operationId != mutation.operationId) {
        return false
    }
    repository.requestStateRefresh()
    repository.refreshState(object : BridgeClient.Result<BridgeClient.PlayerState> {
        override fun success(value: BridgeClient.PlayerState) {
            applyAuthoritativeState(value)
        }

        override fun failure(message: String, unauthorized: Boolean) = Unit
    })
    return true
}

private fun TunesLinkViewModel.settleUnconfirmedMutation(mutation: PendingMutation) {
    if (mutableState.value.player.pending(mutation.action)?.operationId != mutation.operationId) {
        return
    }
    val authoritative = latestAuthoritativeState
    val notApplied = getApplication<Application>()
        .getString(R.string.playback_change_not_applied)
    mutableState.update { state ->
        val pending = state.player.pending(mutation.action)
        if (pending?.operationId != mutation.operationId) return@update state
        val withoutExpired = state.player.copy(
            pendingMutations = state.player.pendingMutations - mutation.action,
        )
        val settled = if (authoritative == null) {
            withoutExpired
        } else {
            mergePlaybackState(withoutExpired, authoritative)
        }
        state.copy(player = settled.copy(commandError = notApplied))
    }
    mutationTimeoutJobs.remove(mutation.operationId)
    announce(R.string.playback_change_not_applied, haptic = HapticIntent.Reject)
    if (mutation.action == PlaybackAction.PlayTrack) {
        loadArtwork(mutableState.value.player.artworkId)
    }
}

internal fun TunesLinkViewModel.announceMutationSuccess(action: PlaybackAction, player: PlayerUiState) {
    val resource = when (action) {
        PlaybackAction.PlayTrack -> R.string.playback_playing
        PlaybackAction.PlayPause -> if (player.playing) R.string.playback_playing else R.string.playback_paused
        PlaybackAction.Shuffle -> if (player.shuffleEnabled) R.string.shuffle_on else R.string.shuffle_off
        PlaybackAction.Repeat -> when (player.repeatMode) {
            RepeatMode.Off -> R.string.repeat_off
            RepeatMode.All -> R.string.repeat_all
            RepeatMode.One -> R.string.repeat_one
        }
        else -> R.string.playback_updated
    }
    announce(resource, haptic = HapticIntent.Confirm)
}

/**
 * Loads the current cover. The refresh clock only advances when a load settles; a failure
 * retries with a short backoff while the same cover is still current.
 */
internal fun TunesLinkViewModel.loadArtwork(artworkId: String) {
    artworkRequest.cancel()
    artworkRetryJob?.cancel()
    artworkRetryJob = null
    if (artworkId != artworkFailureId) {
        artworkFailureId = artworkId
        artworkFailures = 0
    }
    if (artworkId.isBlank()) {
        artworkLoaded(artworkId, null)
        return
    }
    // Not due again until this request settles.
    artworkDueAt = Long.MAX_VALUE
    mutableState.update {
        it.copy(player = it.player.copy(artworkState = ArtworkLoadState.Loading(it.player.artwork)))
    }
    artworkRequest = repository.getArtwork(artworkId, TunesLinkViewModel.ARTWORK_SIZE, object : BridgeRepository.ArtworkResult {
        override fun cached(bitmap: Bitmap) {
            // A saved cover does not settle the refresh or reset its retry backoff.
            if (mutableState.value.player.artworkId == artworkId) displayArtwork(artworkId, bitmap)
        }

        override fun success(value: Bitmap?) {
            if (mutableState.value.player.artworkId == artworkId) artworkLoaded(artworkId, value)
        }

        override fun failure(message: String, unauthorized: Boolean) {
            Log.w(TunesLinkViewModel.TAG, "Artwork request failed: ${message.take(120)}")
            if (mutableState.value.player.artworkId != artworkId) return
            mutableState.update { it.copy(player = it.player.withArtworkFailure(message.take(120))) }
            val retryDelay = artworkRetryDelayMillis(++artworkFailures)
            artworkDueAt = System.currentTimeMillis() + retryDelay
            artworkRetryJob = viewModelScope.launch {
                delay(retryDelay)
                val current = mutableState.value
                if (current.player.artworkId == artworkId &&
                    current.connection is ConnectionState.Connected
                ) loadArtwork(artworkId)
            }
        }
    })
}

private fun TunesLinkViewModel.artworkLoaded(artworkId: String, bitmap: Bitmap?) {
    artworkFailures = 0
    artworkDueAt = System.currentTimeMillis() + ArtworkDiskCache.REFRESH_INTERVAL_MS
    displayArtwork(artworkId, bitmap)
}

private fun TunesLinkViewModel.displayArtwork(artworkId: String, bitmap: Bitmap?) {
    mutableState.update {
        it.copy(
            player = it.player.copy(
                artworkState = bitmap?.let(ArtworkLoadState::Ready) ?: ArtworkLoadState.Missing,
                artworkOwnerId = if (bitmap == null) "" else artworkId,
            ),
        )
    }
}

/** Reloads when the cover changed, or when its refresh or retry time has come. */
internal fun TunesLinkViewModel.refreshArtworkIfDue(previousArtworkId: String, artworkId: String) {
    if (previousArtworkId != artworkId || System.currentTimeMillis() >= artworkDueAt) {
        loadArtwork(artworkId)
    }
}
