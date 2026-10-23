package com.s3vault.app.presentation.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
import com.s3vault.app.domain.RemoteObject
import com.s3vault.app.presentation.ui.rememberReducedMotion
import com.s3vault.app.presentation.ui.skeleton
import com.s3vault.app.sync.PreviewQuality
import com.s3vault.app.sync.PreviewResult
import kotlinx.coroutines.CancellationException
import java.io.File

/** What an S3 photo can show right now. Never reads the original unless the user restores it. */
sealed interface PreviewState {
    data object Loading : PreviewState
    data class Ready(val file: File, val quality: PreviewQuality) : PreviewState

    /** No preview object and no embedded thumbnail: only a download would show it. */
    data object Unavailable : PreviewState

    data class Failed(val message: String, val needsCredentials: Boolean, val notFound: Boolean = false) : PreviewState
}

/**
 * Loads the preview of [obj] while it is composed (so scrolled-away rows cancel their request). [attempt] is bumped
 * by a "Reintentar" button to run the lookup again after a transient failure.
 */
@Composable
fun rememberPreviewState(obj: RemoteObject, attempt: Int, load: suspend (RemoteObject) -> PreviewResult): State<PreviewState> {
    val loader = rememberUpdatedState(load)
    return produceState<PreviewState>(PreviewState.Loading, obj.versionId, attempt) {
        value = PreviewState.Loading
        value = try {
            when (val r = loader.value(obj)) {
                is PreviewResult.Ready -> PreviewState.Ready(r.file, r.quality)
                PreviewResult.Unavailable -> PreviewState.Unavailable
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteException) {
            PreviewState.Failed(e.message ?: e.kind.userMessage, e.needsCredentials, notFound = e.kind == RemoteErrorKind.OBJECT_NOT_FOUND)
        } catch (e: Exception) {
            PreviewState.Failed(RemoteErrorKind.UNKNOWN.userMessage, false)
        }
    }
}

/** Square thumbnail for list rows: skeleton while loading, the image when ready, a quiet placeholder otherwise. */
@Composable
fun RemoteThumb(obj: RemoteObject, load: suspend (RemoteObject) -> PreviewResult, modifier: Modifier = Modifier) {
    val state by rememberPreviewState(obj, 0, load)
    val reduced = rememberReducedMotion()
    val ctx = LocalContext.current
    Box(modifier, contentAlignment = Alignment.Center) {
        when (val s = state) {
            PreviewState.Loading -> Box(Modifier.fillMaxSize().skeleton())
            is PreviewState.Ready -> AsyncImage(
                model = ImageRequest.Builder(ctx).data(s.file).crossfade(!reduced).build(),
                contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
            PreviewState.Unavailable -> Icon(Icons.Filled.Image, null, Modifier.fillMaxSize(0.5f), tint = MaterialTheme.colorScheme.outline)
            is PreviewState.Failed -> Icon(Icons.Filled.CloudOff, null, Modifier.fillMaxSize(0.5f), tint = MaterialTheme.colorScheme.outline)
        }
    }
}
