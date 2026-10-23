package com.s3vault.app.presentation.viewer

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Size
import com.s3vault.app.domain.MediaItem
import com.s3vault.app.domain.MediaKind
import com.s3vault.app.presentation.SyncPill
import com.s3vault.app.presentation.formatBytes
import com.s3vault.app.presentation.formatDateTime
import com.s3vault.app.presentation.openVideo
import com.s3vault.app.presentation.shareMedia
import com.s3vault.app.presentation.theme.PhotoOverlay
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.presentation.theme.technical
import com.s3vault.app.presentation.ui.LabeledValue
import com.s3vault.app.presentation.ui.rememberReducedMotion
import kotlinx.coroutines.launch

/**
 * Full-screen viewer with pager navigation, pinch zoom, pan and double-tap zoom.
 * [onBackup] only queues the item (the transfer happens in WorkManager) and returns an honest message to show.
 *
 * The photograph owns the screen: controls fade in over soft scrims on tap and disappear again, and the system
 * bars hide with them. Details live in a bottom sheet instead of permanently covering the image.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    items: List<MediaItem>,
    startId: Long,
    onBack: () -> Unit,
    onBackup: suspend (MediaItem) -> String,
) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val reduced = rememberReducedMotion()
    val startIndex = remember(items.isEmpty()) { items.indexOfFirst { it.mediaStoreId == startId }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = startIndex) { items.size }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val current = items.getOrNull(pager.currentPage)

    // Immersive viewing: light system-bar icons on black, bars hidden together with the controls.
    val controller = remember(view) { (view.context as? Activity)?.window?.let { WindowCompat.getInsetsController(it, view) } }
    DisposableEffect(controller) {
        val c = controller
        val wasLightStatus = c?.isAppearanceLightStatusBars
        val wasLightNav = c?.isAppearanceLightNavigationBars
        c?.isAppearanceLightStatusBars = false
        c?.isAppearanceLightNavigationBars = false
        onDispose {
            c?.show(WindowInsetsCompat.Type.systemBars())
            wasLightStatus?.let { c.isAppearanceLightStatusBars = it }
            wasLightNav?.let { c.isAppearanceLightNavigationBars = it }
        }
    }
    LaunchedEffect(chrome, controller) {
        controller?.let {
            if (chrome) it.show(WindowInsetsCompat.Type.systemBars()) else {
                it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                it.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    val fade = tween<Float>(if (reduced) 0 else 180)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, userScrollEnabled = !zoomed, beyondViewportPageCount = 1, modifier = Modifier.fillMaxSize()) { page ->
            val item = items[page]
            ZoomableImage(
                item = item,
                active = page == pager.currentPage,
                onZoomChanged = { zoomed = it },
                onTap = { if (item.kind == MediaKind.VIDEO) openVideo(ctx, item) else chrome = !chrome },
            )
        }

        AnimatedVisibility(chrome && current != null, Modifier.align(Alignment.TopCenter), enter = fadeIn(fade), exit = fadeOut(fade)) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(PhotoOverlay.strongScrim, Color.Transparent)))
                    .statusBarsPadding().padding(horizontal = Space.xs, vertical = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = PhotoOverlay.onScrim) }
                Column(Modifier.weight(1f).padding(end = Space.l)) {
                    Text(
                        current?.displayName.orEmpty(), color = PhotoOverlay.onScrim, style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${pager.currentPage + 1} de ${items.size}", color = PhotoOverlay.onScrim.copy(alpha = 0.78f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        AnimatedVisibility(chrome && current != null, Modifier.align(Alignment.BottomCenter), enter = fadeIn(fade), exit = fadeOut(fade)) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, PhotoOverlay.strongScrim)))
                    .navigationBarsPadding().padding(horizontal = Space.screen, vertical = Space.s),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                current?.let { SyncPill(it.syncState, Modifier.padding(start = Space.xs), onPhoto = true) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    current?.let { item ->
                        ViewerAction(Icons.Filled.CloudUpload, "Respaldar") {
                            scope.launch { snackbar.showSnackbar(onBackup(item)) }
                        }
                        ViewerAction(Icons.Filled.Share, "Compartir") { shareMedia(ctx, listOf(item)) }
                        ViewerAction(Icons.Filled.Info, "Detalles") { info = true }
                    }
                }
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 112.dp))
    }

    if (info && current != null) {
        ModalBottomSheet(onDismissRequest = { info = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(horizontal = Space.screen).padding(bottom = Space.xl),
                verticalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                Text(current.displayName, style = MaterialTheme.typography.titleLarge)
                SyncPill(current.syncState)
                LabeledValue("Fecha de captura", formatDateTime(current.dateTakenMs))
                LabeledValue("Tamaño", formatBytes(current.sizeBytes))
                if (current.width > 0) LabeledValue("Dimensiones", "${current.width} × ${current.height} px")
                LabeledValue("Carpeta", current.folderName.ifBlank { "—" })
                current.lastBackupAtMs?.let { LabeledValue("Último respaldo", formatDateTime(it)) }
                current.lastError?.let {
                    Column {
                        Text("Último error", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                        Text(it, style = MaterialTheme.technical, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.widthIn(min = 80.dp).heightIn(min = 56.dp).clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = Space.m, vertical = Space.s),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(icon, null, tint = PhotoOverlay.onScrim)
        Text(label, color = PhotoOverlay.onScrim, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ZoomableImage(item: MediaItem, active: Boolean, onZoomChanged: (Boolean) -> Unit, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val ctx = LocalContext.current

    // Reset when the page is no longer the visible one.
    if (!active && (scale != 1f || offset != Offset.Zero)) { scale = 1f; offset = Offset.Zero }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = (box.width * (s - 1f)) / 2f
        val maxY = (box.height * (s - 1f)) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                box = size
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero } else { scale = 3f }
                        onZoomChanged(scale > 1f)
                    },
                )
            }
            .pointerInput(Unit) {
                // Zoom/pan only when pinching or already zoomed, so a one-finger swipe still pages.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val multi = event.changes.size > 1
                        if (multi || scale > 1f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val newScale = (scale * zoom).coerceIn(1f, 6f)
                            offset = clamp(offset + pan, newScale)
                            scale = newScale
                            onZoomChanged(scale > 1f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            // Capped decode size: sharp enough to zoom, never the full-resolution original in memory.
            model = ImageRequest.Builder(ctx).data(item.uri).size(Size(2048, 2048)).build(),
            contentDescription = item.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        if (item.kind == MediaKind.VIDEO) {
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(PhotoOverlay.scrim),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.PlayArrow, "Reproducir vídeo", tint = PhotoOverlay.onScrim, modifier = Modifier.size(44.dp)) }
        }
    }
}
