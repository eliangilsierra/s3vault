package com.s3vault.app.presentation.sync

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.s3vault.app.auth.AuthStatus
import com.s3vault.app.data.local.JobEntity
import com.s3vault.app.data.local.JobState
import com.s3vault.app.domain.MediaItem
import com.s3vault.app.domain.RemoteObject
import com.s3vault.app.domain.SyncState
import com.s3vault.app.domain.TransferEvent
import com.s3vault.app.domain.sync.CloudStatus
import com.s3vault.app.domain.sync.FailureAdviser
import com.s3vault.app.domain.sync.FailureFix
import com.s3vault.app.domain.sync.RowAction
import com.s3vault.app.domain.sync.SyncCounts
import com.s3vault.app.domain.sync.SyncFilter
import com.s3vault.app.domain.sync.SyncPresentation
import com.s3vault.app.presentation.SyncPill
import com.s3vault.app.presentation.formatAge
import com.s3vault.app.presentation.formatBytes
import com.s3vault.app.presentation.formatCount
import com.s3vault.app.presentation.formatDateTime
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.presentation.theme.status
import com.s3vault.app.presentation.theme.technical
import com.s3vault.app.presentation.ui.AppTopBar
import com.s3vault.app.presentation.ui.ChoiceChip
import com.s3vault.app.presentation.ui.PrimaryButton
import com.s3vault.app.presentation.ui.QuietButton
import com.s3vault.app.presentation.ui.SecondaryButton
import com.s3vault.app.presentation.ui.SlimProgress
import com.s3vault.app.presentation.ui.StatusBanner
import com.s3vault.app.presentation.ui.Tone
import com.s3vault.app.presentation.ui.accent
import com.s3vault.app.presentation.ui.rememberReducedMotion

/**
 * The synchronization centre: one place that says, for every photo, where it is (this device, Amazon S3, both, moving) and what
 * is happening to it. It reads only the sync state the engines already keep in Room, so it cannot disagree with them, and it
 * reuses their operations for retry, cancel, pause and restore.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SyncCenterScreen(
    vm: SyncCenterViewModel,
    onOpenS3Settings: () -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenPhoto: (Long) -> Unit,
    onOpenCloudPhoto: (String) -> Unit,
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val list by vm.list.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }
    LaunchedEffect(filter) { expanded = null }
    // Asks for the next page a little before the end, so scrolling never stops at a wall.
    LaunchedEffect(listState, list.hasMore, list.rows.size) {
        snapshotFlow { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) to listState.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (list.hasMore && total > 0 && last >= total - 8) vm.loadMore() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { AppTopBar("Respaldo", subtitle = "Sincronización con Amazon S3") },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = Space.maxContent).fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(horizontal = Space.screen, vertical = Space.s),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                item(key = "banners") { Banners(s, vm, onOpenS3Settings, onOpenSecurity) }
                item(key = "overview") { Overview(s) }
                item(key = "last") { LastSync(s) }
                item(key = "actions") { Actions(s, vm) }
                stickyHeader(key = "filters") { Filters(s.counts, filter, vm::select) }

                if (list.rows.isEmpty() && !list.loading) {
                    item(key = "empty") { EmptyList(filter, s) }
                }
                items(list.rows, key = { it.key }, contentType = { "row" }) { row ->
                    val key = row.key
                    when (row) {
                        is SyncRow.Local -> LocalRowItem(
                            row.item, vm, expanded == key, { expanded = if (expanded == key) null else key },
                            onOpen = { onOpenPhoto(row.item.mediaStoreId) },
                            onFix = { fix -> if (fix == FailureFix.CREDENTIALS) onOpenSecurity() else onOpenS3Settings() },
                        )
                        is SyncRow.Cloud -> CloudRowItem(
                            row.obj, vm, expanded == key, { expanded = if (expanded == key) null else key },
                            onOpen = { onOpenCloudPhoto(row.obj.key) },
                        )
                    }
                }
                if (list.hasMore) item(key = "more") { SlimProgress(null, Modifier.padding(vertical = Space.l)) }
            }
        }
    }
}

// ------------------------------------------------------------------ top of the screen

@Composable
private fun Banners(s: SyncCenterState, vm: SyncCenterViewModel, onOpenS3Settings: () -> Unit, onOpenSecurity: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        // Blocking conditions first: the user must be told why nothing is moving.
        if (!s.configured) {
            StatusBanner(Tone.Warning, "Amazon S3 no está configurado", "Los respaldos no pueden empezar hasta que indiques bucket y región.", "Configurar S3", onOpenS3Settings)
        } else if (s.auth is AuthStatus.Missing) {
            StatusBanner(Tone.Warning, "Faltan credenciales", "Añade credenciales para poder subir archivos.", "Añadir credenciales", onOpenSecurity)
        } else if (s.auth is AuthStatus.Expired || s.needsCredentials) {
            StatusBanner(
                Tone.Error, "Credenciales caducadas",
                "El trabajo pendiente se conserva (${s.counts.queued} en cola) y no se ha marcado nada como respaldado. Introduce credenciales nuevas para continuar.",
                "Renovar credenciales", onOpenSecurity,
            )
        } else if (s.auth is AuthStatus.ExpiringSoon) {
            StatusBanner(
                Tone.Warning, "Las credenciales caducan pronto",
                "Renuévalas para que los respaldos no se detengan. La app no puede renovarlas sola.", "Ver credenciales", onOpenSecurity,
            )
        }
        if (s.settings.paused) {
            StatusBanner(Tone.Info, "Respaldo en pausa", "La cola se conserva. Los trabajos no se ejecutarán hasta que reanudes.", "Reanudar", onAction = { vm.resume() })
        }
    }
}

/** The one-glance answer: how much is confirmed in S3 and what is happening now. Every number is a real count of sync state. */
@Composable
private fun Overview(s: SyncCenterState) {
    val c = s.counts
    val total = c.localTotal
    val reduced = rememberReducedMotion()
    val fraction by animateFloatAsState(if (total > 0) c.backedUp.toFloat() / total else 0f, tween(if (reduced) 0 else 400), label = "overviewProgress")
    val (tone, headline, icon) = when {
        total == 0 && c.remoteOnly == 0 -> Triple(Tone.Neutral, "Aún no hay fotos que respaldar", Icons.Filled.CloudSync)
        c.uploading > 0 -> Triple(Tone.Info, "Subiendo ahora", Icons.Filled.CloudSync)
        s.settings.paused && c.queued > 0 -> Triple(Tone.Info, "En pausa · ${c.queued} en cola", Icons.Filled.Pause)
        c.queued > 0 -> Triple(Tone.Info, "${c.queued} en cola", Icons.Filled.CloudSync)
        c.failed > 0 -> Triple(Tone.Error, if (c.failed == 1) "1 elemento con error" else "${c.failed} elementos con error", Icons.Filled.ErrorOutline)
        c.localOnly > 0 -> Triple(Tone.Neutral, if (c.localOnly == 1) "1 sin respaldar" else "${c.localOnly} sin respaldar", Icons.Filled.CloudUpload)
        else -> Triple(Tone.Success, "Todo respaldado", Icons.Filled.CheckCircle)
    }
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Icon(icon, null, Modifier.size(20.dp), tint = tone.accent())
                Text(headline, style = MaterialTheme.typography.titleMedium)
            }
            if (total > 0) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text(formatCount(c.backedUp), style = MaterialTheme.typography.displaySmall)
                    Text(
                        "de ${formatCount(total)} respaldadas y verificadas",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = Space.s),
                    )
                }
                SlimProgress(fraction, Modifier.semantics { stateDescription = "${c.backedUp} de $total respaldadas" })
            }
            s.backupRun?.let { RunSummary("Subidas", it) }
            s.restoreRun?.takeIf { it.state != JobState.COMPLETED || c.restoring > 0 }?.let { RunSummary("Restauraciones", it) }
            Text(
                "Cuenta como respaldada solo la foto cuya subida pasó la verificación (tamaño y SHA-256 en S3).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** What the last (or the running) pass did. A pass with failures is never described as complete. */
@Composable
private fun RunSummary(label: String, run: JobEntity) {
    val running = run.state == JobState.RUNNING
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs), modifier = Modifier.semantics(mergeDescendants = true) { }) {
        val (word, tone) = when (run.state) {
            JobState.RUNNING -> "en curso" to Tone.Info
            JobState.COMPLETED -> "completadas" to Tone.Success
            JobState.PARTIAL -> "terminó con errores" to Tone.Error
            JobState.RETRY_LATER -> "pendiente de reintento" to Tone.Warning
            JobState.NEEDS_ATTENTION -> "necesita tu atención" to Tone.Warning
            JobState.CANCELLED -> "detenida (la cola se conserva)" to Tone.Neutral
            JobState.INTERRUPTED -> "interrumpida (se reanuda sola si queda cola)" to Tone.Neutral
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            Text("$label: $word", style = MaterialTheme.typography.titleSmall, color = tone.accent(), modifier = Modifier.weight(1f, fill = false))
            Text(formatAge(run.finishedAtMs ?: run.updatedAtMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (running && run.totalItems > 0) {
            SlimProgress(run.doneItems.toFloat() / run.totalItems)
            Text("${run.doneItems} de ${run.totalItems} procesadas", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (run.succeededItems > 0 || run.failedItems > 0) {
            Text(
                buildString {
                    append("${run.succeededItems} verificadas")
                    if (run.failedItems > 0) append(" · ${run.failedItems} con error")
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!running && run.lastError != null) {
            Text(run.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LastSync(s: SyncCenterState) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs), modifier = Modifier.padding(horizontal = Space.xs)) {
        Text(
            "Última transferencia correcta: " + (s.lastSuccessMs?.let { formatDateTime(it) } ?: "ninguna todavía"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (s.completedToday > 0 || s.failedToday > 0) {
            Text(
                "Hoy: ${s.completedToday} completadas · ${s.failedToday} con error",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        s.lastFailure?.let { LastFailure(it, resolved = (s.lastSuccessMs ?: 0L) > it.timestampMs) }
        Text(
            "Android decide cuándo se ejecutan los trabajos (red, batería, ahorro de energía): no se garantiza una subida inmediata.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LastFailure(e: TransferEvent, resolved: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.ErrorOutline, null, Modifier.padding(top = 2.dp).size(16.dp), tint = if (resolved) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error)
        Text(
            buildString {
                append(if (resolved) "Último error (anterior a la última transferencia correcta): " else "Último error: ")
                append(e.name)
                e.message?.let { append(" — ").append(it.substringBefore(" [")) }
                append(" · ").append(formatAge(e.timestampMs))
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (resolved) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Actions(s: SyncCenterState, vm: SyncCenterViewModel) {
    val c = s.counts
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        PrimaryButton("Respaldar pendientes (${c.localOnly})", vm::backupPending, Modifier.fillMaxWidth(), enabled = c.localOnly > 0, icon = Icons.Filled.CloudUpload)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            if (s.settings.paused) SecondaryButton("Reanudar", vm::resume, Modifier.weight(1f), icon = Icons.Filled.PlayArrow)
            else SecondaryButton("Pausar", vm::pause, Modifier.weight(1f), icon = Icons.Filled.Pause)
            SecondaryButton("Reintentar (${c.failed})", vm::retryFailed, Modifier.weight(1f), enabled = c.failed > 0, icon = Icons.Filled.Replay)
        }
        if (c.queued > 0) QuietButton("Cancelar la cola (${c.queued}) — no borra nada", vm::cancelQueue, Modifier.fillMaxWidth())
        if (s.configured && s.settings.cloudPreviews && s.previewBacklog > 0) {
            SecondaryButton(
                if (s.previewBacklog == 1) "Crear 1 vista previa que falta" else "Crear ${s.previewBacklog} vistas previas que faltan",
                vm::createPreviews, Modifier.fillMaxWidth(), enabled = !s.settings.paused && s.auth !is AuthStatus.Expired, icon = Icons.Filled.Image,
            )
            Text(
                "Cada vista previa es una miniatura de unos 50 KB (una petición PUT) creada desde la copia de este dispositivo; se hace sola tras cada respaldo y esto completa las anteriores.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Stays at the top while the list scrolls, so the filter can be changed from anywhere. Eight short chips: a plain scrolling row is enough. */
@Composable
private fun Filters(counts: SyncCounts, selected: SyncFilter, onSelect: (SyncFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).horizontalScroll(rememberScrollState()).padding(vertical = Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        SyncFilter.entries.forEach { f ->
            val n = counts.of(f)
            ChoiceChip(f == selected, if (f == SyncFilter.ACTIVITY && n == 0) f.label else "${f.label} · ${formatCount(n)}", { onSelect(f) })
        }
    }
}

@Composable
private fun EmptyList(filter: SyncFilter, s: SyncCenterState) {
    val (title, body) = when (filter) {
        SyncFilter.ACTIVITY -> "Todo al día" to "No hay subidas en curso, en cola ni con error."
        SyncFilter.LOCAL_ONLY -> "Nada sin respaldar" to "Todas las fotos del dispositivo están en cola o respaldadas."
        SyncFilter.QUEUED -> "La cola está vacía" to "Las fotos que pidas respaldar esperan aquí hasta que el sistema las suba."
        SyncFilter.UPLOADING -> "Nada subiéndose" to "Cuando haya una subida en curso la verás aquí con su progreso."
        SyncFilter.BACKED_UP -> "Aún no hay fotos respaldadas" to "Una foto aparece aquí cuando su subida pasa la verificación."
        SyncFilter.FAILED -> "Sin errores" to "Si una subida falla, aquí verás por qué y podrás reintentarla."
        SyncFilter.REMOTE_ONLY -> "Nada solo en S3" to if (s.configured) "Todo lo que hay en la lista de S3 también está en este dispositivo (según la última lista descargada)." else "Configura Amazon S3 para ver qué hay en tu bucket."
        SyncFilter.RESTORING -> "Sin restauraciones" to "Las fotos que restaures desde la pestaña S3 aparecerán aquí mientras se descargan."
    }
    Column(Modifier.fillMaxWidth().padding(vertical = Space.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ------------------------------------------------------------------ rows

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocalRowItem(
    item: MediaItem,
    vm: SyncCenterViewModel,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onFix: (FailureFix) -> Unit,
) {
    val advice = if (item.syncState == SyncState.FAILED) FailureAdviser.of(item.lastError, item.lastErrorPermanent, item.attempts) else null
    val actions = SyncPresentation.actionsFor(item)
    RowSurface(expanded, onToggle, "${item.displayName}, ${com.s3vault.app.presentation.syncStateLabel(item.syncState)}") {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.Top) {
            AsyncImage(
                model = item.uri, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(52.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(item.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                    SyncPill(item.syncState)
                    Text(formatBytes(item.sizeBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (item.syncState.isInFlight) {
                    val progress by remember(item.mediaStoreId) { vm.uploadProgress(item.mediaStoreId) }.collectAsStateWithLifecycle(null)
                    SlimProgress(progress?.fraction)
                    Text(
                        if (item.syncState == SyncState.VERIFYING) "Verificando en S3: aún no cuenta como respaldada"
                        else progress?.let { "${formatBytes(it.doneBytes)} de ${formatBytes(it.totalBytes)}" } ?: "Subiendo",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Locations(item)
                if (advice != null) {
                    Text(advice.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = if (expanded) 6 else 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            Column(Modifier.padding(top = Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                if (advice != null) {
                    Text(advice.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    item.lastError?.let { Text(it, style = MaterialTheme.technical, color = MaterialTheme.colorScheme.error) }
                    if (item.attempts > 0) Text("Intentos: ${item.attempts}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    if (RowAction.RETRY in actions) SecondaryButton("Reintentar", { vm.retry(item.mediaStoreId) }, Modifier.weight(1f), enabled = advice?.action != FailureFix.NOTHING, icon = Icons.Filled.Replay)
                    if (RowAction.BACK_UP in actions) SecondaryButton("Respaldar", { vm.backUp(item.mediaStoreId) }, Modifier.weight(1f), icon = Icons.Filled.CloudUpload)
                    if (RowAction.CANCEL in actions) SecondaryButton("Cancelar subida", { vm.cancel(item.mediaStoreId) }, Modifier.weight(1f))
                    if (RowAction.OPEN in actions) SecondaryButton("Ver foto", onOpen, Modifier.weight(1f), icon = Icons.Filled.Image)
                }
                if (advice != null && (advice.action == FailureFix.CREDENTIALS || advice.action == FailureFix.PERMISSIONS || advice.action == FailureFix.S3_SETTINGS)) {
                    QuietButton(
                        if (advice.action == FailureFix.CREDENTIALS) "Ir a credenciales" else "Ir a los ajustes de S3",
                        { onFix(advice.action) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CloudRowItem(obj: RemoteObject, vm: SyncCenterViewModel, expanded: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    val restoring = obj.state == SyncState.RESTORING
    RowSurface(expanded, onToggle, "${obj.displayName}, ${if (restoring) "restaurando" else "solo en S3"}") {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.Top) {
            // No S3 request just to draw a list: the preview is loaded when the user opens the photo.
            Box(Modifier.size(52.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(obj.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                    SyncPill(obj.state)
                    Text(formatBytes(obj.sizeBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (restoring) {
                    val progress by remember(obj.key) { vm.downloadProgress(obj.key) }.collectAsStateWithLifecycle(null)
                    SlimProgress(progress?.fraction)
                    Text(
                        progress?.let { "${formatBytes(it.doneBytes)} de ${formatBytes(it.totalBytes)}" } ?: "En espera de descarga",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LocationLine(device = false, deviceText = "No está en este dispositivo", cloudStatus = CloudStatus.LISTED, cloudText = "En S3 según la lista guardada")
                obj.lastError?.let {
                    Text("Falló la restauración: " + it.substringBefore(" ["), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = if (expanded) 6 else 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            Row(Modifier.padding(top = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                if (restoring) SecondaryButton("Cancelar restauración", { vm.cancelRestore(listOf(obj.key)) }, Modifier.weight(1f))
                else SecondaryButton(if (obj.lastError != null) "Reintentar" else "Restaurar", { vm.restore(listOf(obj.key)) }, Modifier.weight(1f), icon = Icons.Filled.Replay)
                SecondaryButton("Ver foto", onOpen, Modifier.weight(1f), icon = Icons.Filled.Image)
            }
        }
    }
}

@Composable
private fun RowSurface(expanded: Boolean, onToggle: () -> Unit, description: String, content: @Composable () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().animateContentSize(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Ocultar detalles" else "Ver detalles", onClick = onToggle)
                .heightIn(min = Space.touch)
                .padding(Space.m)
                .semantics { contentDescription = description; stateDescription = if (expanded) "Expandido" else "Contraído" },
        ) { content() }
    }
}

/** Where the file is, said separately for the device (phone icon) and for Amazon S3 (cloud icon). */
@Composable
private fun Locations(item: MediaItem) {
    val status = SyncPresentation.cloudStatus(item.syncState)
    val cloud = when (status) {
        CloudStatus.CONFIRMED -> "S3: confirmada" + (item.lastBackupAtMs?.let { " · ${formatAge(it)}" } ?: "")
        CloudStatus.VERIFYING -> "S3: verificando, sin confirmar"
        CloudStatus.NOT_YET -> "S3: todavía no está"
        CloudStatus.NONE -> "S3: sin copia"
        CloudStatus.FAILED -> "S3: sin confirmar por el error"
        CloudStatus.LISTED -> "S3: en la lista guardada"
    }
    LocationLine(device = item.present, deviceText = if (item.present) "En el dispositivo" else "Ya no está aquí", cloudStatus = status, cloudText = cloud)
}

@Composable
private fun LocationLine(device: Boolean, deviceText: String, cloudStatus: CloudStatus, cloudText: String) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val (cloudIcon, cloudTint) = when (cloudStatus) {
        CloudStatus.CONFIRMED -> Icons.Filled.CloudDone to MaterialTheme.status.success
        CloudStatus.VERIFYING, CloudStatus.NOT_YET -> Icons.Filled.CloudQueue to MaterialTheme.colorScheme.primary
        CloudStatus.FAILED -> Icons.Filled.ErrorOutline to MaterialTheme.colorScheme.error
        CloudStatus.NONE -> Icons.Filled.CloudOff to muted
        CloudStatus.LISTED -> Icons.Filled.Cloud to muted
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.PhoneAndroid, null, Modifier.size(14.dp), tint = if (device) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            Text(deviceText, style = MaterialTheme.typography.labelMedium, color = muted, maxLines = 1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
            Icon(cloudIcon, null, Modifier.size(14.dp), tint = cloudTint)
            Text(cloudText, style = MaterialTheme.typography.labelMedium, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
