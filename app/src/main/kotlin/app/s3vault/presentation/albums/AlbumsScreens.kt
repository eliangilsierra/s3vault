package app.s3vault.presentation.albums

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.s3vault.presentation.common.EmptyState
import app.s3vault.presentation.common.LoadingState
import app.s3vault.presentation.common.rememberContainer
import app.s3vault.presentation.gallery.MediaGrid
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(onOpenAlbum: (Long) -> Unit) {
    val c = rememberContainer()
    val albums by c.albumRepository.albums.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        topBar = { TopAppBar(title = { Text("Álbumes") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, "Nuevo álbum") }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val list = albums
            when {
                list == null -> LoadingState()
                list.isEmpty() -> EmptyState(
                    "Sin álbumes",
                    "Los álbumes son virtuales: solo agrupan tus fotos. Quitar una foto de un álbum o borrar el álbum no elimina ningún archivo.",
                    actionLabel = "Crear álbum", onAction = { creating = true },
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(list, key = { it.id }) { album ->
                        Card(Modifier.fillMaxWidth().clickable { onOpenAlbum(album.id) }) {
                            Column {
                                Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(0.dp)) {
                                    if (album.coverUri != null) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(context).data(album.coverUri).size(300, 300).build(),
                                            contentDescription = album.name, contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                                Column(Modifier.padding(12.dp)) {
                                    Text(album.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                    Text("${album.itemCount} elementos", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating) NameDialog("Nuevo álbum", "", "Crear", { creating = false }) { name ->
        scope.launch { c.albumRepository.create(name) }
        creating = false
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlbumDetailScreen(albumId: Long, onBack: () -> Unit, onOpenViewer: (String) -> Unit) {
    val c = rememberContainer()
    val album by c.albumRepository.album(albumId).collectAsStateWithLifecycle(initialValue = null)
    val items by c.albumRepository.items(albumId).collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var selection by remember { mutableStateOf(setOf<String>()) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    BackHandler(enabled = selection.isNotEmpty()) { selection = emptySet() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selection.isEmpty()) album?.name.orEmpty() else "${selection.size} seleccionadas") },
                navigationIcon = {
                    IconButton(onClick = { if (selection.isNotEmpty()) selection = emptySet() else onBack() }) {
                        Icon(if (selection.isEmpty()) Icons.Filled.ArrowBack else Icons.Filled.Close, "Volver")
                    }
                },
                actions = {
                    if (selection.isNotEmpty()) {
                        TextButton(onClick = {
                            val ids = selection.toList()
                            scope.launch { c.albumRepository.remove(albumId, ids) }
                            selection = emptySet()
                        }) { Text("Quitar del álbum") }
                    } else {
                        IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.Edit, "Renombrar") }
                        IconButton(onClick = { deleting = true }) { Icon(Icons.Filled.Delete, "Eliminar álbum") }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val list = items
            when {
                list == null -> LoadingState()
                list.isEmpty() -> EmptyState("Álbum vacío", "Añade fotos desde la galería: selecciónalas y usa «Añadir a álbum».")
                else -> MediaGrid(
                    items = list, selected = selection,
                    onClick = { if (selection.isNotEmpty()) selection = selection.toggle(it.assetId) else onOpenViewer(it.assetId) },
                    onLongClick = { selection = selection.toggle(it.assetId) },
                )
            }
        }
    }

    if (renaming) NameDialog("Renombrar álbum", album?.name.orEmpty(), "Guardar", { renaming = false }) { name ->
        scope.launch { c.albumRepository.rename(albumId, name) }
        renaming = false
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Eliminar álbum") },
        text = { Text("Solo se elimina la agrupación. Las fotos permanecen en el dispositivo y en S3.") },
        confirmButton = {
            TextButton(onClick = { deleting = false; scope.launch { c.albumRepository.delete(albumId) }; onBack() }) { Text("Eliminar álbum") }
        },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancelar") } },
    )
}

private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id

@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it.take(80) }, singleLine = true, label = { Text("Nombre") }) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
