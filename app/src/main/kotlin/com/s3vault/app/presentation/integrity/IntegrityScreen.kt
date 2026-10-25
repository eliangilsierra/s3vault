package com.s3vault.app.presentation.integrity

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.s3vault.app.auth.AuthStatus
import com.s3vault.app.data.local.AuditMode
import com.s3vault.app.data.local.AuditStatus
import com.s3vault.app.data.local.IntegrityRow
import com.s3vault.app.data.local.IntegrityVerdict
import com.s3vault.app.presentation.formatAge
import com.s3vault.app.presentation.formatCount
import com.s3vault.app.presentation.settings.formatRequests
import com.s3vault.app.presentation.settings.formatUsdBrief
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.presentation.theme.status
import com.s3vault.app.presentation.ui.AppTopBar
import com.s3vault.app.presentation.ui.BackButton
import com.s3vault.app.presentation.ui.ChoiceChip
import com.s3vault.app.presentation.ui.ConfirmDialog
import com.s3vault.app.presentation.ui.PrimaryButton
import com.s3vault.app.presentation.ui.QuietButton
import com.s3vault.app.presentation.ui.SecondaryButton
import com.s3vault.app.presentation.ui.SectionHeader
import com.s3vault.app.presentation.ui.SlimProgress
import com.s3vault.app.presentation.ui.StatusBanner
import com.s3vault.app.presentation.ui.Tone
import com.s3vault.app.presentation.ui.accent

/**
 * The backup integrity auditor: does S3 still hold what the device recorded as backed up? Shows what the last audit found, how
 * strong each verdict is, what the next audit would cost in requests, and what cannot be known. Nothing here changes S3 unless
 * the user picks "Volver a subir" and confirms.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IntegrityScreen(vm: IntegrityViewModel, onBack: () -> Unit, onOpenS3Settings: () -> Unit, onOpenSecurity: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val filter by vm.effectiveFilter.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val selecting = selected.isNotEmpty()
    val chosen = rows.filter { it.result.remoteKey in selected }

    BackHandler(enabled = selecting) { vm.clearSelection() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }
    LaunchedEffect(listState, rows.size) {
        snapshotFlow { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) to listState.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (rows.isNotEmpty() && rows.size % 60 == 0 && total > 0 && last >= total - 8) vm.loadMore() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selecting) {
                AppTopBar(
                    title = if (selected.size == 1) "1 seleccionado" else "${selected.size} seleccionados",
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    navigation = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.Close, "Cancelar selección") } },
                    actions = {
                        IconButton(onClick = { vm.recheck(selected.toList()) }, enabled = !s.busy) { Icon(Icons.Filled.Refresh, "Volver a comprobar") }
                        val canReupload = chosen.any { it.present == true }
                        IconButton(onClick = { confirm = Confirm.Reupload }, enabled = canReupload) { Icon(Icons.Filled.CloudUpload, "Volver a subir…") }
                        val canDiscard = chosen.any { it.present != true && (it.result.verdict == IntegrityVerdict.MISSING || it.result.verdict == IntegrityVerdict.STALE_RECORD) }
                        IconButton(onClick = { confirm = Confirm.Discard }, enabled = canDiscard) { Icon(Icons.Filled.DeleteSweep, "Descartar registro…") }
                    },
                )
            } else {
                AppTopBar("Auditoría del respaldo", subtitle = "Comprueba que S3 conserva tus fotos", navigation = { BackButton(onBack) })
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = Space.maxContent).fillMaxSize(), state = listState,
                contentPadding = PaddingValues(horizontal = Space.screen, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                item(key = "banners") { Banners(s, onOpenS3Settings, onOpenSecurity) }
                item(key = "summary") { Summary(s) }
                item(key = "actions") { Actions(s, vm) }
                item(key = "limits") { Limitations() }
                if (s.totalObjects > 0) stickyHeader(key = "filters") { Filters(s, filter, vm::select) }
                if (rows.isEmpty() && s.loaded) item(key = "empty") { Empty(s, filter) }
                items(rows, key = { it.result.remoteKey }, contentType = { "row" }) { row ->
                    ResultRow(row, row.result.remoteKey in selected, selecting) { vm.toggle(row.result.remoteKey) }
                }
            }
        }
    }

    when (confirm) {
        Confirm.Reupload -> {
            val n = chosen.count { it.present == true }
            ConfirmDialog(
                title = "¿Volver a subir ${if (n == 1) "1 foto" else "$n fotos"}?",
                text = "Se sube de nuevo la copia que tienes en este dispositivo y, si el objeto de S3 existe con otro contenido, se sobrescribe con ella. " +
                    "No se borra nada. Cuenta como respaldada solo cuando pase la verificación.",
                confirmLabel = "Volver a subir",
                onConfirm = { vm.reupload(chosen.filter { it.present == true }.map { it.result.remoteKey }); confirm = null },
                onDismiss = { confirm = null },
            )
        }
        Confirm.Discard -> {
            val gone = chosen.filter { it.present != true && (it.result.verdict == IntegrityVerdict.MISSING || it.result.verdict == IntegrityVerdict.STALE_RECORD) }
            ConfirmDialog(
                title = "¿Descartar ${if (gone.size == 1) "1 registro" else "${gone.size} registros"}?",
                text = "La foto ya no está en este dispositivo y el objeto no existe en S3, así que no hay nada que restaurar ni reparar: solo se olvida el registro. S3 no se toca.",
                confirmLabel = "Descartar registro", destructive = true,
                onConfirm = { vm.discard(gone.map { it.result.remoteKey }); confirm = null },
                onDismiss = { confirm = null },
            )
        }
        null -> Unit
    }
}

private enum class Confirm { Reupload, Discard }

// ------------------------------------------------------------------ sections

@Composable
private fun Banners(s: IntegrityState, onOpenS3Settings: () -> Unit, onOpenSecurity: () -> Unit) {
    when {
        !s.configured -> StatusBanner(Tone.Warning, "Amazon S3 no está configurado", "Sin bucket no hay nada que comprobar.", "Configurar S3", onOpenS3Settings)
        s.auth is AuthStatus.Missing -> StatusBanner(Tone.Warning, "Faltan credenciales", "Hacen falta para consultar S3.", "Añadir credenciales", onOpenSecurity)
        s.auth is AuthStatus.Expired -> StatusBanner(Tone.Error, "Credenciales caducadas", "Los resultados guardados se conservan; renueva las credenciales para auditar de nuevo.", "Renovar credenciales", onOpenSecurity)
        else -> Unit
    }
}

@Composable
private fun Summary(s: IntegrityState) {
    val a = s.audit
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            if (a == null) {
                Text("Todavía no se ha auditado", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Una auditoría compara lo que este dispositivo recuerda haber respaldado con lo que S3 tiene ahora. " +
                        "No modifica nada en S3 y te dice cuántas solicitudes hará antes de empezar.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            val (tone, headline, icon) = when {
                a.status == AuditStatus.RUNNING -> Triple(Tone.Info, "Auditando…", Icons.Filled.FactCheck)
                a.status == AuditStatus.FAILED -> Triple(Tone.Warning, "La última auditoría no terminó", Icons.Filled.WarningAmber)
                a.status == AuditStatus.CANCELLED || a.status == AuditStatus.INTERRUPTED -> Triple(Tone.Neutral, "Auditoría detenida", Icons.Filled.FactCheck)
                s.problems > 0 -> Triple(Tone.Error, if (s.problems == 1) "1 objeto con problemas" else "${s.problems} objetos con problemas", Icons.Filled.ErrorOutline)
                else -> Triple(Tone.Success, "Sin problemas conocidos", Icons.Filled.CheckCircle)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(20.dp), tint = tone.accent())
                Text(headline, style = MaterialTheme.typography.titleMedium)
            }
            if (a.status == AuditStatus.RUNNING && a.planned > 0) {
                SlimProgress(a.checked.toFloat() / a.planned)
                Text("${a.checked} de ${a.planned} objetos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                buildString {
                    append(if (a.mode == AuditMode.LISTING) "Auditoría con la lista de S3" else "Verificación profunda")
                    append(" · ").append(formatAge(a.finishedAtMs ?: a.startedAtMs))
                    append(" · ").append(formatRequests(a.requests, if (a.mode == AuditMode.LISTING) "LIST" else "HEAD"))
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            a.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (s.unchecked > 0) {
                Text(
                    if (s.unchecked == 1) "1 objeto aún sin comprobar." else "${formatCount(s.unchecked)} objetos aún sin comprobar.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "«Verificada» por lista significa que existe con el tamaño esperado; solo la verificación profunda compara el SHA-256.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Actions(s: IntegrityState, vm: IntegrityViewModel) {
    val ready = s.configured && s.auth !is AuthStatus.Missing && s.auth !is AuthStatus.Expired
    val listCost = vm.listingCost(s, reuse = false)
    val deepCost = vm.deepCost(s)
    val listing = s.listing
    val reusable = listing != null && listing.complete && System.currentTimeMillis() - listing.atMs < 60 * 60_000L
    // The results are what people come for: once there is an audit the controls fold away, and open again with one tap.
    var open by rememberSaveable(s.loaded) { mutableStateOf(s.audit == null || s.busy) }
    LaunchedEffect(s.busy, s.audit?.id) { if (!s.busy && s.audit != null && s.audit.status != AuditStatus.RUNNING) open = false }
    Surface(Modifier.fillMaxWidth().animateContentSize(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = Space.l, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = Space.touch).clickable(role = Role.Button, onClickLabel = if (open) "Ocultar" else "Mostrar") { open = !open },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Nueva auditoría", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (open || s.busy) {
            if (s.busy) {
                SlimProgress(null)
                SecondaryButton("Cancelar auditoría", vm::cancelAudit, Modifier.fillMaxWidth())
            } else {
                PrimaryButton("Auditar con la lista de S3", { vm.startListing(reuse = false) }, Modifier.fillMaxWidth(), enabled = ready, icon = Icons.Filled.FactCheck)
                CostLine(
                    "${formatRequests(listCost.requests, "LIST")}${listCost.usd?.let { " (≈ ${formatUsdBrief(it)})" } ?: ""}. " +
                        "Detecta objetos que faltan, cambios de tamaño y objetos reemplazados.",
                )
                if (reusable) {
                    SecondaryButton("Usar la lista guardada (${formatAge(listing!!.atMs)}, sin coste)", { vm.startListing(reuse = true) }, Modifier.fillMaxWidth(), enabled = ready)
                    CostLine("Sin solicitudes nuevas, pero compara con lo que S3 tenía ${formatAge(listing.atMs)}.")
                }
                SecondaryButton(
                    if (s.deepPlan > 0) "Verificación profunda (${formatCount(s.deepPlan)} objetos)" else "Verificación profunda",
                    vm::startDeep, Modifier.fillMaxWidth(), enabled = ready && s.deepPlan > 0, icon = Icons.Filled.FactCheck,
                )
                CostLine(
                    if (s.deepPlan > 0) {
                        "${formatRequests(deepCost.requests, "HEAD")}${deepCost.usd?.let { " (≈ ${formatUsdBrief(it)} a precio de S3 Standard)" } ?: ""}. " +
                            "Compara el SHA-256 que S3 guardó al subir cada foto. No descarga fotos. Revisa hasta 200 por vez, empezando por las comprobadas hace más tiempo."
                    } else "No hay objetos pendientes de comprobar a fondo.",
                )
            }
            Schedule(s, vm)
            }
        }
    }
}

@Composable
private fun CostLine(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Schedule(s: IntegrityState, vm: IntegrityViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text("Auditoría periódica (solo lista)", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            listOf(0 to "No", 7 to "Cada 7 días", 14 to "Cada 14 días", 30 to "Cada 30 días").forEach { (d, label) ->
                ChoiceChip(s.auditIntervalDays == d, label, { vm.setInterval(d) })
            }
        }
        CostLine(
            if (s.auditIntervalDays == 0) "Desactivada: la auditoría solo se hace cuando tú la pides."
            else "Con red adecuada y batería no baja. Solo lista (≈ 1 solicitud LIST por cada 1.000 objetos) y nunca modifica nada en S3.",
        )
    }
}

@Composable
private fun Limitations() {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Space.touch).clickable(role = Role.Button, onClickLabel = if (open) "Ocultar" else "Mostrar") { open = !open },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Icon(Icons.Filled.HelpOutline, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Qué puede y qué no puede comprobar", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.padding(bottom = Space.s)) {
                IntegrityText.limitations.forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Filters(s: IntegrityState, selected: AuditFilter, onSelect: (AuditFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).horizontalScroll(rememberScrollState()).padding(vertical = Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        AuditFilter.entries.forEach { f -> ChoiceChip(f == selected, "${f.label} · ${formatCount(s.count(f))}", { onSelect(f) }) }
    }
}

@Composable
private fun Empty(s: IntegrityState, filter: AuditFilter) {
    val text = when {
        s.totalObjects == 0 -> "Cuando audites aparecerán aquí los resultados."
        else -> "Ningún objeto en «${filter.label}»."
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Space.xl))
}

@Composable
private fun ResultRow(row: IntegrityRow, selected: Boolean, selecting: Boolean, onToggle: () -> Unit) {
    val r = row.result
    val name = row.name ?: r.remoteKey.substringAfterLast('/')
    val (tone, icon) = when (r.verdict) {
        IntegrityVerdict.VERIFIED -> Tone.Success to Icons.Filled.CheckCircle
        IntegrityVerdict.MISSING -> Tone.Error to Icons.Filled.ErrorOutline
        IntegrityVerdict.CHANGED -> Tone.Warning to Icons.Filled.WarningAmber
        IntegrityVerdict.UNKNOWN -> Tone.Neutral to Icons.Filled.HelpOutline
        IntegrityVerdict.STALE_RECORD -> Tone.Warning to Icons.Filled.WarningAmber
    }
    Surface(
        Modifier.fillMaxWidth().toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { stateDescription = if (selected) "Seleccionado" else "No seleccionado" },
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.padding(horizontal = Space.s, vertical = Space.s), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.padding(Space.m))
            Column(Modifier.weight(1f).padding(vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, Modifier.size(16.dp), tint = tone.accent())
                    Text(IntegrityText.verdict(r.verdict), style = MaterialTheme.typography.labelLarge, color = tone.accent())
                    Text("· ${IntegrityText.level(r.level)} · ${formatAge(r.checkedAtMs)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(IntegrityText.reason(r), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    when (row.present) { true -> "Copia en este dispositivo: sí"; else -> "Copia en este dispositivo: no" },
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
