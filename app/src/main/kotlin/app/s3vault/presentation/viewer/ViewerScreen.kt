package app.s3vault.presentation.viewer

import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.s3vault.domain.model.MediaItem
import app.s3vault.presentation.Strings
import app.s3vault.presentation.common.LoadingState
import app.s3vault.presentation.common.appViewModel
import app.s3vault.presentation.common.openVideo
import app.s3vault.presentation.common.shareUris
import app.s3vault.presentation.gallery.SyncBadge
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

@Composable
fun ViewerScreen(source: String, initialAssetId: String, onBack: () -> Unit) {
    val vm = appViewModel(key = "viewer-$source") { ViewerViewModel(it, source) }
    val items by vm.items.collectAsStateWithLifecycle()
    val list = items
    if (list == null) { LoadingState(); return }
    if (list.isEmpty()) { LaunchedEffect(Unit) { onBack() }; return }

    val context = LocalContext.current
    val initial = remember { list.indexOfFirst { it.assetId == initialAssetId }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = initial) { list.size }
    var zoomed by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(false) }
    var showAlbums by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val albums by vm.albums.collectAsStateWithLifecycle(initialValue = emptyList())

    val current = list[pager.currentPage.coerceIn(0, list.lastIndex)]
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) vm.afterDelete()
    }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            userScrollEnabled = !zoomed,
            beyondViewportPageCount = 1,
            key = { list.getOrNull(it)?.assetId ?: it },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = list[page]
            ZoomableMedia(
                item = item,
                onZoomChanged = { if (page == pager.currentPage) zoomed = it },
                onTap = { controls = !controls },
                onPlay = { item.uri?.let { openVideo(context, Uri.parse(it)) } },
            )
        }

        if (controls) {
            Row(
                Modifier.fillMaxWidth().background(Color(0x66000000)).statusBarsPadding().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Volver", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(current.displayName, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(Strings.state(current.state), color = Color(0xCCFFFFFF), style = MaterialTheme.typography.bodySmall)
                }
                SyncBadge(current.state, Modifier.padding(end = 12.dp))
            }
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x66000000)).navigationBarsPadding().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                IconButton(onClick = { current.uri?.let { shareUris(context, listOf(Uri.parse(it)), current.mimeType) } }) {
                    Icon(Icons.Filled.Share, "Compartir", tint = Color.White)
                }
                IconButton(onClick = { vm.backup(current.assetId) }) { Icon(Icons.Filled.Send, "Respaldar en S3", tint = Color.White) }
                IconButton(onClick = { showAlbums = true }) { Icon(Icons.Filled.Add, "Álbumes", tint = Color.White) }
                IconButton(onClick = { showInfo = true }) { Icon(Icons.Filled.Info, "Información", tint = Color.White) }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Eliminar del dispositivo", tint = Color.White) }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp))
    }

    if (showInfo) InfoDialog(current) { showInfo = false }

    if (showAlbums) {
        val memberOf by vm.albumIdsOf(current.assetId).collectAsStateWithLifecycle(initialValue = emptyList())
        AlertDialog(
            onDismissRequest = { showAlbums = false },
            title = { Text("Álbumes") },
            text = {
                if (albums.isEmpty()) Text("Aún no hay álbumes. Créalos desde la pestaña Álbumes.")
                else Column {
                    albums.forEach { album ->
                        val inAlbum = album.id in memberOf
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = inAlbum, onCheckedChange = { vm.toggleAlbum(album.id, current.assetId, inAlbum) })
                            Text(album.name)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAlbums = false }) { Text("Listo") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Eliminar del dispositivo") },
            text = { Text(Strings.MEDIA_DELETE_NOTE) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    current.uri?.let { u ->
                        val pending = MediaStore.createTrashRequest(context.contentResolver, listOf(Uri.parse(u)), true)
                        deleteLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                    }
                }) { Text("Continuar") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun InfoDialog(item: MediaItem, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Información") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InfoRow("Nombre", item.displayName)
                InfoRow("Fecha de captura", Strings.dateTime(item.dateTakenMs))
                InfoRow("Modificada", Strings.dateTime(item.dateModifiedMs))
                InfoRow("Tamaño", Strings.size(item.sizeBytes))
                if (item.width != null && item.height != null) InfoRow("Dimensiones", "${item.width} × ${item.height} px")
                InfoRow("Tipo", item.mimeType)
                item.folder?.let { InfoRow("Carpeta", it) }
                InfoRow("Respaldo", Strings.state(item.state))
                if (item.lastBackupAtMs != null) InfoRow("Último respaldo", Strings.dateTime(item.lastBackupAtMs))
                if (item.lastErrorKind != null) InfoRow("Último error", Strings.errorKind(item.lastErrorKind))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Imagen con zoom por pellizco, desplazamiento y doble toque. A escala 1 no consume gestos (el pager navega). */
@Composable
private fun ZoomableMedia(item: MediaItem, onZoomChanged: (Boolean) -> Unit, onTap: () -> Unit, onPlay: () -> Unit) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = size.width * (s - 1f) / 2f
        val maxY = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                        onZoomChanged(scale > 1f)
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val multiTouch = event.changes.size > 1
                        if (multiTouch || scale > 1f) {
                            val newScale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            val newOffset = if (newScale > 1f) clamp(offset + event.calculatePan(), newScale) else Offset.Zero
                            scale = newScale
                            offset = newOffset
                            onZoomChanged(newScale > 1f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(item.uri).size(2048, 2048).build(),
            contentDescription = item.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
            },
        )
        if (item.isVideo) {
            IconButton(onClick = onPlay, modifier = Modifier.align(Alignment.Center).size(72.dp).background(Color(0x99000000), androidx.compose.foundation.shape.CircleShape)) {
                Icon(Icons.Filled.PlayArrow, "Reproducir", tint = Color.White, modifier = Modifier.size(40.dp))
            }
        }
    }
}
