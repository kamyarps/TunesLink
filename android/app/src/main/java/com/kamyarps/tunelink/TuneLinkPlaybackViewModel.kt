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
    if (previous.hasPendingConflict(action)) return
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
    scheduleMutationReconciliation(mutation)
    repository.command(
        checkNotNull(action.wireCommand),
        value,
        commandResult(mutation, previous, R.string.playback_command_failed),
    )
}

internal fun TunesLinkViewModel.commandResult(
    mutation: PendingMutation,
    rollback: PlayerUiState,
    failureRes: Int,
    failureArguments: List<Any> = emptyList(),
) =
    object : BridgeClient.Result<Boolean> {
        override fun success(value: Boolean) {
            val player = mutableState.value.player
            if (player.pending(mutation.action)?.operationId != mutation.operationId) return
            // HTTP success means the bridge accepted the command. The mutation remains pending
            // until the SSE stream confirms the resulting authoritative player state.
        }

        override fun failure(message: String, unauthorized: Boolean) {
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
            val failureCopy = getApplication<Application>().getString(
                failureRes,
                *failureArguments.toTypedArray(),
            )
            mutableState.update {
                it.copy(player = restored.copy(
                    pendingMutations = restored.pendingMutations - mutation.action,
                    commandError = failureCopy,
                ))
            }
            if (mutation.action == PlaybackAction.PlayTrack) loadArtwork(restored.artworkId)
            announce(failureRes, failureArguments, HapticIntent.Reject)
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
)

internal fun TunesLinkViewModel.scheduleMutationReconciliation(mutation: PendingMutation) {
    mutationTimeoutJobs.remove(mutation.operationId)?.cancel()
    mutationTimeoutJobs[mutation.operationId] = viewModelScope.launch {
        delay(mutation.timeoutMillis)
        val current = mutableState.value.player.pending(mutation.action)
        if (current?.operationId != mutation.operationId) return@launch
        mutableState.update { state ->
            val pending = state.player.pending(mutation.action)
            if (pending?.operationId != mutation.operationId) state else state.copy(
                player = state.player.copy(
                    pendingMutations = state.player.pendingMutations +
                        (mutation.action to pending.copy(refreshRequested = true)),
                ),
            )
        }
        repository.requestStateRefresh()
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

internal fun TunesLinkViewModel.loadArtwork(artworkId: String) {
    artworkRequest.cancel()
    if (artworkId.isBlank()) {
        mutableState.update { it.copy(player = it.player.copy(artworkState = ArtworkLoadState.Missing)) }
        return
    }
    repository.cachedArtwork(artworkId, TunesLinkViewModel.ARTWORK_SIZE)?.let { bitmap ->
        mutableState.update { it.copy(player = it.player.copy(artworkState = ArtworkLoadState.Ready(bitmap))) }
        return
    }
    mutableState.update {
        it.copy(player = it.player.copy(artworkState = ArtworkLoadState.Loading(it.player.artwork)))
    }
    artworkRequest = repository.getArtwork(artworkId, TunesLinkViewModel.ARTWORK_SIZE, object : BridgeClient.Result<Bitmap> {
        override fun success(value: Bitmap?) {
            if (mutableState.value.player.artworkId == artworkId) {
                mutableState.update {
                    it.copy(
                        player = it.player.copy(
                            artworkState = value?.let(ArtworkLoadState::Ready) ?: ArtworkLoadState.Missing,
                        ),
                    )
                }
            }
        }

        override fun failure(message: String, unauthorized: Boolean) {
            Log.w(TunesLinkViewModel.TAG, "Artwork request failed: ${message.take(120)}")
            if (mutableState.value.player.artworkId == artworkId) {
                mutableState.update {
                    it.copy(
                        player = it.player.copy(
                            artworkState = ArtworkLoadState.FailedRetainingPrevious(
                                it.player.artwork,
                                message.take(120),
                            ),
                        ),
                    )
                }
            }
        }
    })
}
