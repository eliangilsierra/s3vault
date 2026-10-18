package app.s3vault.presentation.remote

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.s3vault.data.local.RestoreStatus
import app.s3vault.data.repo.RemoteListItem
import app.s3vault.domain.model.SyncState
import app.s3vault.presentation.Strings
import app.s3vault.presentation.common.EmptyState
import app.s3vault.presentation.common.LoadingState
import app.s3vault.presentation.common.Notice
import app.s3vault.presentation.common.appViewModel
import app.s3vault.settings.AppSettings

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RemoteScreen(onOpenS3Config: () -> Unit) {
    val vm = appViewModel { RemoteViewModel(it) }
    val items by vm.items.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val lastRefresh by vm.lastRefresh.collectAsStateWithLifecycle(initialValue = null)
    val details by vm.details.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val snackbar = remember { SnackbarHostState() }
    var confirmDuplicates by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    fun requestRestore(keys: List<String>) {
        val existing = items.orEmpty().filter { it.entry.key in keys && it.state == SyncState.BACKED_UP }
        if (existing.isNotEmpty()) confirmDuplicates = keys else vm.restore(keys)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Biblioteca remota") },
                actions = {
                    IconButton(onClick = vm::refresh, enabled = settings.s3Config != null && !refreshing) {
                        Icon(Icons.Filled.Refresh, "Actualizar desde S3")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (selection.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = vm::clearSelection) { Text("Cancelar") }
                    Button(onClick = { requestRestore(selection.toList()) }) { Text("Restaurar ${selection.size}") }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (settings.s3Config == null) {
                EmptyState(
                    "Amazon S3 no está configurado", "Configura tu bucket para explorar lo que ya respaldaste.",
                    actionLabel = "Configurar", onAction = onOpenS3Config,
                )
                return@Column
            }
            Text(
                "Última actualización: ${Strings.dateTime(lastRefresh)}. Actualizar hace una solicitud LIST por cada 1000 objetos (puede tener un costo mínimo en AWS).",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val list = items
            when {
                list == null -> LoadingState()
                list.isEmpty() -> EmptyState(
                    "Sin objetos", "Pulsa actualizar para leer el contenido de tu bucket. Se muestran solo los objetos creados por esta app.",
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.entry.key }) { item ->
                        RemoteRow(
                            item, selected = item.entry.key in selection, selecting = selection.isNotEmpty(),
                            onClick = { if (selection.isNotEmpty()) vm.toggle(item.entry.key) else vm.showDetails(item.entry.key) },
                            onLongClick = { vm.toggle(item.entry.key) },
                            onDismissError = { vm.dismissError(item.entry.key) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    details?.let { d ->
        val item = items.orEmpty().firstOrNull { it.entry.key == d.key }
        AlertDialog(
            onDismissRequest = vm::closeDetails,
            title = { Text(item?.entry?.name ?: "Objeto remoto") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (d.head == null && d.error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    d.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    item?.let { Text("Tamaño: ${Strings.size(it.entry.sizeBytes)}") }
                    d.head?.let { h ->
                        Text("Modificado: ${Strings.dateTime(h.lastModifiedMs)}")
                        h.contentType?.let { Text("Tipo: $it") }
                        Text("Integridad: " + if (h.checksumSha256Base64 != null) "SHA-256 almacenado por S3" else "sin checksum SHA-256 en S3")
                    }
                    d.metadata?.let { m ->
                        if (m.originalName.isNotBlank()) Text("Nombre original: ${m.originalName}")
                        m.capturedAtMs?.let { Text("Captura: ${Strings.dateTime(it)}") }
                        if (m.width != null && m.height != null) Text("Dimensiones: ${m.width} × ${m.height}")
                    }
                    item?.entry?.restoreError?.let { Text("Último error: $it", color = MaterialTheme.colorScheme.error) }
                    Text("Estado: ${item?.let { Strings.state(it.state) } ?: "—"}")
                }
            },
            confirmButton = {
                TextButton(
                    enabled = item != null && item.state != SyncState.RESTORING && d.error == null,
                    onClick = { requestRestore(listOf(d.key)); vm.closeDetails() },
                ) { Text("Restaurar") }
            },
            dismissButton = { TextButton(onClick = vm::closeDetails) { Text("Cerrar") } },
        )
    }

    confirmDuplicates?.let { keys ->
        AlertDialog(
            onDismissRequest = { confirmDuplicates = null },
            title = { Text("Ya existe en el dispositivo") },
            text = { Text("Alguna de las fotos elegidas ya está en este dispositivo. Restaurarla crea una copia nueva en Imágenes/S3Vault; no se sobrescribe ni se reemplaza nada.") },
            confirmButton = { TextButton(onClick = { confirmDuplicates = null; vm.restore(keys) }) { Text("Restaurar copia") } },
            dismissButton = { TextButton(onClick = { confirmDuplicates = null }) { Text("Cancelar") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RemoteRow(
    item: RemoteListItem,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDismissError: () -> Unit,
) {
    val e = item.entry
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selecting) Checkbox(checked = selected, onCheckedChange = { onClick() })
        Column(Modifier.weight(1f)) {
            Text(e.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text("${Strings.size(e.sizeBytes)} · ${Strings.dateTime(e.lastModifiedMs)}", style = MaterialTheme.typography.bodySmall)
            if (e.restoreStatus == RestoreStatus.FAILED) {
                Text(
                    "Falló la restauración" + Strings.restoreError(e.restoreError),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (e.restoreStatus == RestoreStatus.FAILED) {
            TextButton(onClick = onDismissError) { Text("Descartar") }
        }
        AssistChip(onClick = {}, label = { Text(Strings.state(item.state)) })
    }
}
