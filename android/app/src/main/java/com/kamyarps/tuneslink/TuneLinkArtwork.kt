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
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Refresh visible artwork on the cache's bounded schedule, only while the app is visible. */
@Composable
internal fun rememberLibraryArtwork(artworkId: String, size: Int, viewModel: TunesLinkViewModel): Bitmap? {
    var artwork by remember(artworkId, size) { mutableStateOf<Bitmap?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(artworkId, size, viewModel, lifecycle) {
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
