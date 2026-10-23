package com.s3vault.app.presentation.albums

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.s3vault.app.domain.Album
import com.s3vault.app.presentation.AlbumsViewModel
import com.s3vault.app.presentation.GridSkeleton
import com.s3vault.app.presentation.MediaGrid
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.presentation.ui.AppTopBar
import com.s3vault.app.presentation.ui.BackButton
import com.s3vault.app.presentation.ui.ConfirmDialog
import com.s3vault.app.presentation.ui.EmptyState
import com.s3vault.app.presentation.ui.QuietButton

@Composable
fun AlbumsScreen(vm: AlbumsViewModel, onOpenAlbum: (Long) -> Unit) {
    val albums by vm.albums.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                "Álbumes",
                subtitle = albums?.takeIf { it.isNotEmpty() }?.let { if (it.size == 1) "1 álbum" else "${it.size} álbumes" },
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
                list.isEmpty() -> EmptyState(
                    icon = Icons.Filled.PhotoAlbum,
                    title = "Aún no hay álbumes",
                    body = "Los álbumes son agrupaciones virtuales. Crear, renombrar o borrar un álbum nunca elimina fotos, ni del dispositivo ni de S3.",
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(156.dp),
                    contentPadding = PaddingValues(start = Space.screen, end = Space.screen, top = Space.s, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(Space.m),
                    verticalArrangement = Arrangement.spacedBy(Space.l),
                ) {
                    items(list, key = { it.id }) { AlbumTile(it) { onOpenAlbum(it.id) } }
                }
            }
        }
    }
    if (creating) {
        NameDialog("Nuevo álbum", "", "Crear", onConfirm = { vm.create(it); creating = false }, onDismiss = { creating = false })
    }
}

/** Cover first, caption underneath: the photograph is the tile, not a card around it. */
@Composable
private fun AlbumTile(album: Album, onClick: () -> Unit) {
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
fun AlbumDetailScreen(vm: AlbumsViewModel, albumId: Long, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val name by vm.albumName(albumId).collectAsStateWithLifecycle(initialValue = null)
    val media by remember(albumId) { vm.albumMedia(albumId) }.collectAsStateWithLifecycle(initialValue = null)
    val selected by vm.selection.collectAsStateWithLifecycle()
    val uploads by vm.uploads.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting) { vm.clearSelection() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = if (selecting) (if (selected.size == 1) "1 seleccionada" else "${selected.size} seleccionadas") else name.orEmpty(),
                containerColor = if (selecting) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.background,
                navigation = {
                    if (selecting) IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.Close, "Cancelar selección") }
                    else BackButton(onBack)
                },
                actions = {
                    if (selecting) {
                        IconButton(onClick = vm::backupSelected) { Icon(Icons.Filled.CloudUpload, "Respaldar en S3") }
                        IconButton(onClick = { vm.removeSelected(albumId) }) { Icon(Icons.Filled.RemoveCircleOutline, "Quitar del álbum") }
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
            when {
                list == null -> GridSkeleton()
                list.isEmpty() -> EmptyState(
                    icon = Icons.Filled.PhotoAlbum,
                    title = "Álbum vacío",
                    body = "Selecciona fotos en la galería (mantén pulsado) y elige «Añadir a álbum».",
                )
                else -> MediaGrid(
                    items = list, selected = selected, uploads = uploads,
                    onClick = { if (selecting) vm.toggle(it.mediaStoreId) else onOpen(it.mediaStoreId) },
                    onLongClick = { vm.toggle(it.mediaStoreId) },
                )
            }
        }
    }
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
