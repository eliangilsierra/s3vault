package com.s3vault.app.presentation.albums

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.s3vault.app.domain.Album
import com.s3vault.app.domain.MediaItem
import com.s3vault.app.domain.RemoteObject
import com.s3vault.app.domain.SyncState
import com.s3vault.app.presentation.AlbumsViewModel
import com.s3vault.app.presentation.GridSkeleton
import com.s3vault.app.presentation.MediaCell
import com.s3vault.app.presentation.MediaGrid
import com.s3vault.app.presentation.delete.DeleteHost
import com.s3vault.app.presentation.delete.DeleteViewModel
import com.s3vault.app.presentation.delete.toDeleteEntry
import com.s3vault.app.presentation.remote.RemoteCell
import com.s3vault.app.presentation.remote.RemoteThumb
import com.s3vault.app.presentation.smart.vector
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.sync.DeleteEntry
import com.s3vault.app.sync.PreviewResult
import com.s3vault.app.sync.Progress
import com.s3vault.app.presentation.ui.AppTopBar
import com.s3vault.app.presentation.ui.BackButton
import com.s3vault.app.presentation.ui.ConfirmDialog
import com.s3vault.app.presentation.ui.EmptyState
import com.s3vault.app.presentation.ui.QuietButton
import com.s3vault.app.presentation.ui.SecondaryButton
import com.s3vault.app.presentation.ui.SlimProgress

@Composable
fun AlbumsScreen(
    vm: AlbumsViewModel,
    onOpenAlbum: (Long) -> Unit,
    smart: com.s3vault.app.presentation.smart.SmartHome = com.s3vault.app.presentation.smart.SmartHome(),
    onOpenSmart: (String) -> Unit = {},
    onOpenSmartAll: () -> Unit = {},
    /** Number of people found, or null while the face analysis is off and there is nothing to show. */
    peopleCount: Int? = null,
    onOpenPeople: () -> Unit = {},
) {
    val albums by vm.albums.collectAsStateWithLifecycle()
    val importing by vm.isImporting.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it, duration = SnackbarDuration.Long); vm.dismissMessage() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            AppTopBar(
                "Álbumes",
                subtitle = albums?.takeIf { it.isNotEmpty() }?.let { if (it.size == 1) "1 álbum" else "${it.size} álbumes" },
                actions = {
                    IconButton(onClick = vm::importFromS3, enabled = !importing) {
                        Icon(Icons.Filled.CloudDownload, "Importar álbumes de S3")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { creating = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) { Icon(Icons.Filled.Add, "Crear álbum") }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val list = albums
            when {
                list == null -> GridSkeleton()
                list.isEmpty() -> Column(Modifier.fillMaxSize()) {
                    if (com.s3vault.app.data.faces.FaceFeature.available) com.s3vault.app.presentation.people.PeopleStrip(peopleCount, onOpenPeople)
                    SmartStrip(smart, onOpenSmart, onOpenSmartAll)
                    Box(Modifier.weight(1f)) {
                        EmptyState(
                            icon = Icons.Filled.PhotoAlbum,
                            title = "Aún no hay álbumes",
                            body = "Los álbumes son agrupaciones virtuales. Crear, renombrar o borrar un álbum nunca elimina fotos, ni del dispositivo ni de S3.",
                        ) {
                            SecondaryButton(
                                "Importar álbumes de S3", vm::importFromS3, Modifier.fillMaxWidth(),
                                enabled = !importing, icon = Icons.Filled.CloudDownload,
                            )
                        }
                    }
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(156.dp),
                    contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.s, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(Space.m),
                    verticalArrangement = Arrangement.spacedBy(Space.l),
                ) {
                    if (com.s3vault.app.data.faces.FaceFeature.available) {
                        item(key = "people-strip", span = { GridItemSpan(maxLineSpan) }) { com.s3vault.app.presentation.people.PeopleStrip(peopleCount, onOpenPeople) }
                    }
                    item(key = "smart-strip", span = { GridItemSpan(maxLineSpan) }) { SmartStrip(smart, onOpenSmart, onOpenSmartAll, Modifier.padding(horizontal = 0.dp)) }
                    items(list, key = { it.id }) { AlbumTile(it, vm::loadPreview) { onOpenAlbum(it.id) } }
                }
            }
            if (importing) SlimProgress(null, Modifier.align(Alignment.TopCenter).padding(horizontal = Space.screen))
        }
    }
    if (creating) {
        NameDialog("Nuevo álbum", "", "Crear", onConfirm = { vm.create(it); creating = false }, onDismiss = { creating = false })
    }
}

/**
 * The way into the smart albums from the Albums tab without adding a sixth tab: the few that have something in them, one tap
 * away, and "Ver todos" for the rest (by year, custom, S3).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SmartStrip(home: com.s3vault.app.presentation.smart.SmartHome, onOpen: (String) -> Unit, onAll: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = Space.screen), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Álbumes inteligentes", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            QuietButton("Ver todos", onAll)
        }
        val shown = home.builtIn.filter { it.count > 0 }.take(8)
        if (shown.isNotEmpty()) {
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                rowItems(shown, key = { it.album.id }) { a ->
                    androidx.compose.material3.AssistChip(
                        onClick = { onOpen(a.album.id) },
                        label = { Text("${a.album.title} · ${a.count}", maxLines = 1) },
                        leadingIcon = { Icon(a.album.icon.vector(), null, Modifier.size(18.dp)) },
                    )
                }
            }
        }
    }
}

/** Cover first, caption underneath: the photograph is the tile, not a card around it. */
@Composable
private fun AlbumTile(album: Album, load: suspend (RemoteObject) -> PreviewResult, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(role = Role.Button, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (album.coverUri != null) {
                AsyncImage(model = album.coverUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else if (album.coverRemote != null) {
                RemoteThumb(album.coverRemote, load, Modifier.fillMaxSize())
            } else {
                Icon(Icons.Filled.PhotoAlbum, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.outline)
            }
        }
        Column(Modifier.padding(horizontal = Space.xs)) {
            Text(album.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                if (album.itemCount == 1) "1 elemento" else "${album.itemCount} elementos",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun AlbumDetailScreen(
    vm: AlbumsViewModel,
    deleteVm: DeleteViewModel,
    albumId: Long,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onOpenCloud: (RemoteObject) -> Unit,
    onOpenCredentials: () -> Unit = {},
) {
    val name by vm.albumName(albumId).collectAsStateWithLifecycle(initialValue = null)
    val media by remember(albumId) { vm.albumMedia(albumId) }.collectAsStateWithLifecycle(initialValue = null)
    val cloud by remember(albumId) { vm.albumCloud(albumId) }.collectAsStateWithLifecycle(initialValue = null)
    val selected by vm.selection.collectAsStateWithLifecycle()
    val cloudSelected by vm.cloudSelection.collectAsStateWithLifecycle()
    val uploads by vm.uploads.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val selectedCount = selected.size + cloudSelected.size
    val selecting = selectedCount > 0
    val snackbar = remember { SnackbarHostState() }
    BackHandler(enabled = selecting) { vm.clearSelection() }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it, duration = SnackbarDuration.Long); vm.dismissMessage() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            AppTopBar(
                title = if (selecting) (if (selectedCount == 1) "1 seleccionada" else "$selectedCount seleccionadas") else name.orEmpty(),
                compact = true,
                containerColor = if (selecting) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.background,
                navigation = {
                    if (selecting) IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.Close, "Cancelar selección") }
                    else BackButton(onBack)
                },
                actions = {
                    if (selecting) {
                        if (selected.isNotEmpty()) {
                            IconButton(onClick = vm::backupSelected) { Icon(Icons.Filled.CloudUpload, "Respaldar en S3") }
                        }
                        if (cloudSelected.isNotEmpty()) {
                            IconButton(onClick = vm::restoreSelectedCloud) { Icon(Icons.Filled.Download, "Restaurar en el dispositivo") }
                        }
                        IconButton(onClick = { vm.removeSelected(albumId, cloud.orEmpty()) }) { Icon(Icons.Filled.RemoveCircleOutline, "Quitar del álbum") }
                        IconButton(
                            onClick = {
                                val local = media.orEmpty().filter { it.mediaStoreId in selected }
                                val remote = cloud.orEmpty().filter { it.key in cloudSelected }
                                if (local.isEmpty()) deleteVm.requestCloud(remote)
                                else deleteVm.request(
                                    local.map { it.toDeleteEntry() } +
                                        remote.map { DeleteEntry(it.displayName, null, null, it.key, hasLocalCopy = it.availableLocally) },
                                )
                            },
                        ) { Icon(Icons.Filled.Delete, "Eliminar fotos…", tint = MaterialTheme.colorScheme.error) }
                    } else {
                        IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.Edit, "Renombrar") }
                        IconButton(onClick = { deleting = true }) { Icon(Icons.Filled.Delete, "Eliminar álbum") }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val list = media
            val remote = cloud
            when {
                list == null || remote == null -> GridSkeleton()
                list.isEmpty() && remote.isEmpty() -> EmptyState(
                    icon = Icons.Filled.PhotoAlbum,
                    title = "Álbum vacío",
                    body = "Selecciona fotos en la galería (mantén pulsado) y elige «Añadir a álbum».",
                )
                remote.isEmpty() -> MediaGrid(
                    items = list, selected = selected, uploads = uploads,
                    onClick = { if (selecting) vm.toggle(it.mediaStoreId) else onOpen(it.mediaStoreId) },
                    onLongClick = { vm.toggle(it.mediaStoreId) },
                )
                else -> MixedAlbumGrid(
                    local = list, cloud = remote, selected = selected, cloudSelected = cloudSelected, uploads = uploads, downloads = downloads,
                    load = vm::loadPreview,
                    onLocalClick = { if (selecting) vm.toggle(it.mediaStoreId) else onOpen(it.mediaStoreId) },
                    onLocalLongClick = { vm.toggle(it.mediaStoreId) },
                    onCloudClick = { if (selecting) vm.toggleCloud(it.key) else onOpenCloud(it) },
                    onCloudLongClick = { if (it.state != SyncState.RESTORING) vm.toggleCloud(it.key) },
                )
            }
        }
    }
    DeleteHost(deleteVm, snackbar, onStarted = vm::clearSelection, onOpenCredentials = onOpenCredentials)
    if (renaming) NameDialog("Renombrar álbum", name.orEmpty(), "Guardar", { vm.rename(albumId, it); renaming = false }, { renaming = false })
    if (deleting) {
        ConfirmDialog(
            title = "¿Eliminar el álbum?",
            text = "Solo se elimina la agrupación. Las fotos permanecen en el dispositivo y en S3.",
            confirmLabel = "Eliminar álbum",
            destructive = true,
            onConfirm = { vm.delete(albumId); deleting = false; onBack() },
            onDismiss = { deleting = false },
        )
    }
}

/**
 * The album when some of its photos are only in S3: the ones on this device first, then the S3 ones with their previews
 * (never the originals). An album made entirely of S3 photos shows a single section.
 */
@Composable
private fun MixedAlbumGrid(
    local: List<MediaItem>,
    cloud: List<RemoteObject>,
    selected: Set<Long>,
    cloudSelected: Set<String>,
    uploads: Map<Long, Progress>,
    downloads: Map<String, Progress>,
    load: suspend (RemoteObject) -> PreviewResult,
    onLocalClick: (MediaItem) -> Unit,
    onLocalLongClick: (MediaItem) -> Unit,
    onCloudClick: (RemoteObject) -> Unit,
    onCloudLongClick: (RemoteObject) -> Unit,
) {
    val selecting = selected.isNotEmpty() || cloudSelected.isNotEmpty()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(112.dp),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (local.isNotEmpty()) {
            item(key = "h-local", span = { GridItemSpan(maxLineSpan) }, contentType = "header") { SectionHeader("En este dispositivo", local.size) }
            items(local, key = { "l-${it.mediaStoreId}" }, contentType = { "cell" }) {
                MediaCell(it, it.mediaStoreId in selected, selecting, uploads[it.mediaStoreId], onLocalClick, onLocalLongClick)
            }
        }
        item(key = "h-cloud", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
            SectionHeader(if (local.isEmpty()) "Fotos del álbum en S3" else "Solo en S3", cloud.size)
        }
        items(cloud, key = { "c-${it.key}" }, contentType = { "cell" }) { obj ->
            RemoteCell(
                obj, obj.key in cloudSelected, selecting, downloads[obj.key], load,
                onClick = { onCloudClick(obj) }, onLongClick = { onCloudLongClick(obj) },
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(start = Space.screen, end = Space.screen, top = Space.l, bottom = Space.s)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
        Text(
            if (count == 1) "1 elemento" else "$count elementos",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Space.m),
        )
    }
}

@Composable
fun NameDialog(title: String, initial: String, confirmLabel: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(60) }, singleLine = true, label = { Text("Nombre") },
                shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { QuietButton(confirmLabel, { onConfirm(text.trim()) }, enabled = text.isNotBlank()) },
        dismissButton = { QuietButton("Cancelar", onDismiss) },
    )
}
