package com.s3vault.app.presentation.viewer

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.s3vault.app.domain.RemoteObject
import com.s3vault.app.domain.SyncState
import com.s3vault.app.presentation.RemoteViewModel
import com.s3vault.app.presentation.delete.DeleteHost
import com.s3vault.app.presentation.delete.DeleteViewModel
import com.s3vault.app.presentation.formatBytes
import com.s3vault.app.presentation.library.ItemSource
import com.s3vault.app.presentation.remote.PreviewState
import com.s3vault.app.presentation.remote.RemoteDetailSheet
import com.s3vault.app.presentation.remote.rememberPreviewState
import com.s3vault.app.presentation.theme.PhotoOverlay
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.presentation.ui.ConfirmDialog
import com.s3vault.app.presentation.ui.PrimaryButton
import com.s3vault.app.presentation.ui.SecondaryButton
import com.s3vault.app.presentation.ui.rememberReducedMotion
import com.s3vault.app.sync.PreviewQuality

/**
 * Looks at photos that only live in S3 *without downloading them*. What is shown is the small preview stored next to
 * the photo (or, failing that, the JPEG's embedded thumbnail), and the screen says so; getting the original is an
 * explicit "Restaurar" with its size on the button.
 */
@Composable
fun CloudViewerScreen(
    items: ItemSource<RemoteObject>,
    startIndex: Int,
    vm: RemoteViewModel,
    deleteVm: DeleteViewModel,
    onBack: () -> Unit,
    onOpenCredentials: () -> Unit,
) {
    val view = LocalView.current
    val reduced = rememberReducedMotion()
    val state by vm.state.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val pager = rememberPagerState(initialPage = startIndex.coerceAtLeast(0)) { items.size }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf<RemoteObject?>(null) }
    val previews = remember { mutableStateMapOf<String, PreviewState>() }
    val current = items[pager.currentPage]
    LaunchedEffect(pager.currentPage, items.size) { items.ensure((pager.currentPage - 2).coerceAtLeast(0)..(pager.currentPage + 2)) }

    // Same immersive treatment as the local viewer: dark bars with light icons over the black canvas.
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

    var hadItems by remember { mutableStateOf(false) }
    LaunchedEffect(items.size) { if (items.size > 0) hadItems = true else if (hadItems) onBack() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }

    val fade = tween<Float>(if (reduced) 0 else 180)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, userScrollEnabled = !zoomed, beyondViewportPageCount = 1, modifier = Modifier.fillMaxSize()) { page ->
            val obj = items[page]
            if (obj == null) { Box(Modifier.fillMaxSize()); return@HorizontalPager }
            CloudPage(
                obj = obj, active = page == pager.currentPage, vm = vm,
                onState = { previews[obj.key] = it },
                onZoomChanged = { zoomed = it }, onTap = { chrome = !chrome },
                onRestore = { confirmRestore = obj }, onOpenCredentials = onOpenCredentials,
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
                        "${pager.currentPage + 1} de ${items.size} · ${current?.let { formatBytes(it.sizeBytes) }.orEmpty()}",
                        color = PhotoOverlay.onScrim.copy(alpha = 0.78f), style = MaterialTheme.typography.labelMedium,
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
                current?.let { obj ->
                    QualityChip(previews[obj.key], obj)
                    val restoring = obj.state == SyncState.RESTORING
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        ViewerAction(
                            Icons.Filled.Download, if (restoring) "Restaurando…" else "Restaurar", enabled = !restoring,
                        ) { confirmRestore = obj }
                        ViewerAction(Icons.Filled.Info, "Detalles") { vm.showDetail(obj) }
                        ViewerAction(Icons.Filled.Delete, "Eliminar de S3", enabled = !restoring) {
                            deleteVm.requestCloud(listOf(obj))
                        }
                    }
                }
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 112.dp))
    }

    confirmRestore?.let { obj ->
        ConfirmDialog(
            title = "¿Descargar el original?",
            text = "Se descargará «${obj.displayName}» (${formatBytes(obj.sizeBytes)}) y se guardará como copia nueva en Imágenes/S3Vault. " +
                "Es el único modo de verla a resolución completa." +
                if (obj.availableLocally) " Ya hay una copia en este dispositivo; esta sería otra." else "",
            confirmLabel = "Restaurar (${formatBytes(obj.sizeBytes)})",
            onConfirm = { confirmRestore = null; vm.restoreKeys(listOf(obj.key)) },
            onDismiss = { confirmRestore = null },
        )
    }
    state.detail?.let { RemoteDetailSheet(it, vm::dismissDetail) }
    DeleteHost(deleteVm, snackbar, onOpenCredentials = onOpenCredentials)

    // Keep download progress visible without leaving the viewer.
    current?.let { obj ->
        downloads[obj.key]?.let { p ->
            Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.TopCenter) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { p.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 56.dp),
                    color = PhotoOverlay.onScrim, trackColor = PhotoOverlay.scrim,
                )
            }
        }
    }
}

@Composable
private fun CloudPage(
    obj: RemoteObject,
    active: Boolean,
    vm: RemoteViewModel,
    onState: (PreviewState) -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    onTap: () -> Unit,
    onRestore: () -> Unit,
    onOpenCredentials: () -> Unit,
) {
    var attempt by remember { mutableIntStateOf(0) }
    val ps by rememberPreviewState(obj, attempt, vm::loadPreview)
    LaunchedEffect(ps) {
        onState(ps)
        // The listing was stale (deleted from another device): refresh it so the photo drops out by itself.
        if ((ps as? PreviewState.Failed)?.notFound == true) vm.refresh()
    }
    val ctx = LocalContext.current

    ZoomableBox(active = active, onZoomChanged = onZoomChanged, onTap = onTap) {
        when (val s = ps) {
            PreviewState.Loading -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.m)) {
                CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp, color = PhotoOverlay.onScrim)
                Text("Cargando vista previa…", color = PhotoOverlay.onScrim.copy(alpha = 0.78f), style = MaterialTheme.typography.bodyMedium)
            }
            is PreviewState.Ready -> AsyncImage(
                model = ImageRequest.Builder(ctx).data(s.file).build(),
                contentDescription = obj.displayName, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
            )
            PreviewState.Unavailable -> Message(
                icon = Icons.Filled.HideImage,
                title = "Sin vista previa",
                body = "Este archivo no tiene miniatura en S3 ni una incrustada. Para verlo hay que descargarlo (${formatBytes(obj.sizeBytes)}).",
            ) { SecondaryButton("Restaurar (${formatBytes(obj.sizeBytes)})", onRestore, icon = Icons.Filled.Download) }
            is PreviewState.Failed -> Message(
                icon = if (s.needsCredentials) Icons.Filled.CloudOff else Icons.Filled.ErrorOutline,
                title = if (s.notFound) "Este objeto ya no está en S3" else "No se pudo cargar la vista previa",
                body = if (s.notFound) "Se eliminó desde otro dispositivo o herramienta. Se está actualizando la biblioteca." else s.message,
            ) {
                PrimaryButton("Reintentar", { attempt++ })
                if (s.needsCredentials) SecondaryButton("Ir a credenciales", onOpenCredentials)
            }
        }
    }
}

@Composable
private fun Message(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, actions: @Composable () -> Unit) {
    Column(
        Modifier.widthIn(max = 320.dp).padding(horizontal = Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Icon(icon, null, Modifier.size(40.dp), tint = PhotoOverlay.onScrim.copy(alpha = 0.8f))
        Text(title, color = PhotoOverlay.onScrim, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(body, color = PhotoOverlay.onScrim.copy(alpha = 0.78f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        actions()
    }
}

/** Says honestly what the user is looking at: a downscaled preview, a tiny embedded thumbnail, or nothing yet. */
@Composable
private fun QualityChip(state: PreviewState?, obj: RemoteObject) {
    val (icon, text) = when (state) {
        is PreviewState.Ready -> when (state.quality) {
            PreviewQuality.PREVIEW -> Icons.Filled.CloudSync to "Vista previa · no es el original (pesa ${formatBytes(obj.sizeBytes)})"
            PreviewQuality.EMBEDDED -> Icons.Filled.CloudSync to "Miniatura incrustada · muy baja resolución"
        }
        PreviewState.Unavailable -> Icons.Filled.HideImage to "Sin vista previa"
        is PreviewState.Failed -> Icons.Filled.ErrorOutline to "Sin conexión con S3"
        else -> Icons.Filled.CloudSync to "Solo en Amazon S3"
    }
    Row(
        Modifier.clip(CircleShape).background(PhotoOverlay.chip).padding(horizontal = Space.m, vertical = Space.xs + 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs + 2.dp),
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = PhotoOverlay.onScrim)
        Text(text, color = PhotoOverlay.onScrim, style = MaterialTheme.typography.labelMedium)
    }
}
