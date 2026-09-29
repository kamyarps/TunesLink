package com.kamyarps.tuneslink

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive

/** The small artwork capability that library rows need from the screen owner. */
internal class LibraryArtworkSource(
    private val repository: BridgeRepository,
    val session: StateFlow<Long>,
) {
    fun cached(artworkId: String, size: Int): Bitmap? =
        if (artworkId.isBlank()) null else repository.cachedArtwork(artworkId, size)

    fun request(artworkId: String, size: Int, result: BridgeRepository.ArtworkResult):
        BridgeRepository.RequestHandle = repository.getArtwork(artworkId, size, result)
}

@Composable
internal fun rememberLibraryArtworkSource(viewModel: TunesLinkViewModel): LibraryArtworkSource =
    remember(viewModel) { LibraryArtworkSource(viewModel.repository, viewModel.artworkSession) }

/** Refresh visible artwork on the cache's bounded schedule, only while the app is visible. */
@Composable
internal fun rememberLibraryArtwork(artworkId: String, size: Int, source: LibraryArtworkSource): Bitmap? {
    // Seed synchronously from the bridge-scoped memory cache so a recycled row shows its artwork
    // on its first frame. ArtworkSurface only crossfades values that arrive after composition.
    var artwork by remember(artworkId, size, source) {
        mutableStateOf(source.cached(artworkId, size))
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val session by source.session.collectAsStateWithLifecycle()
    LaunchedEffect(artworkId, size, source, lifecycle, session) {
        if (artworkId.isBlank()) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val request = source.request(artworkId, size, object : BridgeRepository.ArtworkResult {
                    override fun success(value: Bitmap?) { artwork = value }
                    override fun failure(message: String, unauthorized: Boolean) = Unit
                })
                try { delay(ArtworkDiskCache.REFRESH_INTERVAL_MS) }
                finally { request.cancel() }
            }
        }
    }
    return artwork
}
