package com.s3vault.app.presentation.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.s3vault.app.auth.AuthStatus
import com.s3vault.app.domain.cost.Basis
import com.s3vault.app.domain.cost.Blocker
import com.s3vault.app.domain.cost.ClassCost
import com.s3vault.app.domain.cost.ClassNoteKind
import com.s3vault.app.domain.cost.CostEstimate
import com.s3vault.app.domain.cost.CostEstimator
import com.s3vault.app.domain.cost.Confidence
import com.s3vault.app.domain.cost.ReasonKind
import com.s3vault.app.domain.cost.S3Pricing
import com.s3vault.app.domain.cost.Unpriced
import com.s3vault.app.presentation.formatBytes
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.presentation.theme.status
import com.s3vault.app.presentation.ui.PrimaryButton
import com.s3vault.app.presentation.ui.QuietButton
import com.s3vault.app.presentation.ui.SectionHeader
import com.s3vault.app.presentation.ui.SettingsGroup
import com.s3vault.app.presentation.ui.SlimProgress
import com.s3vault.app.presentation.ui.StatusBanner
import com.s3vault.app.presentation.ui.Tone
import java.time.Instant
import java.time.ZoneOffset

private const val BILLING_URL = "https://console.aws.amazon.com/billing/home"

/** One-line summary for the Settings home row; null when there is nothing worth saying yet. */
fun StorageCostUiState.summaryLine(): String? {
    val inv = inventory ?: return null
    val est = estimate
    val usage = "${formatCount(inv.totalObjects)} objetos · ${formatBytes(inv.totalBytes)}"
    return if (est?.monthlyUsd != null) "≈ ${formatUsd(est.monthlyUsd)} al mes · $usage" else usage
}

@Composable
fun StorageCostScreen(
    vm: StorageCostViewModel,
    onBack: () -> Unit,
    onOpenS3Settings: () -> Unit,
    onOpenSecurity: () -> Unit,
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var help by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.tick() }

    SettingsScaffold("Almacenamiento y costes", onBack) {
        StatusBanner(
            Tone.Info, "Es una estimación, no tu factura",
            "Se calcula con lo que S3 informa de tu bucket y con precios de lista. El importe real depende de más cosas y solo lo " +
                "muestran AWS Billing y Cost Explorer.",
            actionLabel = "Abrir AWS Billing", onAction = { openUrl(ctx, BILLING_URL) },
        )
        val inv = s.inventory
        val est = s.estimate
        when {
            !s.loaded -> SlimProgress(null)
            s.config == null -> Placeholder(
                Icons.Filled.CloudOff, "Amazon S3 no está configurado",
                "Configura el bucket y las credenciales para contar lo que tienes guardado y estimar su coste mensual.",
            ) { PrimaryButton("Configurar S3", onOpenS3Settings, Modifier.fillMaxWidth()) }
            inv == null || est == null -> NoInventory(s, vm, onOpenSecurity)
            else -> Dashboard(s, inv, est, vm, onOpenSecurity, onHelp = { help = true }, onPricing = { openUrl(ctx, S3Pricing.SOURCE_URL) })
        }
    }
    if (help) HelpSheet(onDismiss = { help = false })
}

// ------------------------------------------------------------------ no inventory yet

@Composable
private fun NoInventory(s: StorageCostUiState, vm: StorageCostViewModel, onOpenSecurity: () -> Unit) {
    RefreshBanners(s, vm, onOpenSecurity)
    Placeholder(
        Icons.Filled.Calculate,
        if (s.inventoryIsOfAnotherBucket) "El inventario guardado es de otro bucket" else "Todavía no hay inventario",
        "Para estimar el coste hay que contar lo que hay en tu bucket. Se hace con un listado de ${s.config?.prefix.orEmpty()}photos/ " +
            "(ListObjectsV2): una solicitud LIST por cada 1.000 objetos, normalmente una fracción de céntimo. No hace falta ningún permiso " +
            "nuevo y no se repite sola: solo cuando pulsas el botón (o cuando haces un respaldo o actualizas la pestaña S3, que ya listan).",
    ) {
        PrimaryButton(
            "Calcular ahora", vm::refresh, Modifier.fillMaxWidth(), icon = Icons.Filled.Refresh, enabled = s.canRefresh, loading = s.refreshing,
        )
        if (s.refreshing) SlimProgress(null)
    }
}

@Composable
private fun Placeholder(icon: ImageVector, title: String, body: String, actions: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(Space.xl), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            actions()
        }
    }
}

// ------------------------------------------------------------------ dashboard

@Composable
private fun Dashboard(
    s: StorageCostUiState,
    inv: com.s3vault.app.domain.cost.StorageInventory,
    est: CostEstimate,
    vm: StorageCostViewModel,
    onOpenSecurity: () -> Unit,
    onHelp: () -> Unit,
    onPricing: () -> Unit,
) {
    RefreshBanners(s, vm, onOpenSecurity)
    EstimateBanners(s, inv, est, vm, onPricing)
    Hero(est, onHelp)

    // ---- refresh
    SettingsGroup {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            FactRow("Inventario", "${formatAge(s.nowMs - inv.takenAtMs)} · ${formatDateTimeShort(inv.takenAtMs)}")
            FactRow("Precios de lista", "${formatDateOnly(S3Pricing.published)} (${if (est.priceAgeDays == 1L) "hace 1 día" else "hace ${est.priceAgeDays} días"})")
            FactRow("Región", inv.region.ifBlank { "—" })
            PrimaryButton(
                "Actualizar inventario", vm::refresh, Modifier.fillMaxWidth().padding(top = Space.xs),
                enabled = s.canRefresh, loading = s.refreshing, icon = Icons.Filled.Refresh,
            )
            if (s.refreshing) SlimProgress(null)
            Text(
                "Cuesta ≈ ${formatRequests(est.listRequests, "LIST")}" + (est.listUsd?.let { " (≈ ${formatUsd(it)})" } ?: "") +
                    ". Es el mismo listado que usa la pestaña S3; no hay consultas en segundo plano.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // ---- measured usage
    SectionHeader("Uso medido")
    SettingsGroup {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            MetricRow("Objetos", formatCount(inv.totalObjects), Basis.MEASURED)
            MetricRow("Datos almacenados", formatBytes(inv.totalBytes), Basis.MEASURED)
            MetricRow("Ámbito", "${inv.prefix}photos/", null)
            if (inv.totalObjects == 0L) {
                Text(
                    "No hay nada bajo ${inv.prefix}photos/: el almacenamiento medido es 0.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (inv.unrecognizedObjects > 0) {
                Text(
                    "${formatCount(inv.unrecognizedObjects)} objetos (${formatBytes(inv.unrecognizedBytes)}) no son fotos respaldadas por la app, " +
                        "pero están en ese prefijo y también se facturan; van incluidos.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // ---- breakdown
    SectionHeader("Desglose mensual")
    SettingsGroup {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.l)) {
            if (est.classes.isEmpty()) {
                Text("Sin objetos: sin coste de almacenamiento.", style = MaterialTheme.typography.bodyMedium)
            }
            est.classes.forEach { ClassRow(it) }
            if (est.previewsBytes > 0 && est.previewsUsd != null) {
                CostRow(
                    "Vistas previas", "como máximo ${formatCount(inv.photoObjects)} × ${formatBytes(CostEstimator.ASSUMED_PREVIEW_BYTES)} = ${formatBytes(est.previewsBytes)}",
                    est.previewsUsd, Basis.ASSUMED,
                    note = "Viven en .s3vault/thumbs/ y tu política IAM no permite listarlas, así que no se pueden medir.",
                )
            }
            if (est.monthlyUsd != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Total mensual estimado", style = MaterialTheme.typography.titleSmall)
                    Text("≈ ${formatUsd(est.monthlyUsd)}", style = MaterialTheme.typography.titleMedium)
                }
            } else {
                Text(
                    blockerText(est, inv.region),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // ---- requests of the month
    SectionHeader("Solicitudes: lo que se sabe")
    SettingsGroup {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.l)) {
            if (est.putUsd != null) {
                CostRow(
                    "Subidas (PUT) en ${formatMonthKey(est.putMonthKey)}", "al menos ${formatCount(est.putObjects.toLong())} objetos subidos ese mes",
                    est.putUsd, Basis.ESTIMATED, prefix = "≥ ",
                    note = "Mínimo: sale de la fecha de subida de los objetos que siguen en S3. No cuenta las vistas previas, los objetos ya borrados ni las subidas repetidas.",
                )
                est.listUsd?.let {
                    CostRow("Una actualización del inventario", formatRequests(est.listRequests, "LIST"), it, Basis.MEASURED)
                }
            }
            Text(
                "Estas cifras no están incluidas en el total mensual: son gastos de este mes, no del almacenamiento. " +
                    "No hay forma de saber cuántas solicitudes GET, HEAD o LIST has hecho en total.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // ---- not included
    SectionHeader("Qué no incluye")
    SettingsGroup {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            notIncluded(est).forEach { (title, body) ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    // ---- confidence
    SectionHeader("Confianza y límites")
    SettingsGroup {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            if (est.confidence != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                    ConfidencePill(est.confidence)
                    Text(confidenceSummary(est.confidence), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                est.reasons.forEach { r ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        Icon(Icons.Filled.WarningAmber, null, Modifier.size(18.dp).padding(top = 2.dp), tint = MaterialTheme.status.warning)
                        Text(reasonText(r.kind, r.value, r.value2), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (est.reasons.isEmpty()) {
                    Text(
                        "Inventario reciente, precios vigentes, clases de almacenamiento conocidas y sin cambios pendientes. Aun así sigue siendo una estimación.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                Text("Sin total no hay nivel de confianza: faltan datos para calcularlo (ver arriba).", style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "Precios: ${S3Pricing.SOURCE_NAME}, publicados el ${formatDateOnly(S3Pricing.published)}. Son precios de lista (On-Demand) en dólares: " +
                    "no incluyen descuentos, créditos, impuestos ni la capa gratuita.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                QuietButton("Cómo se calcula", onHelp)
                QuietButton("Ver precios en AWS", onPricing)
            }
        }
    }
}

// ------------------------------------------------------------------ banners

@Composable
private fun RefreshBanners(s: StorageCostUiState, vm: StorageCostViewModel, onOpenSecurity: () -> Unit) {
    if (s.refreshError != null) {
        StatusBanner(
            if (s.refreshNeedsCredentials) Tone.Warning else Tone.Error,
            "No se pudo actualizar el inventario",
            s.refreshError + (s.inventory?.let { " Se conserva el último inventario completo (${formatAge(s.nowMs - it.takenAtMs)})." } ?: ""),
            actionLabel = if (s.refreshNeedsCredentials) "Ir a credenciales" else "Reintentar",
            onAction = if (s.refreshNeedsCredentials) onOpenSecurity else vm::refresh,
        )
    } else if (s.auth is AuthStatus.Missing || s.auth is AuthStatus.Expired) {
        StatusBanner(
            Tone.Warning, if (s.auth is AuthStatus.Missing) "Faltan credenciales" else "Credenciales caducadas",
            "Sin credenciales válidas no se puede actualizar el inventario." + (s.inventory?.let { " Se muestra el último guardado." } ?: ""),
            actionLabel = "Ir a credenciales", onAction = onOpenSecurity,
        )
    } else if (s.lastAttempt?.error != null && s.inventory != null && s.lastAttempt.atMs > s.inventory.takenAtMs) {
        StatusBanner(
            Tone.Warning, "El último intento de actualizar falló",
            s.lastAttempt.error + " Se conserva el inventario de ${formatAge(s.nowMs - s.inventory.takenAtMs)}.",
            actionLabel = "Reintentar", onAction = vm::refresh,
        )
    }
}

@Composable
private fun EstimateBanners(
    s: StorageCostUiState, inv: com.s3vault.app.domain.cost.StorageInventory, est: CostEstimate, vm: StorageCostViewModel, onPricing: () -> Unit,
) {
    when (est.blocker) {
        Blocker.NO_PRICES_FOR_REGION -> StatusBanner(
            Tone.Warning, "Sin precios para la región «${inv.region}»",
            "La tabla de precios no incluye esta región, así que no se calcula ningún importe. Lo medido (objetos y datos) sí es válido. " +
                "Consulta los precios en la página de AWS.",
            actionLabel = "Ver precios en AWS", onAction = onPricing,
        )
        Blocker.UNSUPPORTED_CLASS -> StatusBanner(
            Tone.Warning, "Hay clases de almacenamiento sin precio",
            "No se calcula un total porque sería engañoso: ${est.classes.filter { it.unpriced != null }.joinToString { it.label }}.",
        )
        Blocker.INCOMPLETE_INVENTORY -> StatusBanner(
            Tone.Error, "Inventario incompleto",
            "El listado no terminó, así que no se muestra ningún total. Actualiza el inventario.", actionLabel = "Actualizar", onAction = vm::refresh,
        )
        null -> Unit
    }
    if (est.priceAgeDays > S3Pricing.AGED_AFTER_DAYS) {
        StatusBanner(
            Tone.Warning, "Precios de lista antiguos",
            "La tabla de precios es de ${formatDateOnly(S3Pricing.published)} (hace ${est.priceAgeDays} días) y AWS puede haberlos cambiado. " +
                "Las cifras bajan de confianza; actualiza la app para recibir precios nuevos.",
        )
    }
    if (est.inventoryAgeDays > CostEstimator.INVENTORY_AGED_DAYS) {
        StatusBanner(
            Tone.Warning, "Inventario desactualizado",
            "Se tomó hace ${est.inventoryAgeDays} días; tu bucket pudo cambiar desde entonces.", actionLabel = "Actualizar", onAction = vm::refresh,
        )
    }
    if (s.pending.any) {
        StatusBanner(
            Tone.Info, "Hay cambios desde el inventario",
            buildList {
                if (s.pending.uploads > 0) add("${s.pending.uploads} subida(s)")
                if (s.pending.deletions > 0) add("${s.pending.deletions} borrado(s) en S3")
            }.joinToString(" y ") + " registrados por la app. Actualiza para incluirlos.",
            actionLabel = "Actualizar", onAction = vm::refresh,
        )
    }
}

// ------------------------------------------------------------------ pieces

@Composable
private fun Hero(est: CostEstimate, onHelp: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(Space.xl), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Savings, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Coste mensual estimado", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onHelp) { Icon(Icons.Filled.HelpOutline, "Cómo se calcula esta estimación") }
            }
            if (est.monthlyUsd != null) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text("≈ ${formatUsd(est.monthlyUsd)}", style = MaterialTheme.typography.displaySmall)
                    Text("al mes", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = Space.s))
                }
                est.confidence?.let { ConfidencePill(it) }
                Text(
                    buildString {
                        append("Si el uso actual durara todo el mes.")
                        if (est.storageUsd != null && (est.previewsUsd ?: 0.0) > 0) {
                            append(" Almacenamiento medido: ${formatUsd(est.storageUsd)}; vistas previas (suposición): ${formatUsdBrief(est.previewsUsd!!)}.")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("No disponible", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "No hay datos suficientes para dar un importe sin engañar. Más abajo se explica por qué y se muestra lo que sí se midió.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConfidencePill(c: Confidence) {
    val (container, content, icon, text) = when (c) {
        Confidence.HIGH -> Quad(MaterialTheme.status.successContainer, MaterialTheme.status.onSuccessContainer, Icons.Filled.CheckCircle, "Confianza alta")
        Confidence.MEDIUM -> Quad(MaterialTheme.status.warningContainer, MaterialTheme.status.onWarningContainer, Icons.Filled.WarningAmber, "Confianza media")
        Confidence.LOW -> Quad(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, Icons.Filled.ErrorOutline, "Confianza baja")
    }
    Row(
        Modifier.clip(CircleShape).background(container).padding(horizontal = Space.m, vertical = Space.xs + 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs + 2.dp),
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = content)
        Text(text, style = MaterialTheme.typography.labelMedium, color = content)
    }
}

private data class Quad(val a: Color, val b: Color, val c: ImageVector, val d: String)

/** Medido / Estimado / Suposición: icon + word, never colour alone. */
@Composable
private fun BasisTag(basis: Basis) {
    val (icon, text) = when (basis) {
        Basis.MEASURED -> Icons.Filled.Straighten to "Medido"
        Basis.ESTIMATED -> Icons.Filled.Calculate to "Estimado"
        Basis.ASSUMED -> Icons.Filled.HelpOutline to "Suposición"
    }
    val container = if (basis == Basis.ASSUMED) MaterialTheme.status.warningContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (basis == Basis.ASSUMED) MaterialTheme.status.onWarningContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.clip(CircleShape).background(container).padding(horizontal = Space.s, vertical = 2.dp)
            .semantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, Modifier.size(12.dp), tint = content)
        Text(text, style = MaterialTheme.typography.labelSmall, color = content)
    }
}

@Composable
private fun FactRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = Space.l))
    }
}

@Composable
private fun MetricRow(label: String, value: String, basis: Basis?) {
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (basis != null) BasisTag(basis)
        }
        Text(value, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = Space.l))
    }
}

@Composable
private fun CostRow(title: String, detail: String, usd: Double, basis: Basis, prefix: String = "≈ ", note: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(start = Space.m)) {
                Text(prefix + formatUsd(usd), style = MaterialTheme.typography.titleSmall)
                BasisTag(basis)
            }
        }
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ClassRow(c: ClassCost) {
    val basis = if (c.storageClass == "INTELLIGENT_TIERING") Basis.ESTIMATED else Basis.MEASURED
    val detail = "${formatCount(c.objects)} objetos · ${formatBytes(c.bytes)}" + (c.pricePerGb?.let { " × ${formatUsdPerGb(it)}/GB·mes" } ?: "")
    if (c.monthlyUsd == null) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(c.label, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                when (c.unpriced) {
                    Unpriced.NO_PRICES_FOR_REGION -> "Sin precios para esta región."
                    Unpriced.PRICE_MISSING -> "La lista de precios no tiene esta clase en la región."
                    else -> "Clase sin precio en la fuente: no se estima."
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
        }
        return
    }
    val notes = c.notes.mapNotNull { n ->
        when (n.kind) {
            ClassNoteKind.MIN_SIZE_APPLIED -> "Los objetos de menos de 128 KB se facturan como 128 KB (+${formatBytes(n.value.toLong())})."
            ClassNoteKind.ARCHIVE_OVERHEAD -> "Cada objeto añade 40 KB de metadatos facturables (+${formatBytes(n.value.toLong())})."
            ClassNoteKind.TIER_UNKNOWN -> "No se sabe en qué nivel está cada objeto: se usa el más caro, así que es una cota superior."
            ClassNoteKind.MONITORING_FEE -> if (n.value > 0) "Incluye ${formatUsd(n.value)} de cuota de monitorización." else null
        }
    }
    CostRow(c.label, detail, c.monthlyUsd, basis, note = notes.joinToString(" ").ifBlank { null })
}

// ------------------------------------------------------------------ texts

private fun blockerText(est: CostEstimate, region: String): String = when (est.blocker) {
    Blocker.INCOMPLETE_INVENTORY -> "Sin total: el inventario está incompleto."
    Blocker.NO_PRICES_FOR_REGION -> "Sin total: la tabla de precios no incluye la región «$region»."
    Blocker.UNSUPPORTED_CLASS -> "Sin total: hay clases de almacenamiento que la app no sabe valorar."
    null -> ""
}

private fun confidenceSummary(c: Confidence) = when (c) {
    Confidence.HIGH -> "Los datos son recientes y completos."
    Confidence.MEDIUM -> "Útil como orden de magnitud; mira los motivos."
    Confidence.LOW -> "Tómalo solo como referencia; mira los motivos."
}

private fun reasonText(kind: ReasonKind, a: Long, b: Long): String = when (kind) {
    ReasonKind.PRICES_AGED -> "Los precios de lista tienen $a días y pueden haber cambiado."
    ReasonKind.INVENTORY_AGED -> "El inventario tiene $a días: tu bucket pudo cambiar."
    ReasonKind.PENDING_CHANGES -> "La app registró cambios desde el inventario (${a} subidas, ${b} borrados en S3)."
    ReasonKind.TIER_UNKNOWN -> "Intelligent-Tiering: no se conoce el nivel de cada objeto, el importe es una cota superior."
    ReasonKind.PREVIEWS_SHARE -> "Más del 10 % del importe son vistas previas, que son una suposición."
}

/** Only what applies to this bucket, plus the items that are always true. */
private fun notIncluded(est: CostEstimate): List<Pair<String, String>> = buildList {
    add("Solicitudes GET y HEAD" to "Ver una vista previa, restaurar o comprobar una foto hace solicitudes que no se cuentan aquí (cuestan una fracción de céntimo por cada 1.000).")
    add("Descarga de datos" to "Restaurar o descargar hacia Internet se cobra por GB; los primeros 100 GB al mes de toda tu cuenta AWS son gratuitos.")
    est.retrievalPerGb.forEach { (cls, price) ->
        add("Recuperación en ${CostEstimator.label(cls)}" to "Leer datos de esta clase cuesta ${formatUsdPerGb(price)} por GB recuperado, además de la descarga.")
    }
    val minDays = est.classes.filter { it.objects > 0 }.mapNotNull { c ->
        when (c.storageClass) {
            "STANDARD_IA", "ONEZONE_IA" -> c.label to 30
            "GLACIER_IR", "GLACIER" -> c.label to 90
            else -> null
        }
    }
    if (minDays.isNotEmpty()) {
        add(
            "Duración mínima de almacenamiento" to
                minDays.joinToString("; ") { (n, d) -> "$n: $d días" } +
                ". Borrar o sobrescribir antes cobra el resto del periodo. No se calcula porque el listado no dice cuándo pasó cada objeto a esa clase.",
        )
    }
    add("Versionado" to "Si tu bucket tiene Versioning activado, las versiones anteriores y las marcas de borrado también se facturan y no salen en este inventario. La app no puede saber si está activado sin pedir otro permiso.")
    add("Todo lo demás" to "Otros prefijos del bucket, replicación, cifrado con KMS, S3 Inventory o Storage Lens, impuestos, créditos y descuentos.")
}

// ------------------------------------------------------------------ help

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HelpSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = Space.screen).padding(bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Text("Cómo se calcula", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            HelpBlock(
                "Qué es", "Una aproximación para hacerte una idea del orden de magnitud. No es tu factura: esa la calcula AWS con todo lo que usas " +
                    "(solicitudes, transferencias, descuentos, créditos, impuestos) y solo la ves en AWS Billing y Cost Explorer.",
            )
            HelpBlock(
                "De dónde salen los datos", "De un listado de tu bucket (ListObjectsV2) bajo el prefijo de las fotos. Cada objeto trae su tamaño, su fecha de " +
                    "subida y su clase de almacenamiento. Solo se usa un listado completo: si no termina, no se guarda ni se muestra ningún total.",
            )
            HelpBlock(
                "Cómo se calcula el almacenamiento", "GB = 2³⁰ bytes, como factura AWS. Se multiplica por el precio por GB y mes de la clase en tu región. " +
                    "S3 Standard tiene tramos por volumen; Standard-IA, One Zone-IA y Glacier Instant Retrieval cobran los objetos de menos de 128 KB como 128 KB; " +
                    "Glacier Flexible añade 40 KB por objeto. Se supone que el uso actual dura todo el mes.",
            )
            HelpBlock(
                "Medido, estimado, suposición", "Medido: la cantidad sale de tu bucket y el importe es cantidad × precio de lista. Estimado: sale de una regla con " +
                    "un margen conocido (por ejemplo, un mínimo). Suposición: depende de algo que la app no puede ver, como el tamaño de las vistas previas.",
            )
            HelpBlock(
                "Precios", "Vienen de la lista de precios oficial de AWS (${S3Pricing.SOURCE_NAME}), publicada el ${formatDateOnly(S3Pricing.published)}, " +
                    "guardada en la app por región. La app no consulta ningún servicio de precios ni envía datos de tu bucket a nadie. " +
                    "Si la tabla envejece, se avisa y baja la confianza.",
            )
            HelpBlock(
                "Qué cuesta calcularlo", "Actualizar el inventario hace ≈ 1 solicitud LIST por cada 1.000 objetos (fracciones de céntimo). No hay consultas en " +
                    "segundo plano, no se necesitan permisos nuevos y no se piden objetos uno a uno.",
            )
            QuietButton("Cerrar", onDismiss, Modifier.align(Alignment.End))
        }
    }
}

@Composable
private fun HelpBlock(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // No browser installed: nothing to do.
    }
}
