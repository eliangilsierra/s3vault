package app.s3vault.presentation.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.s3vault.domain.model.MediaItem
import app.s3vault.domain.model.SyncState
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/**
 * Cuadrícula de miniaturas. Cada miniatura se decodifica a ~300 px bajo demanda (solo las celdas
 * visibles), nunca el original a resolución completa.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGrid(
    items: List<MediaItem>,
    selected: Set<String>,
    onClick: (MediaItem) -> Unit,
    onLongClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(2.dp),
) {
    val context = LocalContext.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
    ) {
        items(items, key = { it.assetId }, contentType = { "thumb" }) { item ->
            val isSelected = item.assetId in selected
            Box(
                Modifier
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .combinedClickable(onClick = { onClick(item) }, onLongClick = { onLongClick(item) }),
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(item.uri)
                        .size(300, 300)
                        .build(),
                    contentDescription = item.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().padding(if (isSelected) 10.dp else 0.dp),
                )
                if (item.isVideo) {
                    Icon(
                        Icons.Filled.PlayArrow, contentDescription = "Vídeo", tint = Color.White,
                        modifier = Modifier.align(Alignment.Center).size(32.dp).clip(CircleShape).background(Color(0x66000000)),
                    )
                }
                SyncBadge(item.state, Modifier.align(Alignment.BottomEnd).padding(4.dp))
                if (isSelected) {
                    Icon(
                        Icons.Filled.CheckCircle, contentDescription = "Seleccionada", tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(22.dp)
                            .background(Color.White, CircleShape),
                    )
                }
            }
        }
    }
}

/** Indicador discreto del estado de respaldo. LOCAL_ONLY no muestra nada. */
@Composable
fun SyncBadge(state: SyncState, modifier: Modifier = Modifier) {
    when (state) {
        SyncState.BACKED_UP -> Icon(
            Icons.Filled.CheckCircle, contentDescription = "Respaldada", tint = Color(0xFF2E9E5B),
            modifier = modifier.size(16.dp).background(Color.White, CircleShape),
        )
        SyncState.QUEUED, SyncState.UPLOADING, SyncState.VERIFYING, SyncState.RESTORING -> Box(
            modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
        )
        SyncState.FAILED -> Icon(
            Icons.Filled.Warning, contentDescription = "Error de respaldo", tint = MaterialTheme.colorScheme.error,
            modifier = modifier.size(16.dp).background(Color.White, CircleShape),
        )
        SyncState.LOCAL_ONLY, SyncState.REMOTE_ONLY -> Unit
    }
}
