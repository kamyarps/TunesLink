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
import kotlinx.coroutines.isActive

/** Refresh visible artwork on the cache's bounded schedule, only while the app is visible. */
@Composable
internal fun rememberLibraryArtwork(artworkId: String, size: Int, viewModel: TunesLinkViewModel): Bitmap? {
    // Seed synchronously from the bridge-scoped memory cache so a recycled row shows its artwork
    // on its first frame. ArtworkSurface only crossfades values that arrive after composition.
    var artwork by remember(artworkId, size, viewModel) {
        mutableStateOf(viewModel.cachedLibraryArtwork(artworkId, size))
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val session by viewModel.artworkSession.collectAsStateWithLifecycle()
    LaunchedEffect(artworkId, size, viewModel, lifecycle, session) {
        if (artworkId.isBlank()) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val request = viewModel.requestArtwork(artworkId, size, object : BridgeClient.Result<Bitmap> {
                    override fun success(value: Bitmap?) { artwork = value }
                    override fun failure(message: String, unauthorized: Boolean) = Unit
                })
                try { delay(ArtworkDiskCache.MAX_AGE_MS) }
                finally { request.cancel() }
            }
        }
    }
    return artwork
}

/** A synchronous memory-cache peek; never touches disk or the network. */
internal fun TunesLinkViewModel.cachedLibraryArtwork(artworkId: String, size: Int): Bitmap? =
    if (artworkId.isBlank()) null else repository.cachedArtwork(artworkId, size)
