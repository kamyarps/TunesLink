package com.kamyarps.tuneslink

import android.graphics.Bitmap
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

internal enum class TunesLinkDestination { Library, NowPlaying, Search }

internal enum class RepeatMode(val wireValue: Double) {
    Off(0.0),
    All(2.0),
    One(1.0),
    ;

    fun next(): RepeatMode = when (this) {
        Off -> All
        All -> One
        One -> Off
    }

    companion object {
        fun fromWire(value: String): RepeatMode = when (value) {
            "all" -> All
            "one" -> One
            else -> Off
        }
    }
}

internal enum class LibraryBrowseKind(val wireValue: String) {
    Playlists("playlists"),
    Artists("artists"),
    Albums("albums"),
    Songs("songs"),
    Genres("genres"),
}

internal sealed interface TunesLinkRoute {
    data object Welcome : TunesLinkRoute
    data object LocalNetworkPermission : TunesLinkRoute
    data object Connecting : TunesLinkRoute
    data class Connected(val destination: TunesLinkDestination) : TunesLinkRoute
}

internal sealed interface ConnectionState {
    data object Unpaired : ConnectionState
    data object Discovering : ConnectionState
    data object Pairing : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val computer: String) : ConnectionState
    data class RecoverableFailure(val computer: String) : ConnectionState
    data class Unauthorized(val computer: String) : ConnectionState
    data class IdentityChanged(val computer: String) : ConnectionState
}

internal data class PlayerUiState(
    val iTunesAvailable: Boolean = true,
    val playing: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val duration: Double = 0.0,
    val position: Double = 0.0,
    val volume: Int = 0,
    val artworkId: String = "",
    val trackId: String = "",
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.Off,
    val artworkState: ArtworkLoadState = ArtworkLoadState.Empty,
    val pendingMutations: Map<PlaybackAction, PendingMutation> = emptyMap(),
    val commandError: String? = null,
) {
    val artwork: Bitmap? get() = artworkState.visibleBitmap

    fun pending(action: PlaybackAction): PendingMutation? = pendingMutations[action]

    fun hasPendingConflict(action: PlaybackAction): Boolean {
        val fields = action.playerFields()
        return pendingMutations.values.any { mutation ->
            mutation.affectedFields.any(fields::contains)
        }
    }
}

internal enum class HapticIntent { None, Confirm, Reject }

internal data class UiAnnouncement(
    val id: Long,
    val messageRes: Int,
    val arguments: List<Any> = emptyList(),
    val quantity: Int? = null,
    val hapticIntent: HapticIntent = HapticIntent.None,
)

internal data class TrackUiState(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Double,
    val artworkId: String,
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
    val albumArtist: String = "",
)

internal data class LibraryCollectionUiState(
    val id: String,
    val title: String,
    val subtitle: String,
    val trackCount: Int,
    val artworkId: String,
)

internal data class SelectedLibraryCollection(
    val kind: LibraryBrowseKind,
    val id: String,
    val title: String,
    val subtitle: String,
)

internal enum class LibraryBrowseTarget { Collections, Tracks }

internal data class LibraryPageCursor(
    val total: Int = 0,
    val windowStart: Int = 0,
    val revision: String = "",
    val hasMore: Boolean = false,
    val hasPrevious: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isLoadingPrevious: Boolean = false,
    val error: String? = null,
) {
    val isBusy: Boolean get() = isLoading || isLoadingMore || isLoadingPrevious
}

internal data class LibraryBrowseUiState(
    val kind: LibraryBrowseKind? = null,
    val selectedCollection: SelectedLibraryCollection? = null,
    val collections: List<LibraryCollectionUiState> = emptyList(),
    val tracks: List<TrackUiState> = emptyList(),
    val collectionsCursor: LibraryPageCursor = LibraryPageCursor(),
    val tracksCursor: LibraryPageCursor = LibraryPageCursor(),
) {
    val canNavigateUp: Boolean get() = kind != null
    val showingTracks: Boolean
        get() = kind == LibraryBrowseKind.Songs || selectedCollection != null
    val visibleTarget: LibraryBrowseTarget
        get() = if (showingTracks) LibraryBrowseTarget.Tracks else LibraryBrowseTarget.Collections

    fun cursor(target: LibraryBrowseTarget): LibraryPageCursor =
        if (target == LibraryBrowseTarget.Tracks) tracksCursor else collectionsCursor

    fun withCursor(target: LibraryBrowseTarget, cursor: LibraryPageCursor): LibraryBrowseUiState =
        if (target == LibraryBrowseTarget.Tracks) {
            copy(tracksCursor = cursor)
        } else {
            copy(collectionsCursor = cursor)
        }

    val visibleCursor: LibraryPageCursor get() = cursor(visibleTarget)
    val total: Int get() = visibleCursor.total
    val hasMore: Boolean get() = visibleCursor.hasMore
    val isLoading: Boolean get() = visibleCursor.isLoading
    val isLoadingMore: Boolean get() = visibleCursor.isLoadingMore
    val isLoadingPrevious: Boolean get() = visibleCursor.isLoadingPrevious
    val error: String? get() = visibleCursor.error
}

internal data class LibraryUiState(
    val editingQuery: String = "",
    val loadedQuery: String? = null,
    val items: List<TrackUiState> = emptyList(),
    val total: Int = 0,
    val windowStart: Int = 0,
    val revision: String = "",
    val hasMore: Boolean = false,
    val hasPrevious: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isLoadingPrevious: Boolean = false,
    val error: String? = null,
    val searchActive: Boolean = false,
)

internal sealed interface TunesLinkModal {
    data class Pairing(val bridge: BridgeClient.BridgeInfo) : TunesLinkModal
    data object ManualAddress : TunesLinkModal
    data object ConnectionDetails : TunesLinkModal
    data object Privacy : TunesLinkModal
    data object ForgetConfirmation : TunesLinkModal
}

internal data class TunesLinkUiState(
    val route: TunesLinkRoute = TunesLinkRoute.Welcome,
    val connection: ConnectionState = ConnectionState.Unpaired,
    val bridgeName: String = "",
    val bridgeAddress: String = "",
    val navigation: NavigationState = NavigationState(),
    val modalPresentation: ModalPresentation? = null,
    val discovered: List<BridgeClient.BridgeInfo> = emptyList(),
    val discoveryError: String? = null,
    val manualAddress: String = "",
    val manualAddressError: String? = null,
    val pairing: PairingUiState = PairingUiState(),
    val library: LibraryUiState = LibraryUiState(),
    val browse: LibraryBrowseUiState = LibraryBrowseUiState(),
    val player: PlayerUiState = PlayerUiState(),
    val announcement: UiAnnouncement? = null,
    val manualResolutionBusy: Boolean = false,
    val revocationRetryBusy: Boolean = false,
    val pendingRevocationCount: Int = 0,
    val forgetBusy: Boolean = false,
    val forgetError: String? = null,
) {
    val modal: TunesLinkModal? get() = modalPresentation?.destination
    val pairingCode: String get() = pairing.code
    val pairingBusy: Boolean get() = pairing.phase == PairingPhase.Submitting
}

@OptIn(FlowPreview::class)
internal fun Flow<String>.committedSearchQueries(): Flow<String> =
    debounce { query -> if (query.isEmpty()) 0L else 250L }.distinctUntilChanged()
