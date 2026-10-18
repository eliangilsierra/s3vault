package app.s3vault.presentation.gallery

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.s3vault.data.repo.MediaPermissions
import app.s3vault.presentation.common.EmptyState
import app.s3vault.presentation.common.LoadingState
import app.s3vault.presentation.common.Notice
import app.s3vault.presentation.common.appViewModel
import app.s3vault.presentation.common.shareUris
import app.s3vault.presentation.Strings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(onOpenViewer: (assetId: String) -> Unit) {
    val vm = appViewModel { GalleryViewModel(it) }
    val context = LocalContext.current
    val items by vm.items.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle(initialValue = emptyList())
    val snackbar = remember { SnackbarHostState() }

    var permissionVersion by remember { mutableIntStateOf(0) }
    val hasAccess = remember(permissionVersion) { MediaPermissions.hasAnyAccess(context) }
    val partial = remember(permissionVersion) { MediaPermissions.hasPartialAccess(context) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionVersion++
    }

    // Reindexar al abrir y al volver a la app (por si el usuario cambió permisos o hizo fotos).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permissionVersion++ }
    LaunchedEffect(permissionVersion, hasAccess) { if (hasAccess) vm.refresh() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) vm.onDeleted()
    }
    var confirmDelete by remember { mutableStateOf(false) }
    var albumPicker by remember { mutableStateOf(false) }

    val selecting = selection.isNotEmpty()
    BackHandler(enabled = selecting) { vm.clearSelection() }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text("${selection.size} seleccionadas") },
                    navigationIcon = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.ArrowBack, "Cancelar selección") } },
                    actions = {
                        IconButton(onClick = vm::selectAll) { Icon(Icons.Filled.Check, "Seleccionar todo") }
                        IconButton(onClick = vm::backupSelected) { Icon(Icons.Filled.Send, "Respaldar en S3") }
                        IconButton(onClick = { albumPicker = true }) { Icon(Icons.Filled.Add, "Añadir a álbum") }
                        IconButton(onClick = {
                            val chosen = items.orEmpty().filter { it.assetId in selection }
                            shareUris(context, chosen.mapNotNull { it.uri?.let(Uri::parse) }, "*/*")
                        }) { Icon(Icons.Filled.Share, "Compartir") }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Eliminar del dispositivo") }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("Galería") },
                    actions = {
                        IconButton(onClick = vm::refresh, enabled = hasAccess && !scanning) { Icon(Icons.Filled.Refresh, "Actualizar") }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (partial) {
                Notice(
                    "Acceso parcial: solo se muestran las fotos que elegiste. Concede acceso total para ver y respaldar toda la galería.",
                    Modifier.padding(8.dp),
                ) {
                    TextButton(onClick = { permissionLauncher.launch(MediaPermissions.requested()) }) { Text("Cambiar") }
                }
            }
            when {
                !hasAccess -> EmptyState(
                    "Permiso necesario",
                    "Para mostrar tus fotos necesitamos acceso a ellas. Las fotos no salen del dispositivo salvo que tú las respaldes en tu bucket.",
                    actionLabel = "Conceder acceso",
                    onAction = { permissionLauncher.launch(MediaPermissions.requested()) },
                )
                items == null -> LoadingState()
                items.orEmpty().isEmpty() -> EmptyState(
                    "No hay fotos",
                    if (scanning) "Leyendo la galería…" else "No se encontraron fotos ni vídeos en el dispositivo.",
                )
                else -> MediaGrid(
                    items = items.orEmpty(),
                    selected = selection,
                    onClick = { item -> if (selecting) vm.toggle(item.assetId) else onOpenViewer(item.assetId) },
                    onLongClick = { item -> if (selecting) vm.toggle(item.assetId) else vm.startSelection(item.assetId) },
                )
            }
        }
    }

    if (confirmDelete) {
        val chosen = items.orEmpty().filter { it.assetId in selection }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Eliminar ${chosen.size} del dispositivo") },
            text = { Text(Strings.MEDIA_DELETE_NOTE) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val uris = chosen.mapNotNull { it.uri?.let(Uri::parse) }
                    if (uris.isNotEmpty()) {
                        val pending = MediaStore.createTrashRequest(context.contentResolver, uris, true)
                        deleteLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                    }
                }) { Text("Continuar") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }

    if (albumPicker) {
        var newName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { albumPicker = false },
            title = { Text("Añadir a álbum") },
            text = {
                Column {
                    albums.forEach { album ->
                        TextButton(onClick = { vm.addSelectionToAlbum(album.id); albumPicker = false }) {
                            Text("${album.name} (${album.itemCount})")
                        }
                    }
                    OutlinedTextField(newName, { newName = it }, label = { Text("Nuevo álbum") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = { vm.createAlbumWithSelection(newName); albumPicker = false },
                ) { Text("Crear y añadir") }
            },
            dismissButton = { TextButton(onClick = { albumPicker = false }) { Text("Cerrar") } },
        )
    }
}

/** Abre los ajustes de la app para cambiar permisos cuando el sistema ya no vuelve a preguntar. */
@Suppress("unused")
fun appSettingsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
