package com.s3vault.app.presentation.duplicates

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.s3vault.app.data.local.DuplicateKind
import com.s3vault.app.data.local.JobState
import com.s3vault.app.domain.SyncState
import com.s3vault.app.presentation.delete.DeleteHost
import com.s3vault.app.presentation.delete.DeleteViewModel
import com.s3vault.app.presentation.formatAge
import com.s3vault.app.presentation.formatBytes
import com.s3vault.app.presentation.formatCount
import com.s3vault.app.presentation.formatDay
import com.s3vault.app.presentation.theme.PhotoOverlay
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.presentation.theme.status
import com.s3vault.app.presentation.ui.AppTopBar
import com.s3vault.app.presentation.ui.BackButton
import com.s3vault.app.presentation.ui.ChoiceChip
import com.s3vault.app.presentation.ui.PrimaryButton
import com.s3vault.app.presentation.ui.QuietButton
import com.s3vault.app.presentation.ui.SecondaryButton
import com.s3vault.app.presentation.ui.SlimProgress
import com.s3vault.app.presentation.ui.StatusBanner
import com.s3vault.app.presentation.ui.Tone

/**
 * Duplicate and similar pictures, found on this device. Identical files are exact (same SHA-256); similar ones are probable (a
 * visual fingerprint) and always labelled so. Each group suggests which copy to keep and why; the user can pick another. Local and
 * cloud availability are shown separately for every copy, and deleting the others always goes through the confirmation flow.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DuplicatesScreen(vm: DuplicatesViewModel, deleteVm: DeleteViewModel, onBack: () -> Unit, onOpenCredentials: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }
    LaunchedEffect(vm, deleteVm) {
        vm.deletionRequests.collect { r ->
            // S3 deletion goes through the full "which copy?" flow so it is chosen explicitly there; device-only never offers S3.
            if (r.cloud) deleteVm.request(r.entries) else deleteVm.requestDeviceOnly(r.entries, r.note)
        }
    }
    LaunchedEffect(listState, groups.size) {
        snapshotFlow { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) to listState.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (groups.isNotEmpty() && groups.size % 30 == 0 && total > 0 && last >= total - 4) vm.loadMore() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { AppTopBar("Duplicados y parecidas", subtitle = "Se busca en este dispositivo", navigation = { BackButton(onBack) }) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = Space.maxContent).fillMaxSize(), state = listState,
                contentPadding = PaddingValues(horizontal = Space.screen, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                item(key = "scan") { ScanCard(s, vm) }
                item(key = "how") { HowItWorks() }
                stickyHeader(key = "tabs") {
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(vertical = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        ChoiceChip(s.tab == DuplicateKind.EXACT, "Idénticas · ${formatCount(s.exact.groupCount)}", { vm.selectTab(DuplicateKind.EXACT) })
                        ChoiceChip(s.tab == DuplicateKind.SIMILAR, "Parecidas · ${formatCount(s.similar.groupCount)}", { vm.selectTab(DuplicateKind.SIMILAR) })
                    }
                }
                item(key = "tabSummary") { TabSummary(s, vm) }
                if (groups.isEmpty() && s.loaded) item(key = "empty") { Empty(s) }
                items(groups, key = { it.summary.id }, contentType = { "group" }) { g -> GroupCard(g, s.tab, vm) }
            }
        }
    }
    DeleteHost(deleteVm, snackbar, onOpenCredentials = onOpenCredentials)
}

@Composable
private fun ScanCard(s: DuplicatesState, vm: DuplicatesViewModel) {
    val run = s.run
    val running = run?.state == JobState.RUNNING
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(
                when {
                    running -> "Buscando…"
                    run == null && s.pending == 0 -> "Aún no se ha buscado"
                    s.pending > 0 -> "Quedan ${formatCount(s.pending)} archivos por revisar"
                    else -> "Búsqueda al día"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            if (running && run != null && run.totalItems > 0) {
                SlimProgress((run.doneItems.toFloat() / run.totalItems).coerceIn(0f, 1f))
                Text("${run.doneItems} de ${run.totalItems}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            run?.takeIf { !running }?.let {
                Text(
                    "Última búsqueda ${formatAge(it.finishedAtMs ?: it.updatedAtMs)}" + if (it.state == JobState.CANCELLED) " (detenida)" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Todo ocurre en tu móvil: nada se sube. Solo se leen los archivos que comparten tamaño con otro y cada foto nueva una vez; se pausa con batería baja.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (running) SecondaryButton("Detener", vm::cancelScan, Modifier.fillMaxWidth())
            else PrimaryButton(if (s.pending > 0 || run == null) "Buscar duplicados" else "Buscar de nuevo", vm::startScan, Modifier.fillMaxWidth(), icon = Icons.Filled.Search)
        }
    }
}

@Composable
private fun HowItWorks() {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Space.touch).clickable(role = Role.Button, onClickLabel = if (open) "Ocultar" else "Mostrar") { open = !open },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Icon(Icons.Filled.HelpOutline, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Cómo se detecta y qué límites tiene", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) Column(verticalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.padding(bottom = Space.s)) {
            listOf(
                "Idénticas: mismo contenido byte a byte (SHA-256). Es seguro: aunque el nombre o la fecha sean distintos, son el mismo archivo.",
                "Parecidas: una huella visual de 64 bits (pHash) calculada sobre una miniatura. Detecta la misma foto redimensionada, recomprimida, con otro brillo o contraste, ráfagas y copias editadas con poco cambio.",
                "No detecta recortes, giros ni ediciones fuertes, y no entiende el contenido: dos fotos distintas de una escena muy parecida pueden salir juntas. Por eso son «probables» y siempre decides tú.",
                "Dos archivos idénticos en el móvil no tienen por qué ser el mismo objeto en S3: cada copia muestra por separado su estado en el dispositivo y en S3.",
                "Nada se borra solo. Eliminar las otras copias quita solo la del dispositivo; borrar de S3 es una opción aparte y explícita.",
            ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun TabSummary(s: DuplicatesState, vm: DuplicatesViewModel) {
    val t = if (s.tab == DuplicateKind.EXACT) s.exact else s.similar
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs), modifier = Modifier.padding(horizontal = Space.xs)) {
        if (t.groupCount > 0) {
            Text(
                if (s.tab == DuplicateKind.EXACT) "${if (t.groupCount == 1) "1 grupo" else "${formatCount(t.groupCount)} grupos"} · se liberarían ≈ ${formatBytes(t.recoverableBytes)} quitando las copias de más"
                else "${if (t.groupCount == 1) "1 grupo" else "${formatCount(t.groupCount)} grupos"} de fotos probablemente iguales · hasta ≈ ${formatBytes(t.recoverableBytes)} si conservas la mejor de cada una",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (s.tab == DuplicateKind.SIMILAR) {
                StatusBanner(Tone.Warning, "Coincidencias probables", "Compáralas antes de borrar: una huella visual puede juntar fotos que no son la misma.")
            } else {
                QuietButton("Aplicar la sugerencia a todos los grupos…", vm::reviewAllIdentical)
            }
        }
        if (s.ignored > 0) QuietButton(if (s.ignored == 1) "Volver a proponer 1 grupo descartado" else "Volver a proponer ${s.ignored} grupos descartados", vm::restoreIgnored)
    }
}

@Composable
private fun Empty(s: DuplicatesState) {
    val text = when {
        s.run == null && s.pending == 0 -> "Pulsa «Buscar duplicados» para empezar."
        s.tab == DuplicateKind.EXACT -> "No hay archivos idénticos${if (s.pending > 0) " entre los ya revisados" else ""}."
        else -> "No hay fotos parecidas${if (s.pending > 0) " entre las ya analizadas" else ""}."
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Space.xl))
}

@Composable
private fun GroupCard(g: GroupView, tab: DuplicateKind, vm: DuplicatesViewModel) {
    var menu by remember { mutableStateOf(false) }
    val exact = tab == DuplicateKind.EXACT
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(vertical = Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Row(Modifier.padding(horizontal = Space.l), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f).padding(start = Space.s)) {
                    Text(
                        if (exact) "${g.members.size} copias idénticas" else "${g.members.size} fotos parecidas (probable)",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        if (exact) "Mismo contenido · ${formatBytes(g.members.first().item.sizeBytes)} cada una"
                        else "Coincidencia visual aproximada: revísalas",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Más acciones del grupo") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("No son duplicadas") }, onClick = { menu = false; vm.ignore(g) })
                        DropdownMenuItem(text = { Text("Eliminar las otras de S3…") }, onClick = { menu = false; vm.deleteOthersFromCloud(g) })
                    }
                }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = Space.l), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                items(g.members, key = { it.item.mediaStoreId }) { m ->
                    MemberTile(m, keep = m.item.mediaStoreId == g.keepId, exact = exact) { vm.chooseKeeper(g.summary.id, m.item.mediaStoreId) }
                }
            }
            Text(
                "Se conserva la marcada: ${g.suggestion.reason.takeIf { g.keepId == g.suggestion.keepId } ?: "la que has elegido"}.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Space.l),
            )
            SecondaryButton(
                "Eliminar las otras ${g.others.size} del dispositivo (≈ ${formatBytes(g.recoverableBytes)})", { vm.deleteOthersFromDevice(g) },
                Modifier.fillMaxWidth().padding(horizontal = Space.l),
            )
        }
    }
}

/** One copy: the photo first, then what decides which one to keep. Tapping it makes it the one that stays. */
@Composable
private fun MemberTile(m: MemberView, keep: Boolean, exact: Boolean, onKeep: () -> Unit) {
    val item = m.item
    Column(
        Modifier.width(148.dp).selectable(selected = keep, role = Role.RadioButton, onClick = onKeep)
            .semantics { contentDescription = "${item.displayName}, ${if (keep) "se conserva" else "se puede eliminar"}"; stateDescription = if (keep) "Se conserva" else "No se conserva" },
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Box(
            Modifier.size(148.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .then(if (keep) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium) else Modifier),
        ) {
            AsyncImage(model = item.uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (keep) Row(
                Modifier.align(Alignment.TopStart).padding(Space.xs).clip(MaterialTheme.shapes.small).background(PhotoOverlay.strongScrim).padding(horizontal = Space.s, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = PhotoOverlay.successOnScrim)
                Text("Conservar", style = MaterialTheme.typography.labelSmall, color = PhotoOverlay.onScrim)
            }
        }
        Text(item.displayName, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "${item.width}×${item.height} · ${formatBytes(item.sizeBytes)}",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
        )
        Text(
            "${formatDay(item.dateTakenMs)} · ${item.folderName.ifBlank { "—" }}",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (!exact && m.distance > 0) Text("Diferencia visual: ${m.distance}/64", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.PhoneAndroid, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurface)
            Text("En el dispositivo", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        val (icon, tint, text) = when (item.syncState) {
            SyncState.BACKED_UP -> Triple(Icons.Filled.CloudDone, MaterialTheme.status.success, if (m.sharesCloudObject) "S3: mismo objeto que otra copia" else "S3: respaldada")
            SyncState.LOCAL_ONLY -> Triple(Icons.Filled.CloudOff, MaterialTheme.colorScheme.outline, "S3: sin copia")
            SyncState.QUEUED, SyncState.UPLOADING, SyncState.VERIFYING -> Triple(Icons.Filled.Cloud, MaterialTheme.colorScheme.primary, "S3: subiéndose")
            else -> Triple(Icons.Filled.CloudOff, MaterialTheme.colorScheme.error, "S3: sin confirmar")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(14.dp), tint = tint)
            Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

private fun Modifier.width(w: androidx.compose.ui.unit.Dp) = this.then(Modifier.widthIn(min = w, max = w))
