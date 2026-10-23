package com.s3vault.app.presentation.library

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.s3vault.app.data.local.FolderCount
import com.s3vault.app.domain.MediaKind
import com.s3vault.app.domain.library.BackupFilter
import com.s3vault.app.domain.library.CloudAvailability
import com.s3vault.app.domain.library.DateKeys
import com.s3vault.app.domain.library.DateRange
import com.s3vault.app.domain.library.LibraryFilters
import com.s3vault.app.domain.library.LibraryScope
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.presentation.ui.PrimaryButton
import com.s3vault.app.presentation.ui.QuietButton
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** What the filter sheet can offer for one gallery: only things its metadata answers reliably. */
data class FilterSheetConfig(
    val scope: LibraryScope,
    val folders: List<FolderCount> = emptyList(),
    /** Local: photos whose date is only the file's. Cloud: objects without a capture date. */
    val undatedCount: Int? = null,
)

private fun fmt(n: Int) = "%,d".format(java.util.Locale.forLanguageTag("es"), n)

private fun dayKeyOf(date: LocalDate) = DateKeys.dayKey(date)

/** The common date ranges, computed from today. */
private fun presets(today: LocalDate): List<Pair<String, DateRange>> = listOf(
    "Últimos 30 días" to DateRange(dayKeyOf(today.minusDays(30)), dayKeyOf(today)),
    "Este año" to DateRange(today.year * 10_000 + 101, today.year * 10_000 + 1231),
    "Año pasado" to DateRange((today.year - 1) * 10_000 + 101, (today.year - 1) * 10_000 + 1231),
)

/** The date picker works in UTC midnights, whatever the device zone. */
private fun millisToDayKey(ms: Long): Int = dayKeyOf(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate())

/**
 * Filters and nothing else: changes are drafted here and applied together, and the button always says how many photos they
 * would show, so a combination that matches nothing is seen before it is applied.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryFilterSheet(
    initial: LibraryFilters,
    config: FilterSheetConfig,
    countFor: suspend (LibraryFilters) -> Int,
    onApply: (LibraryFilters) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    var count by remember { mutableStateOf<Int?>(null) }
    var pickRange by remember { mutableStateOf(false) }
    val today = remember { LocalDate.now() }
    LaunchedEffect(draft) { delay(120); count = countFor(draft) }

    // Opens fully (never half-way): the apply button at the bottom must be on screen from the start.
    ModalBottomSheet(
        onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        // The button that applies the filters is outside the scrolling part: it is always on screen, however long the sheet is.
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Space.screen).padding(bottom = Space.l)) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Filtros", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                QuietButton("Restablecer", { draft = LibraryFilters() }, enabled = !draft.isEmpty)
            }

            SheetSection("Fecha de captura") {
                val presets = presets(today)
                val matchedPreset = presets.firstOrNull { it.second == draft.dateRange }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    FilterChip(selected = draft.dateRange == null, onClick = { draft = draft.copy(dateRange = null) }, label = { Text("Cualquiera") })
                    presets.forEach { (label, range) ->
                        FilterChip(selected = draft.dateRange == range, onClick = { draft = draft.copy(dateRange = range) }, label = { Text(label) })
                    }
                    FilterChip(
                        selected = draft.dateRange != null && matchedPreset == null,
                        onClick = { pickRange = true },
                        label = { Text(draft.dateRange?.takeIf { matchedPreset == null }?.let(::dateRangeLabel) ?: "Elegir rango…") },
                        leadingIcon = { Icon(Icons.Filled.CalendarMonth, null) },
                    )
                }
                if (config.scope == LibraryScope.CLOUD && config.undatedCount != null && config.undatedCount > 0) {
                    Note("Los filtros de fecha usan la fecha de captura. ${fmt(config.undatedCount)} objetos no la tienen y no entran en ellos.")
                }
            }

            SheetSection("Tipo") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    listOf(MediaKind.IMAGE to "Fotos", MediaKind.VIDEO to "Vídeos").forEach { (k, label) ->
                        FilterChip(
                            selected = k in draft.kinds,
                            onClick = { draft = draft.copy(kinds = if (k in draft.kinds) draft.kinds - k else draft.kinds + k) },
                            label = { Text(label) },
                        )
                    }
                }
                if (config.scope == LibraryScope.CLOUD) Note("En S3 el tipo se deduce de la extensión del archivo.")
            }

            if (config.scope == LibraryScope.LOCAL) {
                SheetSection("Respaldo") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        listOf(
                            BackupFilter.BACKED_UP to "Respaldadas", BackupFilter.NOT_BACKED_UP to "Sin respaldar",
                            BackupFilter.IN_PROGRESS to "En curso", BackupFilter.FAILED to "Con error",
                        ).forEach { (b, label) ->
                            FilterChip(
                                selected = b in draft.backup,
                                onClick = { draft = draft.copy(backup = if (b in draft.backup) draft.backup - b else draft.backup + b) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
                if (config.folders.size > 1) {
                    SheetSection("Carpeta") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                            config.folders.take(14).forEach { f ->
                                FilterChip(
                                    selected = f.name in draft.folders,
                                    onClick = { draft = draft.copy(folders = if (f.name in draft.folders) draft.folders - f.name else draft.folders + f.name) },
                                    label = { Text("${f.name} · ${fmt(f.n)}") },
                                )
                            }
                        }
                    }
                }
                if ((config.undatedCount ?: 0) > 0) {
                    SheetSection("Fecha estimada") {
                        FilterChip(
                            selected = draft.undatedOnly,
                            onClick = { draft = draft.copy(undatedOnly = !draft.undatedOnly) },
                            label = { Text("Sin fecha de captura (${fmt(config.undatedCount!!)})") },
                        )
                        Note("Son fotos sin fecha de captura en sus metadatos: se colocan por la fecha del archivo.")
                    }
                }
            } else {
                SheetSection("Disponibilidad") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        listOf(CloudAvailability.CLOUD_ONLY to "Solo en S3", CloudAvailability.ALSO_LOCAL to "También en el dispositivo").forEach { (a, label) ->
                            FilterChip(
                                selected = draft.availability == a,
                                onClick = { draft = draft.copy(availability = if (draft.availability == a) null else a) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }

        }
        PrimaryButton(
            text = when (val n = count) { null -> "Mostrar resultados"; 0 -> "Sin resultados con estos filtros"; 1 -> "Mostrar 1 resultado"; else -> "Mostrar ${fmt(n)} resultados" },
            onClick = { onApply(draft) }, modifier = Modifier.fillMaxWidth().padding(top = Space.m),
        )
        }
    }

    if (pickRange) {
        val state = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { pickRange = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        val from = state.selectedStartDateMillis
                        val to = state.selectedEndDateMillis ?: from
                        if (from != null && to != null) draft = draft.copy(dateRange = DateRange(millisToDayKey(from), millisToDayKey(to)))
                        pickRange = false
                    },
                ) { Text("Aplicar") }
            },
            dismissButton = { TextButton(onClick = { pickRange = false }) { Text("Cancelar") } },
        ) { DateRangePicker(state, modifier = Modifier.heightIn(max = 520.dp), showModeToggle = true) }
    }
}

@Composable
private fun SheetSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/**
 * "Go to a date": years with their months and counts, or an exact day. Built from the same rows as the grid, so every entry
 * leads somewhere that exists. [undatedNote] names the section of objects that have no capture date, when there is one.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryTimelineSheet(
    index: TimelineIndex,
    onMonth: (Int) -> Unit,
    onYear: (Int) -> Unit,
    onDate: (Int) -> Unit,
    onUndated: (() -> Unit)?,
    undatedLabel: String,
    onDismiss: () -> Unit,
) {
    var expanded by remember { mutableStateOf(index.years.firstOrNull()?.year) }
    var pickDate by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Space.screen).padding(bottom = Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text("Ir a una fecha", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            if (index.years.isEmpty() && index.undated == 0) {
                Text("No hay fotos con fecha en esta lista.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                AssistChip(onClick = { pickDate = true }, label = { Text("Elegir un día exacto…") }, leadingIcon = { Icon(Icons.Filled.CalendarMonth, null) })
            }
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(index.years, key = { it.year }) { y ->
                    val open = expanded == y.year
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = Space.touch).clickable(role = Role.Button, onClickLabel = if (open) "Contraer ${y.year}" else "Ver los meses de ${y.year}") { expanded = if (open) null else y.year }
                            .padding(vertical = Space.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(y.year.toString(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(fmt(y.count), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (open) {
                        FlowRow(Modifier.padding(bottom = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                            AssistChip(onClick = { onYear(y.year) }, label = { Text("Todo ${y.year}") })
                            y.months.forEach { m ->
                                AssistChip(onClick = { onMonth(m.monthKey) }, label = { Text(DateKeys.monthLabel(m.monthKey).substringBefore(' ') + " · " + fmt(m.count)) })
                            }
                        }
                    }
                }
                if (onUndated != null && index.undated > 0) {
                    item(key = "undated") {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = Space.touch).clickable(role = Role.Button, onClick = onUndated).padding(vertical = Space.s),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(undatedLabel, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Text(fmt(index.undated), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    if (pickDate) {
        val state = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(enabled = state.selectedDateMillis != null, onClick = { state.selectedDateMillis?.let { onDate(millisToDayKey(it)) }; pickDate = false }) { Text("Ir") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancelar") } },
        ) { DatePicker(state, showModeToggle = true) }
    }
}
