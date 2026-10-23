package com.s3vault.app.presentation.remote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.s3vault.app.domain.RemoteObject
import com.s3vault.app.domain.SyncState
import com.s3vault.app.presentation.RemoteViewModel
import com.s3vault.app.presentation.formatBytes
import com.s3vault.app.presentation.formatDateTime
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.presentation.theme.status
import com.s3vault.app.presentation.theme.technical
import com.s3vault.app.presentation.ui.AppTopBar
import com.s3vault.app.presentation.ui.ConfirmDialog
import com.s3vault.app.presentation.ui.EmptyState
import com.s3vault.app.presentation.ui.LabeledValue
import com.s3vault.app.presentation.ui.PrimaryButton
import com.s3vault.app.presentation.ui.QuietButton
import com.s3vault.app.presentation.ui.SlimProgress
import com.s3vault.app.presentation.ui.Tone
import com.s3vault.app.presentation.ui.skeleton
import com.s3vault.app.sync.Progress

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RemoteScreen(vm: RemoteViewModel, onOpenS3Settings: () -> Unit, onOpenSecurity: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val selected by vm.selectedKeys.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmDuplicates by remember { mutableStateOf<List<RemoteObject>?>(null) }
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting) { vm.clearSelection() }

    LaunchedEffect(s.message) { s.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }
    LaunchedEffect(s.configured) { if (s.configured && s.items.isEmpty()) vm.refresh() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = if (selecting) (if (selected.size == 1) "1 seleccionada" else "${selected.size} seleccionadas") else "Biblioteca en S3",
                subtitle = if (!selecting && s.items.isNotEmpty()) (if (s.items.size == 1) "1 objeto" else "${s.items.size} objetos") else null,
                containerColor = if (selecting) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.background,
                navigation = if (selecting) {
                    { IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.Close, "Cancelar selección") } }
                } else null,
                actions = {
                    if (selecting) {
                        IconButton(onClick = {
                            val chosen = s.items.filter { it.key in selected && it.state == SyncState.REMOTE_ONLY }
                            val dupes = chosen.filter { it.availableLocally }
                            if (dupes.isNotEmpty()) confirmDuplicates = chosen else vm.restoreSelected()
                        }) { Icon(Icons.Filled.Download, "Restaurar") }
                    } else {
                        IconButton(onClick = vm::refresh, enabled = s.configured && !s.loading) { Icon(Icons.Filled.Refresh, "Actualizar listado") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (s.loading) SlimProgress(null, Modifier.padding(horizontal = Space.screen))
            when {
                !s.configured -> EmptyState(
                    icon = Icons.Filled.CloudOff,
                    title = "Amazon S3 no está configurado",
                    body = "Configura bucket y credenciales para explorar tus respaldos.",
                ) { PrimaryButton("Configurar S3", onOpenS3Settings, Modifier.fillMaxWidth()) }
                s.error != null && s.items.isEmpty() -> EmptyState(
                    icon = Icons.Filled.ErrorOutline,
                    tone = if (s.needsCredentials) Tone.Warning else Tone.Error,
                    title = "No se pudo leer el bucket",
                    body = s.error,
                ) {
                    PrimaryButton("Reintentar", vm::refresh, Modifier.fillMaxWidth())
                    if (s.needsCredentials) QuietButton("Ir a credenciales", onOpenSecurity)
                }
                s.items.isEmpty() && s.loading -> RemoteSkeleton()
                s.items.isEmpty() -> EmptyState(
                    icon = Icons.Filled.CloudQueue,
                    title = "No hay objetos respaldados",
                    body = "No se encontró nada bajo el prefijo configurado. Cada listado es una petición LIST de S3 (con coste mínimo): actualiza solo cuando lo necesites.",
                )
                else -> LazyColumn(Modifier.widthIn(max = Space.maxContent).fillMaxSize().align(Alignment.CenterHorizontally)) {
                    if (s.error != null) item {
                        Row(Modifier.padding(Space.screen), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                            Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                            Text(s.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    items(s.items, key = { it.key }) { obj ->
                        RemoteRow(
                            obj, obj.key in selected, selecting, downloads[obj.key],
                            onClick = { if (selecting) vm.toggle(obj.key) else vm.showDetail(obj) },
                            onLongClick = { if (obj.state == SyncState.REMOTE_ONLY) vm.toggle(obj.key) },
                            onCancel = { vm.cancelRestore(obj.key) },
                        )
                    }
                }
            }
        }
    }

    confirmDuplicates?.let { chosen ->
        ConfirmDialog(
            title = "Ya tienes copias en el dispositivo",
            text = "${chosen.count { it.availableLocally }} de las ${chosen.size} fotos ya están en este dispositivo. " +
                "Restaurar crea copias nuevas (nunca sobrescribe archivos existentes). ¿Continuar?",
            confirmLabel = "Restaurar copias",
            onConfirm = { confirmDuplicates = null; vm.restoreSelected() },
            onDismiss = { confirmDuplicates = null },
        )
    }

    s.detail?.let { d ->
        ModalBottomSheet(onDismissRequest = vm::dismissDetail, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(horizontal = Space.screen).padding(bottom = Space.xl),
                verticalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                Text("Metadatos del objeto", style = MaterialTheme.typography.titleLarge)
                when {
                    d.error != null -> Text(d.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    d.lines == null -> SlimProgress(null)
                    else -> d.lines.forEach { (k, v) ->
                        // Identifiers (keys, checksums) scan better in monospace than as prose.
                        val identifier = v.length > 28 && ' ' !in v
                        Column {
                            Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(v, style = if (identifier) MaterialTheme.technical else MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                QuietButton("Cerrar", vm::dismissDetail, Modifier.align(Alignment.End))
            }
        }
    }
}

@Composable
private fun RemoteSkeleton() {
    Column(Modifier.fillMaxSize().padding(horizontal = Space.screen, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.l)) {
        repeat(8) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.l), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(MaterialTheme.shapes.medium).skeleton())
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Box(Modifier.size(width = 180.dp, height = 14.dp).clip(MaterialTheme.shapes.extraSmall).skeleton())
                    Box(Modifier.size(width = 120.dp, height = 12.dp).clip(MaterialTheme.shapes.extraSmall).skeleton())
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RemoteRow(
    obj: RemoteObject,
    selected: Boolean,
    selecting: Boolean,
    progress: Progress?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCancel: () -> Unit,
) {
    val restoring = obj.state == SyncState.RESTORING
    val (statusIcon: ImageVector, statusText: String) = when {
        restoring -> Icons.Filled.CloudSync to "Restaurando…"
        obj.availableLocally -> Icons.Filled.CloudDone to "También en este dispositivo"
        else -> Icons.Filled.CloudQueue to "Solo en S3"
    }
    val statusTint = when {
        restoring -> MaterialTheme.colorScheme.primary
        obj.availableLocally -> MaterialTheme.status.success
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else androidx.compose.ui.graphics.Color.Transparent)
            .semantics { this.selected = selected }
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = 72.dp).padding(horizontal = Space.screen, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        if (selecting && obj.state == SyncState.REMOTE_ONLY) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
        } else {
            Box(
                Modifier.size(48.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(obj.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${formatBytes(obj.sizeBytes)} · ${formatDateTime(obj.lastModifiedMs)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs + 2.dp)) {
                Icon(statusIcon, null, Modifier.size(14.dp), tint = statusTint)
                Text(statusText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (obj.lastError != null && !restoring) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.xs + 2.dp)) {
                    Icon(Icons.Filled.ErrorOutline, null, Modifier.padding(top = 1.dp).size(14.dp), tint = MaterialTheme.colorScheme.error)
                    Text(obj.lastError, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            if (restoring) SlimProgress(progress?.fraction, Modifier.padding(top = Space.xs))
        }
        if (restoring) QuietButton("Cancelar", onCancel)
    }
}
