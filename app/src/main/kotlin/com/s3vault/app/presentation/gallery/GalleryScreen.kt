package com.s3vault.app.presentation.gallery

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.s3vault.app.domain.MediaItem
import com.s3vault.app.domain.SyncState
import com.s3vault.app.presentation.GalleryViewModel
import com.s3vault.app.presentation.GridSkeleton
import com.s3vault.app.presentation.MediaGrid
import com.s3vault.app.presentation.shareMedia
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.presentation.ui.AppTopBar
import com.s3vault.app.presentation.ui.EmptyState
import com.s3vault.app.presentation.ui.PrimaryButton
import com.s3vault.app.presentation.ui.QuietButton
import com.s3vault.app.presentation.ui.SecondaryButton
import com.s3vault.app.presentation.ui.StatusBanner
import com.s3vault.app.presentation.ui.Tone
import com.s3vault.app.presentation.ui.rememberReducedMotion
import com.s3vault.app.sync.MediaAccess

private fun requiredPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

/** Backup activity visible in the library, derived only from the sync state Room already confirmed. */
private data class BackupGlance(val active: Int, val failed: Int)

private fun glanceOf(items: List<MediaItem>): BackupGlance {
    var active = 0
    var failed = 0
    for (i in items) when (i.syncState) {
        SyncState.QUEUED, SyncState.UPLOADING, SyncState.VERIFYING -> active++
        SyncState.FAILED -> failed++
        else -> Unit
    }
    return BackupGlance(active, failed)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(vm: GalleryViewModel, onOpen: (Long) -> Unit, onOpenBackup: () -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val uploads by vm.uploads.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var albumSheet by remember { mutableStateOf(false) }
    val reduced = rememberReducedMotion()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refresh() }

    // Rescan whenever the screen is shown (new photos, permission changes from system settings).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() }
    }

    val items = state.items
    val selectionMode = state.selected.isNotEmpty()
    // Back leaves selection mode first instead of closing the app.
    BackHandler(enabled = selectionMode) { vm.clearSelection() }
    val glance = remember(items) { items?.let(::glanceOf) ?: BackupGlance(0, 0) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Crossfade(targetState = selectionMode, animationSpec = tween(if (reduced) 0 else 150), label = "galleryBar") { selecting ->
                if (selecting) {
                    AppTopBar(
                        title = if (state.selected.size == 1) "1 seleccionada" else "${state.selected.size} seleccionadas",
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        navigation = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.Close, "Cancelar selección") } },
                        actions = {
                            IconButton(onClick = { vm.selectAll(state.items.orEmpty().map { it.mediaStoreId }) }) { Icon(Icons.Filled.SelectAll, "Seleccionar todo") }
                            IconButton(onClick = vm::backupSelected) { Icon(Icons.Filled.CloudUpload, "Respaldar en S3") }
                            IconButton(onClick = { albumSheet = true }) { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Añadir a álbum") }
                            IconButton(onClick = {
                                shareMedia(ctx, state.items.orEmpty().filter { it.mediaStoreId in state.selected })
                            }) { Icon(Icons.Filled.Share, "Compartir") }
                        },
                    )
                } else {
                    AppTopBar(
                        title = "Galería",
                        subtitle = items?.takeIf { it.isNotEmpty() }?.let { if (it.size == 1) "1 elemento" else "${it.size} elementos" },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.access == MediaAccess.PARTIAL) {
                StatusBanner(
                    tone = Tone.Info,
                    title = "Acceso parcial",
                    body = "Acceso parcial: solo ves las fotos que elegiste.",
                    actionLabel = "Gestionar",
                    onAction = { launcher.launch(requiredPermissions()) },
                    modifier = Modifier.padding(horizontal = Space.screen, vertical = Space.s),
                )
            }
            if (!selectionMode && (glance.active > 0 || glance.failed > 0)) {
                BackupGlanceChip(glance, onOpenBackup, Modifier.padding(horizontal = Space.screen, vertical = Space.xs))
            }
            when {
                state.access == MediaAccess.NONE -> EmptyState(
                    icon = Icons.Filled.PhotoLibrary,
                    title = "Necesito acceso a tus fotos",
                    body = "La galería lee tus fotos y vídeos directamente del dispositivo. Nada se envía a ningún sitio hasta que configures Amazon S3 y elijas respaldar. " +
                        "Si ya lo denegaste, Android puede no volver a preguntar: abre los ajustes de la app.",
                ) {
                    PrimaryButton("Conceder acceso", { launcher.launch(requiredPermissions()) }, Modifier.fillMaxWidth())
                    QuietButton("Abrir ajustes de la app", {
                        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                    })
                }
                items == null -> GridSkeleton()
                state.error != null && items.isEmpty() -> EmptyState(
                    icon = Icons.Filled.ErrorOutline,
                    tone = Tone.Error,
                    title = "No se pudo cargar la galería",
                    body = state.error,
                ) { PrimaryButton("Reintentar", vm::refresh, Modifier.fillMaxWidth()) }
                items.isEmpty() -> if (state.refreshing) GridSkeleton() else EmptyState(
                    icon = Icons.Filled.PhotoLibrary,
                    title = "No hay fotos",
                    body = "Cuando haya fotos o vídeos en el dispositivo aparecerán aquí.",
                )
                else -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
                    MediaGrid(
                        items = items,
                        selected = state.selected,
                        uploads = uploads,
                        onClick = { if (selectionMode) vm.toggle(it.mediaStoreId) else onOpen(it.mediaStoreId) },
                        onLongClick = { vm.toggle(it.mediaStoreId) },
                    )
                }
            }
        }
    }

    if (albumSheet) {
        AddToAlbumSheet(
            albums = state.albums.map { it.id to it.name },
            onPick = { vm.addSelectedToAlbum(it); albumSheet = false },
            onCreate = { vm.createAlbumWithSelected(it); albumSheet = false },
            onDismiss = { albumSheet = false },
        )
    }
}

/** One quiet line above the grid, only while there is real backup activity or a failure to look at. */
@Composable
private fun BackupGlanceChip(glance: BackupGlance, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val failing = glance.failed > 0
    val label = buildString {
        if (glance.active > 0) append(if (glance.active == 1) "1 en cola o subiendo" else "${glance.active} en cola o subiendo")
        if (glance.active > 0 && failing) append(" · ")
        if (failing) append(if (glance.failed == 1) "1 con error" else "${glance.failed} con error")
    }
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = Space.touch),
        shape = CircleShape,
        color = if (failing) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (failing) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            Modifier.padding(horizontal = Space.l, vertical = Space.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Icon(if (failing) Icons.Filled.ErrorOutline else Icons.Filled.CloudSync, null, Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text("Ver respaldo", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToAlbumSheet(
    albums: List<Pair<Long, String>>,
    onPick: (Long) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.imePadding().navigationBarsPadding().padding(horizontal = Space.screen), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text("Añadir a álbum", style = MaterialTheme.typography.titleLarge)
            Text(
                "Los álbumes son virtuales: las fotos no se mueven ni se copian.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (albums.isNotEmpty()) {
                LazyColumn(Modifier.heightIn(max = 240.dp)) {
                    items(albums, key = { it.first }) { (id, name) ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = Space.touch).clickable(role = Role.Button) { onPick(id) }.padding(vertical = Space.s),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Space.l),
                        ) {
                            Icon(Icons.Filled.PhotoAlbum, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 2)
                            Text("Añadir", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            OutlinedTextField(
                value = newName, onValueChange = { newName = it.take(60) }, label = { Text("Nuevo álbum") }, singleLine = true,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().padding(top = Space.s),
            )
            SecondaryButton("Crear y añadir", { onCreate(newName.trim()) }, Modifier.fillMaxWidth(), enabled = newName.isNotBlank())
            Column(Modifier.padding(bottom = Space.l)) {}
        }
    }
}
