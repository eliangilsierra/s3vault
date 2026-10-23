package com.s3vault.app.presentation.backup

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.s3vault.app.auth.AuthStatus
import com.s3vault.app.domain.MediaItem
import com.s3vault.app.domain.TransferEvent
import com.s3vault.app.domain.TransferEventType
import com.s3vault.app.presentation.BackupViewModel
import com.s3vault.app.presentation.formatBytes
import com.s3vault.app.presentation.formatDateTime
import com.s3vault.app.presentation.syncStateLabel
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.presentation.theme.technical
import com.s3vault.app.presentation.ui.AppTopBar
import com.s3vault.app.presentation.ui.PrimaryButton
import com.s3vault.app.presentation.ui.QuietButton
import com.s3vault.app.presentation.ui.SecondaryButton
import com.s3vault.app.presentation.ui.SectionHeader
import com.s3vault.app.presentation.ui.SlimProgress
import com.s3vault.app.presentation.ui.StatusBanner
import com.s3vault.app.presentation.ui.Tone
import com.s3vault.app.presentation.ui.accent
import com.s3vault.app.presentation.ui.rememberReducedMotion
import com.s3vault.app.sync.Progress

@Composable
fun BackupScreen(vm: BackupViewModel, onOpenS3Settings: () -> Unit, onOpenSecurity: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val uploads by vm.uploads.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar("Respaldo") },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = Space.maxContent).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = Space.screen, vertical = Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            // Blocking conditions first: the user must be told why nothing is moving.
            if (!s.configured) item {
                StatusBanner(
                    Tone.Warning, "Amazon S3 no está configurado",
                    "Los respaldos no pueden empezar hasta que indiques bucket y región.", "Configurar S3", onOpenS3Settings,
                )
            } else if (s.auth is AuthStatus.Missing) item {
                StatusBanner(Tone.Warning, "Faltan credenciales", "Añade credenciales para poder subir archivos.", "Añadir credenciales", onOpenSecurity)
            } else if (s.auth is AuthStatus.Expired || s.needsCredentials) item {
                StatusBanner(
                    Tone.Error, "Credenciales caducadas",
                    "El trabajo pendiente se conserva (${s.queued} en cola) y no se ha marcado nada como respaldado. Introduce credenciales nuevas para continuar.",
                    "Renovar credenciales", onOpenSecurity,
                )
            } else if (s.auth is AuthStatus.ExpiringSoon) item {
                StatusBanner(
                    Tone.Warning, "Las credenciales caducan pronto",
                    "Renuévalas para que los respaldos no se detengan. La app no puede renovarlas sola.", "Ver credenciales", onOpenSecurity,
                )
            }
            if (s.settings.paused) item {
                StatusBanner(
                    Tone.Info, "Respaldo en pausa",
                    "La cola se conserva. Los trabajos no se ejecutarán hasta que reanudes.", "Reanudar", vm::resume,
                )
            }

            item { Overview(s.backedUp, s.pending, s.queued, s.inFlight.size, s.failed.size, s.lastBackupMs, s.settings.paused) }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    PrimaryButton(
                        "Respaldar pendientes (${s.pending})", vm::backupPending, Modifier.fillMaxWidth(),
                        enabled = s.pending > 0, icon = Icons.Filled.CloudUpload,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        if (s.settings.paused) {
                            SecondaryButton("Reanudar", vm::resume, Modifier.weight(1f), icon = Icons.Filled.PlayArrow)
                        } else {
                            SecondaryButton("Pausar", vm::pause, Modifier.weight(1f), icon = Icons.Filled.Pause)
                        }
                        SecondaryButton("Reintentar", vm::retryFailed, Modifier.weight(1f), enabled = s.failed.isNotEmpty(), icon = Icons.Filled.Replay)
                    }
                    if (s.queued > 0) QuietButton("Cancelar la cola (${s.queued}) — no borra nada", vm::cancelQueue, Modifier.fillMaxWidth())
                    Text(
                        "El sistema Android decide cuándo se ejecutan los trabajos (red, batería, ahorro de energía): no se garantiza una subida inmediata.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (s.configured && s.settings.cloudPreviews && s.previewBacklog > 0) item {
                PreviewsCard(s.previewBacklog, enabled = !s.settings.paused && s.auth !is AuthStatus.Expired, onCreate = vm::createPreviews)
            }

            if (s.inFlight.isNotEmpty()) {
                item { SectionHeader("Transferencias en curso") }
                items(s.inFlight, key = { "f-${it.mediaStoreId}" }) { InFlightRow(it, uploads[it.mediaStoreId]) }
            }

            if (s.failed.isNotEmpty()) {
                item { SectionHeader("Errores") }
                items(s.failed, key = { "e-${it.mediaStoreId}" }) { FailedRow(it) }
            }

            if (s.events.isNotEmpty()) {
                item { SectionHeader("Registro reciente") }
                items(s.events, key = { "ev-${it.id}" }) { EventRow(it) }
            }
        }
        }
    }
}

/**
 * The one-glance answer: how much of the library is confirmed in S3, and what is happening now.
 * Every number is a real count from the sync state; the bar is backed-up over total, never an estimate.
 */
@Composable
private fun Overview(backedUp: Int, pending: Int, queued: Int, inFlight: Int, failed: Int, last: Long?, paused: Boolean) {
    val total = backedUp + pending + queued + inFlight + failed
    val reduced = rememberReducedMotion()
    val fraction by animateFloatAsState(
        if (total > 0) backedUp.toFloat() / total else 0f, tween(if (reduced) 0 else 400), label = "overviewProgress",
    )
    val (tone, headline, icon) = when {
        total == 0 -> Triple(Tone.Neutral, "Aún no hay fotos que respaldar", Icons.Filled.CloudSync)
        inFlight > 0 -> Triple(Tone.Info, "Subiendo ahora", Icons.Filled.CloudSync)
        paused && queued > 0 -> Triple(Tone.Info, "En pausa · $queued en cola", Icons.Filled.Pause)
        queued > 0 -> Triple(Tone.Info, "$queued en cola", Icons.Filled.CloudSync)
        failed > 0 -> Triple(Tone.Error, if (failed == 1) "1 elemento con error" else "$failed elementos con error", Icons.Filled.ErrorOutline)
        pending > 0 -> Triple(Tone.Neutral, if (pending == 1) "1 sin respaldar" else "$pending sin respaldar", Icons.Filled.CloudUpload)
        else -> Triple(Tone.Success, "Todo respaldado", Icons.Filled.CheckCircle)
    }
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(Space.xl), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Icon(icon, null, Modifier.size(20.dp), tint = tone.accent())
                Text(headline, style = MaterialTheme.typography.titleMedium)
            }
            if (total > 0) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text("$backedUp", style = MaterialTheme.typography.displaySmall)
                    Text(
                        "de $total respaldadas y verificadas",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = Space.s),
                    )
                }
                SlimProgress(fraction, Modifier.semantics { stateDescription = "$backedUp de $total respaldadas" })
            }
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Stat("Sin respaldar", pending)
                Stat("En cola", queued)
                Stat("En transferencia", inFlight)
                Stat("Con error", failed)
            }
            Text(
                "Última transferencia correcta: " + (last?.let { formatDateTime(it) } ?: "ninguna todavía"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Older backups have no S3 preview yet. Creating them re-reads the photo on this device and uploads ~50 KB each, so it
 * is a visible, optional step with its cost spelled out instead of a silent background surprise.
 */
@Composable
private fun PreviewsCard(pending: Int, enabled: Boolean, onCreate: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Icon(Icons.Filled.Image, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (pending == 1) "1 foto sin vista previa en S3" else "$pending fotos sin vista previa en S3",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                "Con una vista previa puedes ver la foto en la pestaña S3 sin descargar el original. Se crea desde la copia de este dispositivo " +
                    "y se sube una miniatura de unos 50 KB (una petición PUT por foto). Se hace sola tras cada respaldo; esto completa las anteriores.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SecondaryButton("Crear vistas previas", onCreate, Modifier.fillMaxWidth(), enabled = enabled, icon = Icons.Filled.CloudUpload)
        }
    }
}

@Composable
private fun Stat(label: String, n: Int) {
    val color = if (n == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(n.toString(), style = MaterialTheme.typography.titleSmall, color = color)
    }
}

@Composable
private fun InFlightRow(item: MediaItem, progress: Progress?) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs + 2.dp)) {
        Text(item.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        SlimProgress(progress?.fraction)
        Text(
            if (progress != null) "${syncStateLabel(item.syncState)} · ${formatBytes(progress.doneBytes)} de ${formatBytes(progress.totalBytes)}"
            else syncStateLabel(item.syncState),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Plain-language summary first; the raw error text is one tap away for people who need it. */
@Composable
private fun FailedRow(item: MediaItem) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        Modifier.fillMaxWidth().animateContentSize(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.clickable(role = Role.Button, onClickLabel = if (expanded) "Ocultar detalles" else "Ver detalles") { expanded = !expanded }
                .heightIn(min = Space.touch).padding(Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                Icon(Icons.Filled.ErrorOutline, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                Text(item.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                if (item.lastErrorPermanent) "Error permanente: corrige la causa y pulsa Reintentar." else "Reintentos agotados.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                Text(item.lastError ?: "Error desconocido", style = MaterialTheme.technical, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun EventRow(e: TransferEvent) {
    val (label, icon, tone) = when (e.type) {
        TransferEventType.UPLOADED -> Triple("Subida correcta", Icons.Filled.CheckCircle, Tone.Success)
        TransferEventType.UPLOAD_FAILED -> Triple("Fallo al subir", Icons.Filled.ErrorOutline, Tone.Error)
        TransferEventType.RESTORED -> Triple("Restaurada", Icons.Filled.CheckCircle, Tone.Success)
        TransferEventType.RESTORE_FAILED -> Triple("Fallo al restaurar", Icons.Filled.ErrorOutline, Tone.Error)
        TransferEventType.PAUSED -> Triple("Pausa", Icons.Filled.Pause, Tone.Neutral)
        TransferEventType.CREDENTIALS_NEEDED -> Triple("Credenciales necesarias", Icons.Filled.ErrorOutline, Tone.Warning)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        Icon(icon, null, Modifier.padding(top = 2.dp).size(18.dp), tint = tone.accent())
        Column(Modifier.weight(1f)) {
            Text("$label — ${e.name}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                formatDateTime(e.timestampMs) + (e.message?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
