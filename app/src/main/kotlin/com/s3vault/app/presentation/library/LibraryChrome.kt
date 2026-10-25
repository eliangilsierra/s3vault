package com.s3vault.app.presentation.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.s3vault.app.domain.MediaKind
import com.s3vault.app.domain.library.BackupFilter
import com.s3vault.app.domain.library.CloudAvailability
import com.s3vault.app.domain.library.DateKeys
import com.s3vault.app.domain.library.DateRange
import com.s3vault.app.domain.library.GroupBy
import com.s3vault.app.domain.library.LibraryFilters
import com.s3vault.app.domain.library.SearchChip
import com.s3vault.app.domain.library.SortField
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.presentation.ui.EmptyState
import com.s3vault.app.presentation.ui.PrimaryButton
import com.s3vault.app.settings.GridDensity

// ------------------------------------------------------------------ wording shared by both galleries

fun sortFieldLabel(f: SortField): String = when (f) {
    SortField.CAPTURE_DATE -> "Fecha de captura"
    SortField.MODIFIED_DATE -> "Fecha de modificación"
    SortField.UPLOAD_DATE -> "Fecha de subida"
    SortField.NAME -> "Nombre"
    SortField.SIZE -> "Tamaño"
}

/** The order in a few words, as it reads on the chip ("Recientes primero", "Nombre A–Z"). */
fun sortSummary(f: SortField, descending: Boolean): String = when (f) {
    SortField.CAPTURE_DATE -> if (descending) "Recientes primero" else "Antiguas primero"
    SortField.MODIFIED_DATE -> if (descending) "Modificadas hace poco" else "Modificadas hace tiempo"
    SortField.UPLOAD_DATE -> if (descending) "Subidas hace poco" else "Subidas hace tiempo"
    SortField.NAME -> if (descending) "Nombre Z–A" else "Nombre A–Z"
    SortField.SIZE -> if (descending) "Más pesadas" else "Más ligeras"
}

/** Direction a field starts with when it is chosen: newest, largest, A to Z. */
fun defaultDescending(f: SortField): Boolean = f != SortField.NAME

fun groupLabel(g: GroupBy): String = when (g) {
    GroupBy.DAY -> "Día"; GroupBy.MONTH -> "Mes"; GroupBy.YEAR -> "Año"; GroupBy.NONE -> "Sin agrupar"
}

fun dateRangeLabel(r: DateRange): String {
    val sameYear = r.fromDay / 10_000 == r.toDay / 10_000
    return when {
        r.fromDay == r.toDay -> DateKeys.shortDayLabel(r.fromDay)
        sameYear && r.fromDay % 10_000 == 101 && r.toDay % 10_000 == 1231 -> (r.fromDay / 10_000).toString()
        r.fromDay / 100 == r.toDay / 100 && r.fromDay % 100 == 1 && r.toDay % 100 >= 28 -> DateKeys.monthLabel(r.fromDay / 100)
        else -> DateKeys.shortDayLabel(r.fromDay) + " – " + DateKeys.shortDayLabel(r.toDay)
    }
}

/** A removable chip standing for one active filter. */
data class ChipSpec(val label: String, val onRemove: () -> Unit)

fun filterChips(f: LibraryFilters, onChange: (LibraryFilters) -> Unit): List<ChipSpec> = buildList {
    f.dateRange?.let { add(ChipSpec(dateRangeLabel(it)) { onChange(f.copy(dateRange = null)) }) }
    f.kinds.forEach { k -> add(ChipSpec(if (k == MediaKind.IMAGE) "Fotos" else "Vídeos") { onChange(f.copy(kinds = f.kinds - k)) }) }
    f.backup.forEach { b ->
        add(
            ChipSpec(
                when (b) {
                    BackupFilter.BACKED_UP -> "Respaldadas"
                    BackupFilter.NOT_BACKED_UP -> "Sin respaldar"
                    BackupFilter.IN_PROGRESS -> "En curso"
                    BackupFilter.FAILED -> "Con error"
                },
            ) { onChange(f.copy(backup = f.backup - b)) },
        )
    }
    f.availability?.let { a -> add(ChipSpec(if (a == CloudAvailability.CLOUD_ONLY) "Solo en S3" else "También en el dispositivo") { onChange(f.copy(availability = null)) }) }
    f.folders.forEach { name -> add(ChipSpec(name) { onChange(f.copy(folders = f.folders - name)) }) }
    if (f.undatedOnly) add(ChipSpec("Sin fecha de captura") { onChange(f.copy(undatedOnly = false)) })
    f.minSizeBytes?.let { add(ChipSpec("${(it / (1024 * 1024)).coerceAtLeast(1)} MB o más") { onChange(f.copy(minSizeBytes = null)) }) }
}

// ------------------------------------------------------------------ search field

/** The search field that takes over the top bar. Says what it searches, clears with one tap, closes with the back arrow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySearchBar(
    text: String,
    onText: (String) -> Unit,
    onClose: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    val focus = remember { FocusRequester() }
    val manager = LocalFocusManager.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        modifier = modifier,
        navigationIcon = { IconButton(onClick = { manager.clearFocus(); onClose() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cerrar la búsqueda") } },
        title = {
            TextField(
                value = text, onValueChange = onText, singleLine = true,
                placeholder = { Text(placeholder, maxLines = 1, style = MaterialTheme.typography.bodyMedium) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Buscar" },
                trailingIcon = {
                    if (text.isNotEmpty()) IconButton(onClick = { onText("") }) { Icon(Icons.Filled.Close, "Borrar el texto") }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { manager.clearFocus() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

// ------------------------------------------------------------------ chips of what is active

/** What the search was understood as plus the active filters, each removable, and one way to clear everything. */
@Composable
fun ActiveChipsRow(
    searchChips: List<SearchChip>,
    filterChips: List<ChipSpec>,
    onRemoveSearch: (SearchChip) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (searchChips.isEmpty() && filterChips.isEmpty()) return
    LazyRow(
        modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.screen, vertical = Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(searchChips, key = { "s-" + it.raw + it.label }) { chip ->
            InputChip(
                selected = false, onClick = { onRemoveSearch(chip) },
                label = { Text(chip.label) },
                trailingIcon = { Icon(Icons.Filled.Close, "Quitar ${chip.label}", Modifier.padding(0.dp)) },
            )
        }
        items(filterChips, key = { "f-" + it.label }) { chip ->
            InputChip(
                selected = true, onClick = chip.onRemove,
                label = { Text(chip.label) },
                trailingIcon = { Icon(Icons.Filled.Close, "Quitar filtro ${chip.label}") },
            )
        }
        item(key = "clear") {
            androidx.compose.material3.TextButton(onClick = onClearAll) { Text("Borrar todo") }
        }
    }
}

// ------------------------------------------------------------------ view bar: grouping, sorting, density

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryViewBar(
    group: GroupBy,
    groupEnabled: Boolean,
    onGroup: (GroupBy) -> Unit,
    sort: SortField,
    descending: Boolean,
    sortFields: List<SortField>,
    onSort: (SortField, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    // A flow, not a row: on a narrow phone the segments, the order chip and the view buttons do not fit on one line, and a
    // squeezed segmented control (cut-off labels) is worse than a second line.
    FlowRow(
        modifier.fillMaxWidth().padding(horizontal = Space.screen, vertical = Space.xs),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        val options = listOf(GroupBy.DAY, GroupBy.MONTH, GroupBy.YEAR)
        SingleChoiceSegmentedButtonRow(Modifier.width(228.dp)) {
            options.forEachIndexed { i, g ->
                SegmentedButton(
                    selected = groupEnabled && group == g,
                    onClick = { onGroup(g) },
                    enabled = groupEnabled,
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    label = { Text(groupLabel(g), maxLines = 1, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
        SortMenu(sort, descending, sortFields, onSort)
        trailing()
    }
}

@Composable
fun SortMenu(sort: SortField, descending: Boolean, fields: List<SortField>, onSort: (SortField, Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(sortSummary(sort, descending), maxLines = 1) },
            leadingIcon = { Icon(Icons.Filled.SwapVert, null) },
            modifier = Modifier.semantics { contentDescription = "Ordenar: ${sortSummary(sort, descending)}" },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fields.forEach { f ->
                DropdownMenuItem(
                    text = { Text(sortFieldLabel(f)) },
                    leadingIcon = { if (f == sort) Icon(Icons.Filled.Check, null) },
                    onClick = { open = false; if (f != sort) onSort(f, defaultDescending(f)) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Invertir orden (${sortSummary(sort, !descending)})") },
                onClick = { open = false; onSort(sort, !descending) },
            )
        }
    }
}

@Composable
fun DensityMenu(density: GridDensity, onDensity: (GridDensity) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.GridView, "Tamaño de las miniaturas: ${density.label}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            GridDensity.entries.forEach { d ->
                DropdownMenuItem(
                    text = { Text(d.label) },
                    leadingIcon = { if (d == density) Icon(Icons.Filled.Check, null) },
                    onClick = { open = false; onDensity(d) },
                )
            }
        }
    }
}

// ------------------------------------------------------------------ top bar actions

@Composable
fun SearchAction(onClick: () -> Unit) = IconButton(onClick = onClick) { Icon(Icons.Filled.Search, "Buscar") }

@Composable
fun TimelineAction(onClick: () -> Unit) = IconButton(onClick = onClick) { Icon(Icons.Filled.CalendarMonth, "Ir a una fecha") }

@Composable
fun FilterAction(activeCount: Int, onClick: () -> Unit) = IconButton(onClick = onClick) {
    BadgedBox(badge = { if (activeCount > 0) Badge { Text(activeCount.toString()) } }) {
        Icon(Icons.Filled.FilterList, if (activeCount > 0) "Filtros ($activeCount activos)" else "Filtros")
    }
}

// ------------------------------------------------------------------ empty result

/** Search or filters matched nothing: say so, say what to try, and make undoing it one tap. */
@Composable
fun NoResults(hint: String, onReset: () -> Unit, modifier: Modifier = Modifier) {
    EmptyState(
        icon = Icons.Filled.SearchOff,
        title = "Sin resultados",
        body = hint,
        modifier = modifier,
    ) { PrimaryButton("Quitar búsqueda y filtros", onReset, Modifier.fillMaxWidth()) }
}
