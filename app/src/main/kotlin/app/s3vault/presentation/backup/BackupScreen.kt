package app.s3vault.presentation.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.s3vault.auth.CredentialStatus
import app.s3vault.data.repo.BackupCounts
import app.s3vault.domain.model.MediaItem
import app.s3vault.presentation.Strings
import app.s3vault.presentation.common.Notice
import app.s3vault.presentation.common.rememberContainer
import app.s3vault.settings.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onOpenS3Config: () -> Unit, onOpenSecurity: () -> Unit) {
    val c = rememberContainer()
    val repo = c.backupRepository
    val scope = rememberCoroutineScope()

    val settings by c.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val counts by repo.counts.collectAsStateWithLifecycle(initialValue = BackupCounts())
    val inFlight by repo.inFlight.collectAsStateWithLifecycle(initialValue = emptyList())
    val queued by repo.queued.collectAsStateWithLifecycle(initialValue = emptyList())
    val failed by repo.failed.collectAsStateWithLifecycle(initialValue = emptyList())
    val lastBackup by repo.lastBackupMs.collectAsStateWithLifecycle(initialValue = null)
    val progress by c.progress.state.collectAsStateWithLifecycle()
    val log by repo.recentLog.collectAsStateWithLifecycle(initialValue = emptyList())
    val authStatus by produceCredentialStatus(c)
    var confirmCancel by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("Respaldo") }) }) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ConnectionBanner(settings, authStatus, onOpenS3Config, onOpenSecurity) {
                    scope.launch { repo.resumeAfterUserAction() }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Counter("Respaldadas", counts.backedUp)
                            Counter("Pendientes", counts.pending)
                            Counter("En cola", counts.queued + counts.inFlight)
                            Counter("Con error", counts.failed)
                        }
                        HorizontalDivider()
                        Text("Última transferencia correcta: ${Strings.dateTime(lastBackup)}", style = MaterialTheme.typography.bodyMedium)
                        if (settings.paused) Text("El respaldo está en pausa.", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = settings.s3Config != null && counts.pending > 0,
                            onClick = { scope.launch { repo.backupAllPending() } },
                        ) { Text("Respaldar pendientes (${counts.pending})") }
                        if (settings.paused) {
                            OutlinedButton(onClick = { scope.launch { repo.resume() } }) { Text("Reanudar") }
                        } else {
                            OutlinedButton(onClick = { scope.launch { repo.pause() } }) { Text("Pausar") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = counts.failed > 0, onClick = { scope.launch { repo.retryFailed() } }) { Text("Reintentar errores") }
                        OutlinedButton(enabled = counts.queued + counts.inFlight > 0, onClick = { confirmCancel = true }) { Text("Vaciar cola") }
                    }
                    Text(
                        "Android decide cuándo ejecutar los trabajos (red, batería, ahorro de energía): la subida puede aplazarse. " +
                            "La cola se conserva aunque cierres la app o reinicies el teléfono.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (inFlight.isNotEmpty()) {
                item { SectionTitle("Transferencias en curso") }
                items(inFlight, key = { "f" + it.assetId }) { item ->
                    val p = progress[item.assetId]
                    ItemRow(item, "${Strings.state(item.state)}${p?.let { " · ${(it.fraction * 100).toInt()} %" } ?: ""}") {
                        if (p != null) LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            if (queued.isNotEmpty()) {
                item { SectionTitle("En cola (${counts.queued})") }
                items(queued, key = { "q" + it.assetId }) { item ->
                    ItemRow(item, item.lastErrorKind?.let { "En cola · ${Strings.errorKind(it)}" } ?: "En cola")
                }
            }
            if (failed.isNotEmpty()) {
                item { SectionTitle("Errores (${counts.failed})") }
                items(failed, key = { "e" + it.assetId }) { item ->
                    ItemRow(item, Strings.errorKind(item.lastErrorKind).ifBlank { "Error" }, isError = true) {
                        TextButton(onClick = { scope.launch { repo.enqueue(listOf(item.assetId)) } }) { Text("Reintentar") }
                    }
                }
            }
            if (log.isNotEmpty()) {
                item { SectionTitle("Registro reciente") }
                items(log.take(10), key = { "l" + it.id }) { e ->
                    Text(
                        "${Strings.dateTime(e.timestampMs)} · ${if (e.kind == "UPLOAD") "Subida" else "Restauración"} · " +
                            if (e.success) "correcta${e.message?.let { " ($it)" } ?: ""}" else Strings.errorKind(e.errorKind),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    if (confirmCancel) AlertDialog(
        onDismissRequest = { confirmCancel = false },
        title = { Text("Vaciar la cola") },
        text = { Text("Se quitará lo pendiente de la cola de subida. No se elimina ninguna foto, ni local ni en S3.") },
        confirmButton = { TextButton(onClick = { confirmCancel = false; scope.launch { repo.cancelQueued() } }) { Text("Vaciar") } },
        dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Cancelar") } },
    )
}

@Composable
private fun produceCredentialStatus(c: app.s3vault.AppContainer) =
    androidx.compose.runtime.produceState<CredentialStatus>(CredentialStatus.NotConfigured, c) {
        // Sin red: solo lee el estado local. Se refresca al cambiar los ajustes y cada 15 s (cuenta atrás de expiración).
        launch { c.settings.settings.collect { value = c.credentials.status() } }
        while (true) {
            value = c.credentials.status()
            delay(15_000)
        }
    }

@Composable
private fun ConnectionBanner(
    settings: AppSettings,
    auth: CredentialStatus,
    onOpenS3Config: () -> Unit,
    onOpenSecurity: () -> Unit,
    onResume: () -> Unit,
) {
    when {
        settings.s3Config == null -> Notice(
            "Amazon S3 no está configurado. La galería funciona sin conexión; configura tu bucket para respaldar.",
            action = { TextButton(onClick = onOpenS3Config) { Text("Configurar") } },
        )
        auth is CredentialStatus.NotConfigured || auth is CredentialStatus.Expired || auth is CredentialStatus.RenewalFailed -> Notice(
            "Sin credenciales vigentes: la cola está detenida y conserva todo el trabajo pendiente. Nada se marca como respaldado.",
            isError = true,
            action = { TextButton(onClick = onOpenSecurity) { Text("Renovar") } },
        )
        settings.queueBlock != null -> Notice(
            Strings.failureType(settings.queueBlock) + " La cola está detenida hasta que lo resuelvas.",
            isError = true,
            action = { TextButton(onClick = onResume) { Text("Reanudar") } },
        )
        auth is CredentialStatus.ExpiringSoon -> Notice(
            "Las credenciales caducan en ${Strings.duration(auth.remaining.seconds)}. Renuévalas para no interrumpir el respaldo.",
            action = { TextButton(onClick = onOpenSecurity) { Text("Renovar") } },
        )
        else -> Unit
    }
}

@Composable
private fun Counter(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun ItemRow(item: MediaItem, subtitle: String, isError: Boolean = false, trailing: @Composable (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(item.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        Text(
            "$subtitle · ${Strings.size(item.sizeBytes)}",
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        trailing?.invoke()
    }
}
