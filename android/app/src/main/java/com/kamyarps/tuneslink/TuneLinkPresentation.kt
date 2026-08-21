package com.kamyarps.tuneslink

import android.graphics.Bitmap

internal enum class PairingPhase { Editing, Submitting, Success }

internal enum class PlaybackAction(val wireCommand: String?) {
    PlayTrack(null),
    PlayPause("playPause"),
    Previous("previous"),
    Next("next"),
    Position("position"),
    Volume("volume"),
    Shuffle("shuffle"),
    Repeat("repeat"),
}

internal enum class PlayerField {
    Playing,
    Metadata,
    Position,
    Volume,
    Shuffle,
    Repeat,
}

internal fun PlaybackAction.playerFields(): Set<PlayerField> = when (this) {
    PlaybackAction.PlayPause -> setOf(PlayerField.Playing)
    PlaybackAction.Position -> setOf(PlayerField.Position)
    PlaybackAction.Volume -> setOf(PlayerField.Volume)
    PlaybackAction.Shuffle -> setOf(PlayerField.Shuffle)
    PlaybackAction.Repeat -> setOf(PlayerField.Repeat)
    PlaybackAction.Previous, PlaybackAction.Next -> setOf(PlayerField.Metadata, PlayerField.Position)
    PlaybackAction.PlayTrack -> setOf(PlayerField.Playing, PlayerField.Metadata, PlayerField.Position)
}

/**
 * Describes the authoritative state change an optimistic command is waiting for. Keeping this in
 * the presentation model lets SSE frames be reduced deterministically instead of racing HTTP
 * callbacks against the stream.
 */
internal data class PendingMutation(
    val operationId: Long,
    val action: PlaybackAction,
    val affectedFields: Set<PlayerField>,
    val expectedBoolean: Boolean? = null,
    val expectedNumber: Double? = null,
    val expectedRepeat: RepeatMode? = null,
    val expectedTrackId: String? = null,
    val previousTrackId: String? = null,
    val startedAtMillis: Long,
    val timeoutMillis: Long = 2_000,
    val deadlineMillis: Long = 4_000,
) {
    fun matches(state: BridgeClient.PlayerState): Boolean = when (action) {
        PlaybackAction.PlayPause -> state.playing == expectedBoolean
        PlaybackAction.Position -> kotlin.math.abs(state.position - (expectedNumber ?: state.position)) <= 3.0
        PlaybackAction.Volume -> kotlin.math.abs(
            state.volume.toDouble() - (expectedNumber ?: state.volume.toDouble()),
        ) <= 1.0
        PlaybackAction.Shuffle -> state.shuffleEnabled == expectedBoolean
        PlaybackAction.Repeat -> RepeatMode.fromWire(state.repeatMode) == expectedRepeat
        PlaybackAction.Next -> state.trackId != previousTrackId
        PlaybackAction.Previous ->
            state.trackId != previousTrackId || state.position <= 3.0
        PlaybackAction.PlayTrack -> state.trackId == expectedTrackId
    }
}

internal data class PairingUiState(
    val code: String = "",
    val codeError: String? = null,
    val phase: PairingPhase = PairingPhase.Editing,
) {
    val canSubmit: Boolean
        get() = code.length == 6 && phase == PairingPhase.Editing
}

internal sealed interface ArtworkLoadState {
    val visibleBitmap: Bitmap?

    data object Empty : ArtworkLoadState {
        override val visibleBitmap: Bitmap? = null
    }

    data class Loading(override val visibleBitmap: Bitmap?) : ArtworkLoadState

    data class Ready(val bitmap: Bitmap) : ArtworkLoadState {
        override val visibleBitmap: Bitmap = bitmap
    }

    data object Missing : ArtworkLoadState {
        override val visibleBitmap: Bitmap? = null
    }

    data class FailedRetainingPrevious(
        override val visibleBitmap: Bitmap?,
        val diagnostic: String,
    ) : ArtworkLoadState
}

internal enum class ConnectionAvailabilityKind {
    Available,
    Connecting,
    ConnectionLost,
    PairingExpired,
    IdentityChanged,
}

internal data class ConnectionAvailability(
    val kind: ConnectionAvailabilityKind,
    val titleRes: Int,
    val detailRes: Int,
    val detailArguments: List<Any> = emptyList(),
    val primaryLabelRes: Int? = null,
    val controlsEnabled: Boolean,
) {
    val visible: Boolean get() = kind !in setOf(
        ConnectionAvailabilityKind.Available,
        ConnectionAvailabilityKind.Connecting,
    )

    companion object {
        fun from(state: ConnectionState): ConnectionAvailability = when (state) {
            is ConnectionState.Connected -> ConnectionAvailability(
                ConnectionAvailabilityKind.Available,
                titleRes = R.string.connected,
                detailRes = R.string.connected_to,
                detailArguments = listOf(state.computer),
                controlsEnabled = true,
            )
            ConnectionState.Connecting -> ConnectionAvailability(
                ConnectionAvailabilityKind.Connecting,
                titleRes = R.string.connecting,
                detailRes = R.string.restoring_local_connection,
                controlsEnabled = false,
            )
            is ConnectionState.RecoverableFailure -> ConnectionAvailability(
                ConnectionAvailabilityKind.ConnectionLost,
                titleRes = R.string.connection_lost,
                detailRes = R.string.connection_lost_detail,
                detailArguments = listOf(state.computer),
                primaryLabelRes = R.string.try_again,
                controlsEnabled = false,
            )
            is ConnectionState.Unauthorized -> ConnectionAvailability(
                ConnectionAvailabilityKind.PairingExpired,
                titleRes = R.string.pairing_expired,
                detailRes = R.string.pairing_expired_detail,
                detailArguments = listOf(state.computer),
                primaryLabelRes = R.string.pair_again,
                controlsEnabled = false,
            )
            is ConnectionState.IdentityChanged -> ConnectionAvailability(
                ConnectionAvailabilityKind.IdentityChanged,
                titleRes = R.string.security_id_changed,
                detailRes = R.string.security_id_changed_detail,
                detailArguments = listOf(state.computer),
                primaryLabelRes = R.string.pair_again,
                controlsEnabled = false,
            )
            ConnectionState.Unpaired, ConnectionState.Discovering, ConnectionState.Pairing ->
                ConnectionAvailability(
                    ConnectionAvailabilityKind.Connecting,
                    titleRes = R.string.not_connected,
                    detailRes = R.string.choose_computer_to_continue,
                    controlsEnabled = false,
                )
        }
    }
}

internal data class NavigationState(
    val destination: TunesLinkDestination = TunesLinkDestination.Library,
    val switchingComputer: Boolean = false,
)

/** One row of a browse list that shows an artist's or a genre's songs album by album. */
internal sealed interface LibraryBrowseRow {
    val key: String

    data class AlbumHeading(
        override val key: String,
        val album: String,
        val artworkId: String,
        val trackCount: Int,
    ) : LibraryBrowseRow

    data class Song(val track: TrackUiState, val position: Int) : LibraryBrowseRow {
        override val key: String get() = "song:${track.id}"
    }
}

internal data class LibraryBrowseAlbum(
    val heading: LibraryBrowseRow.AlbumHeading,
    val songs: List<LibraryBrowseRow.Song>,
)

/**
 * Splits an already ordered track list into albums. The bridge returns a collection album by
 * album, so each album is a run of neighbouring songs; grouping the runs rather than collecting
 * matching titles keeps the displayed order identical to the order the bridge will play, and lets
 * two albums that happen to share a title stay apart.
 */
internal fun libraryBrowseAlbums(tracks: List<TrackUiState>): List<LibraryBrowseAlbum> {
    val albums = mutableListOf<LibraryBrowseAlbum>()
    var start = 0
    while (start < tracks.size) {
        val album = tracks[start].album
        val albumArtist = tracks[start].albumArtist
        var end = start
        while (end < tracks.size && tracks[end].album == album &&
            tracks[end].albumArtist == albumArtist
        ) {
            end++
        }
        val run = tracks.subList(start, end)
        albums += LibraryBrowseAlbum(
            heading = LibraryBrowseRow.AlbumHeading(
                key = "album:${run.first().id}",
                album = album,
                artworkId = run.firstOrNull { it.artworkId.isNotBlank() }?.artworkId.orEmpty(),
                trackCount = run.size,
            ),
            songs = run.mapIndexed { offset, track ->
                LibraryBrowseRow.Song(track, track.trackNumber.takeIf { it > 0 } ?: offset + 1)
            },
        )
        start = end
    }
    return albums
}

/** The same albums flattened for a single-column list. */
internal fun libraryBrowseRows(tracks: List<TrackUiState>): List<LibraryBrowseRow> =
    libraryBrowseAlbums(tracks).flatMap { album -> listOf(album.heading) + album.songs }

/**
 * An artist or a genre is browsed to reach an album, so their songs are shown album by album.
 * Albums are already one album, and a playlist keeps the order it was arranged in.
 */
internal fun LibraryBrowseUiState.groupsTracksByAlbum(): Boolean =
    selectedCollection?.kind == LibraryBrowseKind.Artists ||
        selectedCollection?.kind == LibraryBrowseKind.Genres

/**
 * Keeps the connected destinations in one root content slot while giving every other route its own
 * transition identity. The route itself remains the AnimatedContent target so outgoing content
 * never has to read or cast a newer route while it is animating away.
 */
internal fun TunesLinkRoute.rootContentKey(): Int = when (this) {
    TunesLinkRoute.Welcome -> 0
    TunesLinkRoute.LocalNetworkPermission -> 1
    TunesLinkRoute.Connecting -> 2
    is TunesLinkRoute.Connected -> 3
}

internal enum class PendingPermissionAction { Discover, ManualAddress, Reconnect, RetryRevocations }

internal enum class NavigationBackAction { DismissModal, CancelSearch, ShowLibrary, System }

internal fun shouldPresentConnectionRecoveryDialog(
    availability: ConnectionAvailability,
    dismissedKind: ConnectionAvailabilityKind?,
    hasModal: Boolean,
    connectedRoute: Boolean,
): Boolean = connectedRoute && !hasModal && availability.visible &&
    dismissedKind != availability.kind

internal fun playerHasTrack(player: PlayerUiState): Boolean =
    player.title.isNotBlank() || player.trackId.isNotBlank()

internal fun playerIdleSubtitle(player: PlayerUiState, unavailableHint: Boolean): Int? = when {
    playerHasTrack(player) -> null
    player.iTunesAvailable -> R.string.choose_a_song
    unavailableHint -> R.string.open_itunes
    else -> null
}

internal fun searchBackEnabled(
    destination: TunesLinkDestination?,
    searchActive: Boolean,
    editingQuery: String,
): Boolean = destination == TunesLinkDestination.Search &&
    (searchActive || editingQuery.isNotEmpty())

internal fun navigationBackAction(
    hasModal: Boolean,
    searchActive: Boolean,
    destination: TunesLinkDestination?,
): NavigationBackAction = when {
    hasModal -> NavigationBackAction.DismissModal
    searchActive -> NavigationBackAction.CancelSearch
    destination != null && destination != TunesLinkDestination.Library ->
        NavigationBackAction.ShowLibrary
    else -> NavigationBackAction.System
}

internal data class ModalPresentation(
    val destination: TunesLinkModal,
    val returnTo: TunesLinkModal? = null,
    val dismissRequested: Boolean = false,
    val replacement: ModalPresentation? = null,
)

internal fun mergePlaybackState(
    current: PlayerUiState,
    incoming: BridgeClient.PlayerState,
): PlayerUiState {
    val completed = current.pendingMutations.values.filter { mutation ->
        mutation.matches(incoming)
    }.map(PendingMutation::action).toSet()
    val remaining = current.pendingMutations - completed
    val protectedFields = remaining.values.flatMap(PendingMutation::affectedFields).toSet()
    val preserveMetadata = PlayerField.Metadata in protectedFields
    val nextArtworkId = if (preserveMetadata) current.artworkId else incoming.artworkId
    return current.copy(
        iTunesAvailable = incoming.iTunesAvailable,
        playing = if (PlayerField.Playing in protectedFields) current.playing else incoming.playing,
        title = if (preserveMetadata) current.title else incoming.title,
        artist = if (preserveMetadata) current.artist else incoming.artist,
        album = if (preserveMetadata) current.album else incoming.album,
        duration = if (preserveMetadata) current.duration else incoming.duration,
        position = if (PlayerField.Position in protectedFields) current.position else incoming.position,
        volume = if (PlayerField.Volume in protectedFields) current.volume else incoming.volume,
        artworkId = nextArtworkId,
        trackId = if (preserveMetadata) current.trackId else incoming.trackId,
        shuffleEnabled = if (PlayerField.Shuffle in protectedFields) {
            current.shuffleEnabled
        } else {
            incoming.shuffleEnabled
        },
        repeatMode = if (PlayerField.Repeat in protectedFields) {
            current.repeatMode
        } else {
            RepeatMode.fromWire(incoming.repeatMode)
        },
        artworkState = when {
            current.artworkId == nextArtworkId -> current.artworkState
            nextArtworkId.isBlank() -> ArtworkLoadState.Missing
            else -> ArtworkLoadState.Loading(current.artwork)
        },
        pendingMutations = remaining,
    )
}
