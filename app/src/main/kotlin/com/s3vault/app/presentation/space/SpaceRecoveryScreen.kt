package com.s3vault.app.presentation.space

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.s3vault.app.data.local.MediaItemEntity
import com.s3vault.app.data.local.SpaceBucketRow
import com.s3vault.app.data.local.SpaceGroupRow
import com.s3vault.app.domain.space.SafetyRules
import com.s3vault.app.domain.space.SpaceBucket
import com.s3vault.app.domain.space.SpaceGrouping
import com.s3vault.app.presentation.delete.DeleteHost
import com.s3vault.app.presentation.delete.DeleteViewModel
import com.s3vault.app.presentation.formatBytes
import com.s3vault.app.presentation.formatCount
import com.s3vault.app.presentation.formatDay
import com.s3vault.app.presentation.settings.formatMonthKey
import com.s3vault.app.presentation.settings.formatRequests
import com.s3vault.app.presentation.settings.formatUsdBrief
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.presentation.theme.status
import com.s3vault.app.presentation.ui.AppTopBar
import com.s3vault.app.presentation.ui.BackButton
import com.s3vault.app.presentation.ui.ChoiceChip
import com.s3vault.app.presentation.ui.EmptyState
import com.s3vault.app.presentation.ui.PrimaryButton
import com.s3vault.app.presentation.ui.QuietButton
import com.s3vault.app.presentation.ui.SecondaryButton
import com.s3vault.app.presentation.ui.SlimProgress
import com.s3vault.app.presentation.ui.StatusBanner
import com.s3vault.app.presentation.ui.Tone
import com.s3vault.app.sync.MediaAccess

private fun requiredPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

/**
 * Free space on this device without risking the backup. The list only contains photos whose copy in S3 was confirmed; every
 * other state is explained instead of hidden. Deleting always goes through a last check against S3 and Android's own dialog,
 * and only the local copy is ever touched. Liberating space on the phone does not reduce the AWS bill, and the screen says so.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SpaceRecoveryScreen(
    vm: SpaceRecoveryViewModel,
    deleteVm: DeleteViewModel,
    onBack: () -> Unit,
    onOpenIntegrity: () -> Unit,
    onOpenCredentials: () -> Unit,
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val check by vm.checkState.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var rulesSheet by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refreshAccess() }

    BackHandler(enabled = selecting) { vm.clearSelection() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }
    LaunchedEffect(vm) { vm.refreshAccess() }
    // The check ended well: Android's own confirmation takes over.
    LaunchedEffect(vm, deleteVm) { vm.deviceDeletions.collect { deleteVm.requestDeviceOnly(it.entries, it.note) } }
    LaunchedEffect(listState, rows.size) {
        snapshotFlow { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) to listState.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (rows.isNotEmpty() && rows.size % 80 == 0 && total > 0 && last >= total - 8) vm.loadMore() }
    }
    val groupOf = remember(s.grouping) { { m: MediaItemEntity -> groupKey(m, s.grouping) } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selecting) {
                AppTopBar(
                    title = if (selected.size == 1) "1 seleccionado" else "${selected.size} seleccionados",
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    navigation = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.Close, "Cancelar selección") } },
                    actions = { IconButton(onClick = vm::selectAllInBucket) { Icon(Icons.Filled.SelectAll, "Seleccionar todo lo de esta lista") } },
                )
            } else {
                AppTopBar(
                    "Liberar espacio", subtitle = "Solo con copia confirmada en S3", navigation = { BackButton(onBack) },
                    actions = { IconButton(onClick = { rulesSheet = true }) { Icon(Icons.Filled.Tune, "Reglas de seguridad") } },
                )
            }
        },
        bottomBar = {
            if (selecting && check !is CheckState.Running) {
                val bytes = rows.filter { it.mediaStoreId in selected }.sumOf { it.sizeBytes }
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp) {
                    Column(Modifier.navigationBarsPadding().padding(horizontal = Space.screen, vertical = Space.s)) {
                        PrimaryButton(
                            "Revisar y liberar ${if (selected.size == 1) "1 elemento" else "${formatCount(selected.size)} elementos"}",
                            vm::review, Modifier.fillMaxWidth(), icon = Icons.Filled.DeleteSweep,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            when {
                s.access == MediaAccess.NONE -> EmptyState(
                    icon = Icons.Filled.PhoneAndroid, title = "Sin acceso a tus fotos",
                    body = "Para saber qué hay en este dispositivo hace falta el permiso de fotos y vídeos. Sin él no se puede ofrecer nada, y no se borra nada.",
                ) { PrimaryButton("Conceder acceso", { permission.launch(requiredPermissions()) }, Modifier.fillMaxWidth()) }
                else -> LazyColumn(
                    Modifier.widthIn(max = Space.maxContent).fillMaxSize(), state = listState,
                    contentPadding = PaddingValues(horizontal = Space.screen, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    item(key = "cost") {
                        StatusBanner(
                            Tone.Info, "Esto libera espacio del móvil, no reduce tu factura de AWS",
                            "Las copias en S3 se conservan y siguen cobrándose hasta que las borres o las muevas a otra clase de almacenamiento.",
                        )
                    }
                    if (s.access == MediaAccess.PARTIAL) item(key = "partial") {
                        StatusBanner(Tone.Warning, "Acceso parcial", "Solo se ven las fotos que elegiste para la app; el resto no se ofrece.", "Gestionar", onAction = { permission.launch(requiredPermissions()) })
                    }
                    item(key = "hero") { Hero(s) }
                    item(key = "buckets") { Buckets(s, vm::chooseBucket, onOpenIntegrity, vm::verifyUnverified, check, onRules = { rulesSheet = true }) }
                    if (s.bucket.isCandidate && groups.isNotEmpty()) item(key = "grouping") {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                            Text("Agrupar por", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SpaceGrouping.entries.forEach { g -> ChoiceChip(g == s.grouping, g.label, { vm.chooseGrouping(g) }) }
                        }
                    }
                    if (!s.bucket.isCandidate) {
                        item(key = "notOffered") { NotOffered(s.bucket) }
                    } else if (rows.isEmpty() && s.loaded) {
                        item(key = "empty") { Empty(s) }
                    }
                    var last: String? = null
                    rows.forEach { m ->
                        val key = groupOf(m)
                        if (key != last) {
                            last = key
                            val info = groups.firstOrNull { it.gkey == key }
                            stickyHeaderLike(key, info, s.grouping, rows, selected, vm::toggleGroup)
                        }
                        item(key = "m-${m.mediaStoreId}", contentType = "row") {
                            CandidateRow(m, s.bucket, m.mediaStoreId in selected) { vm.toggle(m.mediaStoreId) }
                        }
                    }
                }
            }
            if (check is CheckState.Running) Progress(check as CheckState.Running, vm::cancelCheck, Modifier.align(Alignment.BottomCenter))
        }
    }

    DeleteHost(deleteVm, snackbar, onStarted = vm::clearSelection, onOpenCredentials = onOpenCredentials)
    when (val c = check) {
        is CheckState.Review -> ReviewDialog(c, vm::confirmCheck, vm::dismissCheck)
        is CheckState.Blocked -> BlockedDialog(c, vm::dismissCheck, onCredentials = { vm.dismissCheck(); onOpenCredentials() })
        else -> Unit
    }
    if (rulesSheet) RulesSheet(s.rules, vm.folders.collectAsStateWithLifecycle().value.map { it.name }, { vm.saveRules(it); rulesSheet = false }, { rulesSheet = false })
}

private fun groupKey(m: MediaItemEntity, g: SpaceGrouping): String = when (g) {
    SpaceGrouping.MONTH -> (m.dayKey / 100).toString()
    SpaceGrouping.YEAR -> (m.dayKey / 10000).toString()
    SpaceGrouping.FOLDER -> m.folderName
}

private fun groupTitle(key: String, g: SpaceGrouping): String = when (g) {
    SpaceGrouping.MONTH -> key.toIntOrNull()?.let { formatMonthKey(it).replaceFirstChar { c -> c.uppercase() } } ?: "Sin fecha"
    SpaceGrouping.YEAR -> if (key == "0") "Sin fecha" else key
    SpaceGrouping.FOLDER -> key.ifBlank { "Sin carpeta" }
}

// A section header is a plain row of the list: the headers carry a control that must stay tappable and testable.
private fun androidx.compose.foundation.lazy.LazyListScope.stickyHeaderLike(
    key: String, info: SpaceGroupRow?, g: SpaceGrouping, rows: List<MediaItemEntity>, selected: Set<Long>, onToggle: (String) -> Unit,
) {
    item(key = "g-$key", contentType = "header") {
        val shown = rows.filter { groupKey(it, g) == key }
        val all = shown.isNotEmpty() && shown.all { it.mediaStoreId in selected }
        Row(
            Modifier.fillMaxWidth().heightIn(min = Space.touch).toggleable(value = all, role = Role.Checkbox, onValueChange = { onToggle(key) }).padding(top = Space.m),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Checkbox(checked = all, onCheckedChange = null)
            Column(Modifier.weight(1f)) {
                Text(groupTitle(key, g), style = MaterialTheme.typography.titleSmall)
                if (info != null) Text("${formatCount(info.n)} · ${formatBytes(info.bytes)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ------------------------------------------------------------------ pieces

@Composable
private fun Hero(s: SpaceState) {
    val n = s.n(SpaceBucket.VERIFIED)
    Column(Modifier.padding(horizontal = Space.xs, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        if (n > 0) {
            Text("≈ ${formatBytes(s.bytes(SpaceBucket.VERIFIED))}", style = MaterialTheme.typography.displaySmall)
            Text(
                "se pueden liberar quitando ${if (n == 1) "1 elemento" else "${formatCount(n)} elementos"} de este dispositivo",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Estimación con el tamaño de los archivos; el espacio real puede variar un poco (miniaturas, papelera de Android).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text("Nada recomendable todavía", style = MaterialTheme.typography.titleMedium)
            Text(
                if (s.loaded && s.buckets.isEmpty()) "Cuando respaldes fotos y S3 confirme que las tiene, aparecerán aquí."
                else "Las fotos respaldadas necesitan una confirmación reciente de S3, y tus reglas pueden estar reteniendo otras. Mira el desglose.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Buckets(
    s: SpaceState, onChoose: (SpaceBucket) -> Unit, onOpenIntegrity: () -> Unit, onVerify: () -> Unit, check: CheckState, onRules: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            listOf(SpaceBucket.VERIFIED, SpaceBucket.UNVERIFIED, SpaceBucket.MISSING, SpaceBucket.OUTDATED).forEach { b ->
                ChoiceChip(s.bucket == b, "${b.label} · ${formatCount(s.n(b))}", { onChoose(b) })
            }
        }
        val kept = s.excluded.values.sumOf { it.n }
        if (kept > 0) {
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text("Se conservan por tus reglas: ${formatCount(kept)}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        s.excluded.entries.sortedByDescending { it.value.n }.joinToString(" · ") { (b, r) -> "${r.n} ${b.label.lowercase()}" },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    QuietButton("Cambiar reglas", onRules)
                }
            }
        }
        if (s.bucket == SpaceBucket.UNVERIFIED && s.n(SpaceBucket.UNVERIFIED) > 0) {
            val n = s.n(SpaceBucket.UNVERIFIED)
            val cost = com.s3vault.app.domain.integrity.AuditCosts.deep(n, s.region)
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text("$n respaldadas sin confirmación reciente", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Se pueden seleccionar igualmente: se comprobarán en S3 justo antes de borrar. O confírmalas ahora para ver cuánto espacio dan: " +
                            "${formatRequests(cost.requests, "HEAD")}${cost.usd?.let { " (≈ ${formatUsdBrief(it)})" } ?: ""}, sin descargar fotos.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SecondaryButton("Confirmar en S3 ahora", onVerify, Modifier.fillMaxWidth(), enabled = s.configured && check !is CheckState.Running, icon = Icons.Filled.CloudDone)
                }
            }
        }
    }
}

@Composable
private fun NotOffered(b: SpaceBucket) {
    val (title, body) = when (b) {
        SpaceBucket.MISSING -> "No se ofrecen" to "Una auditoría no encontró estos objetos en S3 (o el registro está obsoleto). Quitar la copia del móvil podría perder la foto. Revisa el resultado en la auditoría del respaldo."
        else -> "No se ofrecen" to "El archivo o el objeto de S3 cambió desde el respaldo: la copia de S3 ya no es este archivo. Vuelve a respaldarla o audítala."
    }
    Column(Modifier.padding(vertical = Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Empty(s: SpaceState) {
    Text(
        if (s.bucket == SpaceBucket.VERIFIED) "Ninguna foto cumple todas las condiciones ahora mismo." else "No hay fotos en esta lista.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Space.l),
    )
}

@Composable
private fun CandidateRow(m: MediaItemEntity, bucket: SpaceBucket, selected: Boolean, onToggle: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { stateDescription = if (selected) "Seleccionado" else "No seleccionado" },
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.padding(horizontal = Space.s, vertical = Space.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.padding(Space.s))
            AsyncImage(
                model = m.uri, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
            Column(Modifier.weight(1f).padding(vertical = Space.xs), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(m.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${formatDay(m.dateTakenMs)} · ${formatBytes(m.sizeBytes)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CloudDone, null, Modifier.size(14.dp), tint = if (bucket == SpaceBucket.VERIFIED) MaterialTheme.status.success else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (bucket == SpaceBucket.VERIFIED) "S3: confirmada" else "S3: respaldada, sin confirmación reciente",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Progress(c: CheckState.Running, onCancel: () -> Unit, modifier: Modifier) {
    Surface(modifier.padding(Space.screen).fillMaxWidth().widthIn(max = Space.maxContent), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp) {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Comprobando en S3…", style = MaterialTheme.typography.titleSmall)
                    Text("${c.done} de ${c.total}. No se ha borrado nada.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                QuietButton("Cancelar", onCancel)
            }
            SlimProgress(if (c.total > 0 && c.done > 0) c.done.toFloat() / c.total else null)
        }
    }
}

@Composable
private fun ReviewDialog(r: CheckState.Review, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, shape = MaterialTheme.shapes.extraLarge, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        icon = { Icon(Icons.Filled.PhoneAndroid, null) },
        title = { Text("Revisar el lote", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text("${if (r.count == 1) "1 elemento" else "${formatCount(r.count)} elementos"} · ${formatBytes(r.bytes)}", style = MaterialTheme.typography.titleSmall)
                Bullet("Antes de nada se comprueba cada uno en S3 con una solicitud HEAD (${formatRequests(r.cost.requests, "HEAD")}${r.cost.usd?.let { ", ≈ ${formatUsdBrief(it)}" } ?: ""}). No se descarga ninguna foto.")
                Bullet("Solo pasan a Android los que S3 confirme con su SHA-256. Si no hay conexión o algo no cuadra, ese elemento se queda.")
                Bullet("Android te pedirá confirmar el borrado. Después podrás restaurarlos desde la pestaña S3.")
                Bullet("Solo se borra la copia de este móvil. Tus objetos en S3 no se tocan.")
            }
        },
        confirmButton = { QuietButton("Comprobar y continuar", onConfirm) },
        dismissButton = { QuietButton("Cancelar", onDismiss) },
    )
}

@Composable
private fun Bullet(text: String) = Text("• $text", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun BlockedDialog(b: CheckState.Blocked, onClose: () -> Unit, onCredentials: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose, shape = MaterialTheme.shapes.extraLarge, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("No se eliminó nada", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(
                    if (b.batch.credentialsNeeded) "Las credenciales de S3 han caducado: sin poder comprobar la copia, no se borra nada."
                    else "Ninguno de los ${b.requested} elementos pasó la comprobación en S3, así que se quedan en tu móvil:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!b.batch.credentialsNeeded) b.batch.dropped.forEach { (r, n) -> Bullet("$n · ${r.label}") }
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                if (b.batch.credentialsNeeded) QuietButton("Ir a credenciales", onCredentials)
                QuietButton("Entendido", onClose)
            }
        },
    )
}

/** The safety rules. Every control only makes the list shorter; the defaults are the cautious ones. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RulesSheet(initial: SafetyRules, folders: List<String>, onSave: (SafetyRules) -> Unit, onDismiss: () -> Unit) {
    var r by remember { mutableStateOf(initial) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen).padding(bottom = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Text("Reglas de seguridad", style = MaterialTheme.typography.titleLarge)
            Text("Estas reglas solo hacen la lista más corta. Nunca añaden fotos.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            RuleSwitch("Conservar las favoritas", r.excludeFavorites) { r = r.copy(excludeFavorites = it) }
            RuleSwitch("Incluir vídeos", r.includeVideos) { r = r.copy(includeVideos = it) }
            RuleSwitch("Conservar las fotos de mis álbumes", r.keepAlbumPhotos) { r = r.copy(keepAlbumPhotos = it) }
            DaysRule("Conservar las de los últimos días", r.keepRecentDays, listOf(0, 7, 30, 90, 365)) { r = r.copy(keepRecentDays = it) }
            DaysRule("Respaldadas desde hace al menos", r.minBackedUpDays, listOf(0, 3, 7, 30, 90)) { r = r.copy(minBackedUpDays = it) }
            Text("Confianza en una comprobación de S3", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                listOf(24 to "1 día", 168 to "7 días", 720 to "30 días").forEach { (h, label) -> ChoiceChip(r.verifiedWithinHours == h, label, { r = r.copy(verifiedWithinHours = h) }) }
            }
            if (folders.isNotEmpty()) {
                Text("Carpetas que nunca se ofrecen", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    (folders + r.excludedFolders).distinct().sorted().forEach { f ->
                        ChoiceChip(f in r.excludedFolders, f, { r = r.copy(excludedFolders = if (f in r.excludedFolders) r.excludedFolders - f else r.excludedFolders + f) })
                    }
                }
            }
            PrimaryButton("Guardar reglas", { onSave(r) }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun RuleSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.touch).toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun DaysRule(label: String, value: Int, options: List<Int>, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            (options + value).distinct().sorted().forEach { d -> ChoiceChip(value == d, if (d == 0) "Sin límite" else "$d días", { onChange(d) }) }
        }
    }
}
