package com.s3vault.app.presentation.remote

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.s3vault.app.domain.MediaKind
import com.s3vault.app.domain.RemoteObject
import com.s3vault.app.domain.SyncState
import com.s3vault.app.domain.library.DateKeys
import com.s3vault.app.domain.library.DateSource
import com.s3vault.app.domain.library.LibraryScope
import com.s3vault.app.domain.library.SearchParser
import com.s3vault.app.domain.library.SortField
import com.s3vault.app.presentation.GridSkeleton
import com.s3vault.app.presentation.RemoteDetail
import com.s3vault.app.presentation.RemoteViewModel
import com.s3vault.app.presentation.delete.DeleteHost
import com.s3vault.app.presentation.delete.DeleteViewModel
import com.s3vault.app.presentation.formatBytes
import com.s3vault.app.presentation.formatDateTime
import com.s3vault.app.presentation.library.ActiveChipsRow
import com.s3vault.app.presentation.library.CellSkeleton
import com.s3vault.app.presentation.library.DensityMenu
import com.s3vault.app.presentation.library.FilterAction
import com.s3vault.app.presentation.library.FilterSheetConfig
import com.s3vault.app.presentation.library.LibraryFilterSheet
import com.s3vault.app.presentation.library.LibraryGrid
import com.s3vault.app.presentation.library.LibraryLayout
import com.s3vault.app.presentation.library.LibrarySearchBar
import com.s3vault.app.presentation.library.LibraryTimelineSheet
import com.s3vault.app.presentation.library.LibraryViewBar
import com.s3vault.app.presentation.library.NoResults
import com.s3vault.app.presentation.library.SearchAction
import com.s3vault.app.presentation.library.SectionHeading
import com.s3vault.app.presentation.library.TimelineAction
import com.s3vault.app.presentation.library.TimelineIndex
import com.s3vault.app.presentation.library.filterChips
import com.s3vault.app.presentation.theme.PhotoOverlay
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.presentation.theme.status
import com.s3vault.app.presentation.theme.technical
import com.s3vault.app.presentation.ui.AppTopBar
import com.s3vault.app.presentation.ui.ConfirmDialog
import com.s3vault.app.presentation.ui.EmptyState
import com.s3vault.app.presentation.ui.PrimaryButton
import com.s3vault.app.presentation.ui.QuietButton
import com.s3vault.app.presentation.ui.SecondaryButton
import com.s3vault.app.presentation.ui.SlimProgress
import com.s3vault.app.presentation.ui.StatusBanner
import com.s3vault.app.presentation.ui.Tone
import com.s3vault.app.presentation.ui.skeleton
import com.s3vault.app.settings.CloudViewMode
import com.s3vault.app.sync.PreviewResult
import com.s3vault.app.sync.Progress
import kotlinx.coroutines.launch

private fun count(n: Int) = "%,d".format(java.util.Locale.forLanguageTag("es"), n)

/** "hace 5 min": how old the downloaded list is, in words (never a timestamp the user has to subtract). */
private fun formatAgo(atMs: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = ((now - atMs) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "ahora mismo"
        minutes < 60 -> "hace $minutes min"
        minutes < 60 * 24 -> "hace ${minutes / 60} h"
        else -> "hace ${minutes / (60 * 24)} d"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(
    vm: RemoteViewModel,
    deleteVm: DeleteViewModel,
    onOpenS3Settings: () -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenPhoto: (RemoteObject) -> Unit,
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val selected by vm.selectedKeys.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val browse by vm.browse.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val searchText by vm.searchInput.collectAsStateWithLifecycle()
    val density by vm.density.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val total by vm.totalObjects.collectAsStateWithLifecycle()
    val listing by vm.listing.collectAsStateWithLifecycle()
    val pendingDates by vm.pendingDates.collectAsStateWithLifecycle()
    val undated by vm.undatedCount.collectAsStateWithLifecycle()
    val sectionSelection by vm.sectionSelection.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current
    var confirmDuplicates by remember { mutableStateOf<List<RemoteObject>?>(null) }
    var searching by rememberSaveable { mutableStateOf(query.search.isNotEmpty()) }
    var filterSheet by remember { mutableStateOf(false) }
    var timelineSheet by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val selecting = selected.isNotEmpty()
    // How many of the selected objects also exist on this device (a COUNT in the database, merged for rapid taps).
    val alsoLocal by vm.alsoLocal.collectAsStateWithLifecycle()
    val layout = browse.layout
    val parsed = remember(query.search) { SearchParser.parse(query.search) }
    val chips = filterChips(query.filters) { vm.setFilters(it) }
    val listedAgo = listing?.let { formatAgo(it.atMs) }

    BackHandler(enabled = selecting) { vm.clearSelection() }
    BackHandler(enabled = !selecting && searching) { searching = false; if (!query.isDefault) vm.resetAll() }

    LaunchedEffect(s.message) { s.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }
    // Opening the tab never lists the bucket again on its own, except the very first time: every listing is a request.
    LaunchedEffect(s.configured) { if (s.configured && total == 0 && listing == null) vm.refresh() }
    LaunchedEffect(query) { gridState.scrollToItem(0); listState.scrollToItem(0) }

    fun jumpTo(section: Int?) {
        if (section == null) return
        scope.launch {
            val entry = layout.entryOfSection(section)
            if (mode == CloudViewMode.GRID) gridState.scrollToItem(entry) else listState.scrollToItem(entry)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Crossfade(targetState = if (selecting) 2 else if (searching) 1 else 0, animationSpec = tween(150), label = "remoteBar") { barMode ->
                when (barMode) {
                    2 -> AppTopBar(
                        title = if (selected.size == 1) "1 seleccionada" else "${selected.size} seleccionadas",
                        compact = true,
                        subtitle = if (alsoLocal == 0) "Solo existen en S3" else "$alsoLocal también en este dispositivo",
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        navigation = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.Close, "Cancelar selección") } },
                        actions = {
                            IconButton(onClick = vm::selectAllMatching) { Icon(Icons.Filled.SelectAll, "Seleccionar todo") }
                            IconButton(onClick = {
                                scope.launch {
                                    val toRestore = vm.selectedObjects().filter { it.state == SyncState.REMOTE_ONLY }
                                    val dupes = toRestore.filter { it.availableLocally }
                                    when {
                                        toRestore.isEmpty() -> vm.postMessage("Esas fotos ya se están restaurando.")
                                        dupes.isNotEmpty() -> confirmDuplicates = toRestore
                                        else -> vm.restoreSelected()
                                    }
                                }
                            }) { Icon(Icons.Filled.Download, "Restaurar en este dispositivo") }
                            IconButton(onClick = {
                                scope.launch { deleteVm.requestCloud(vm.selectedObjects().filter { it.state != SyncState.RESTORING }) }
                            }) { Icon(Icons.Filled.Delete, "Eliminar de S3…", tint = MaterialTheme.colorScheme.error) }
                        },
                    )
                    1 -> LibrarySearchBar(
                        text = searchText, onText = vm::setSearchText,
                        onClose = { searching = false; if (!query.isDefault) vm.resetAll() },
                        placeholder = "Nombre de archivo, fecha o tipo",
                        actions = { FilterAction(query.filters.activeCount) { filterSheet = true } },
                    )
                    else -> AppTopBar(
                        title = "Biblioteca en S3",
                        subtitle = when {
                            total == 0 -> null
                            query.isDefault -> if (total == 1) "1 objeto" else "${count(total)} objetos"
                            else -> "${count(browse.total)} de ${count(total)}"
                        },
                        actions = {
                            if (s.configured && total > 0) {
                                SearchAction { searching = true }
                                if (layout.hasHeaders) TimelineAction { timelineSheet = true }
                                FilterAction(query.filters.activeCount) { filterSheet = true }
                            }
                            IconButton(onClick = vm::refresh, enabled = s.configured && !s.loading) { Icon(Icons.Filled.Refresh, "Actualizar listado") }
                        },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (s.loading) {
                SlimProgress(null, Modifier.padding(horizontal = Space.screen))
                Text(
                    if (total > 0) "Actualizando la lista: ${count(total)} objetos hasta ahora…" else "Leyendo el bucket…",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Space.screen, vertical = Space.xs),
                )
            }
            if (!selecting) {
                ActiveChipsRow(searchChips = parsed.chips, filterChips = chips, onRemoveSearch = vm::removeSearchChip, onClearAll = vm::resetAll)
            }
            if (s.configured && total > 0 && !selecting) {
                ListingNote(
                    searchingOrFiltering = searching || !query.isDefault,
                    shown = browse.total, objects = total, ago = listedAgo, complete = listing?.complete != false,
                )
                if (listing?.complete == false && !s.loading) {
                    StatusBanner(
                        Tone.Warning, "La última actualización no terminó",
                        "Puede que falten objetos o que se muestren algunos que ya no existen. Los resultados no son un inventario completo.",
                        "Reintentar", vm::refresh, Modifier.padding(horizontal = Space.screen, vertical = Space.xs),
                    )
                }
                if (s.error != null && !s.loading) {
                    StatusBanner(
                        if (s.needsCredentials) Tone.Warning else Tone.Error, "No se pudo actualizar la lista",
                        (s.error ?: "") + (listedAgo?.let { " Se muestra la lista descargada $it." } ?: ""),
                        if (s.needsCredentials) "Ir a credenciales" else "Reintentar", if (s.needsCredentials) onOpenSecurity else vm::refresh,
                        Modifier.padding(horizontal = Space.screen, vertical = Space.xs),
                    )
                }
                if (undated > 0 && pendingDates > 0 && !searching && query.isDefault) {
                    UndatedCard(undated, pendingDates, vm::completeDates, Modifier.padding(horizontal = Space.screen, vertical = Space.xs))
                }
                LibraryViewBar(
                    group = query.group, groupEnabled = layout.hasHeaders || query.sort == SortField.CAPTURE_DATE || query.sort == SortField.UPLOAD_DATE,
                    onGroup = vm::setGroup, sort = query.sort, descending = query.descending, sortFields = LibraryScope.CLOUD.sortFields, onSort = vm::setSort,
                ) {
                    if (mode == CloudViewMode.GRID) DensityMenu(density, vm::setDensity)
                    IconButton(onClick = { vm.setMode(if (mode == CloudViewMode.GRID) CloudViewMode.LIST else CloudViewMode.GRID) }) {
                        if (mode == CloudViewMode.GRID) Icon(Icons.AutoMirrored.Filled.ViewList, "Ver como lista") else Icon(Icons.Filled.GridView, "Ver como cuadrícula")
                    }
                }
            }
            when {
                !s.configured -> EmptyState(
                    icon = Icons.Filled.CloudOff,
                    title = "Amazon S3 no está configurado",
                    body = "Configura bucket y credenciales para explorar tus respaldos.",
                ) { PrimaryButton("Configurar S3", onOpenS3Settings, Modifier.fillMaxWidth()) }
                s.error != null && total == 0 -> EmptyState(
                    icon = Icons.Filled.ErrorOutline,
                    tone = if (s.needsCredentials) Tone.Warning else Tone.Error,
                    title = "No se pudo leer el bucket",
                    body = s.error,
                ) {
                    PrimaryButton("Reintentar", vm::refresh, Modifier.fillMaxWidth())
                    if (s.needsCredentials) QuietButton("Ir a credenciales", onOpenSecurity)
                }
                total == 0 && s.loading -> RemoteSkeleton()
                total == 0 -> EmptyState(
                    icon = Icons.Filled.CloudQueue,
                    title = "No hay objetos respaldados",
                    body = "No se encontró nada bajo el prefijo configurado. Cada listado es una petición LIST de S3 (con coste mínimo): actualiza solo cuando lo necesites.",
                )
                !browse.ready -> GridSkeleton()
                browse.total == 0 -> NoResults(
                    hint = "Nada de la lista descargada coincide con lo que has pedido." +
                        (if (parsed.hasDate || query.filters.dateRange != null) " Las fechas se buscan solo entre los objetos que tienen fecha de captura." else "") +
                        " La búsqueda no consulta S3: si acabas de subir algo desde otro sitio, actualiza la lista.",
                    onReset = { vm.resetAll() },
                )
                mode == CloudViewMode.GRID -> LibraryGrid(
                    layout = layout, source = vm.window, minCell = density.minCellDp.dp, state = gridState, modifier = Modifier.fillMaxSize(),
                    header = { sec -> RemoteHeading(layout, sec, selecting, sectionSelection, vm) },
                    cell = { _, obj ->
                        if (obj == null) CellSkeleton() else RemoteCell(
                            obj, obj.key in selected, selecting, downloads[obj.key], vm::loadPreview,
                            onClick = { if (selecting) vm.toggle(obj.key) else onOpenPhoto(obj) },
                            onLongClick = {
                                if (obj.state != SyncState.RESTORING) {
                                    if (!selecting) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    vm.toggle(obj.key)
                                }
                            },
                        )
                    },
                )
                else -> RemoteList(layout, vm, listState, selected, selecting, downloads, sectionSelection, onOpenPhoto) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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

    s.detail?.let { d -> RemoteDetailSheet(d, vm::dismissDetail) }

    if (filterSheet) {
        LibraryFilterSheet(
            initial = query.filters, config = FilterSheetConfig(LibraryScope.CLOUD, undatedCount = undated),
            countFor = vm::countFor, onApply = { vm.setFilters(it); filterSheet = false }, onDismiss = { filterSheet = false },
        )
    }

    if (timelineSheet) {
        LibraryTimelineSheet(
            index = remember(browse.days) { TimelineIndex(browse.days) },
            onMonth = { timelineSheet = false; jumpTo(layout.sectionOfMonth(it)) },
            onYear = { timelineSheet = false; jumpTo(layout.sectionOfYear(it)) },
            onDate = { day ->
                timelineSheet = false
                layout.sectionNear(day, query.descending)?.let { (section, exact) ->
                    jumpTo(section)
                    if (!exact) scope.launch { snackbar.showSnackbar("No hay fotos el ${DateKeys.shortDayLabel(day)}: se muestra la fecha más cercana.") }
                }
            },
            onUndated = layout.undatedSection()?.let { sec -> { timelineSheet = false; jumpTo(sec) } },
            undatedLabel = "Sin fecha de captura",
            onDismiss = { timelineSheet = false },
        )
    }

    DeleteHost(deleteVm, snackbar, onStarted = vm::clearSelection, onOpenCredentials = onOpenSecurity)
}

/**
 * The scope of everything on this screen, stated every time it could be misread: lists, searches and filters work on the
 * objects already downloaded, never on the bucket.
 */
@Composable
private fun ListingNote(searchingOrFiltering: Boolean, shown: Int, objects: Int, ago: String?, complete: Boolean) {
    val text = if (searchingOrFiltering) {
        "${count(shown)} de ${count(objects)} objetos de la lista descargada" + (ago?.let { " ($it)" } ?: "") + ". Buscar y filtrar no consulta S3."
    } else {
        "Lista descargada" + (ago?.let { " $it" } ?: "") + " · ${count(objects)} objetos" + if (complete) "" else " (incompleta)"
    }
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Space.screen, vertical = Space.xs).semantics { contentDescription = text },
    )
}

/** Offers, with its cost spelled out, to read the capture date of the objects that have none. Never starts by itself. */
@Composable
private fun UndatedCard(undated: Int, pending: Int, onComplete: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text("${count(undated)} objetos sin fecha de captura", style = MaterialTheme.typography.titleSmall)
            Text(
                "Se muestran al final, por fecha de subida, porque la subida no es cuándo se hizo la foto. Puedes leer su fecha real de los metadatos: " +
                    "una petición HEAD por objeto (${count(pending)} en total, unos ${cost(pending)} en S3), una sola vez, en segundo plano.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SecondaryButton("Completar fechas", onComplete, Modifier.fillMaxWidth())
        }
    }
}

/** HEAD costs the same as GET: 0.0004 USD per 1000 requests. */
private fun cost(requests: Int): String {
    val usd = requests * 0.0000004
    return if (usd < 0.01) "menos de 0,01 US$" else "%.2f US$".format(java.util.Locale.forLanguageTag("es"), usd)
}

@Composable
private fun RemoteHeading(layout: LibraryLayout, sec: Int, selecting: Boolean, selection: Set<Int>, vm: RemoteViewModel) {
    val section = layout.sections[sec]
    SectionHeading(
        label = layout.sectionLabel(sec), count = section.count,
        note = if (section.key == 0) "Ordenadas por fecha de subida" else null,
        selectionMode = selecting, sectionSelected = section.key in selection, onToggleSection = { vm.toggleSection(sec) },
    )
}

/** Technical metadata of one S3 object (a single HEAD request, made only when the user asks). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteDetailSheet(d: RemoteDetail, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
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
            QuietButton("Cerrar", onDismiss, Modifier.align(Alignment.End))
        }
    }
}

@Composable
private fun RemoteSkeleton() {
    Column(Modifier.fillMaxSize().padding(horizontal = Space.screen, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.l)) {
        repeat(8) { ListSkeletonRow() }
    }
}

private val ThumbSize = 64.dp

private fun statusOf(obj: RemoteObject): String = when {
    obj.state == SyncState.RESTORING -> "restaurando"
    obj.availableLocally -> "también en este dispositivo"
    else -> "solo en S3"
}

/** How the date of an object is known, in the words the user needs (a capture date is not an upload date). */
private fun dateText(obj: RemoteObject): String = when (obj.dateSource) {
    DateSource.CAPTURE -> formatDateTime(obj.effectiveDateMs)
    DateSource.FILENAME -> "Fecha del nombre · " + DateKeys.shortDayLabel(DateKeys.dayKey(obj.effectiveDateMs, java.time.ZoneId.systemDefault()))
    DateSource.FALLBACK -> "Subida · " + formatDateTime(obj.lastModifiedMs)
}

/** One square of the S3 grid: the preview that was uploaded with the photo, where it exists, and where else it lives. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RemoteCell(
    obj: RemoteObject,
    selected: Boolean,
    selectionMode: Boolean,
    progress: Progress?,
    load: suspend (RemoteObject) -> PreviewResult,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .semantics(mergeDescendants = true) {
                contentDescription = obj.displayName + (if (obj.mediaKind == MediaKind.VIDEO) ", vídeo" else "") + ", " + statusOf(obj)
                this.selected = selected
            }
            .combinedClickable(
                onClickLabel = if (selectionMode) "Alternar selección" else "Ver vista previa", onClick = onClick,
                onLongClickLabel = "Seleccionar", onLongClick = onLongClick,
            ),
    ) {
        RemoteThumb(obj, load, Modifier.fillMaxSize())
        if (selected) Box(Modifier.fillMaxSize().background(primary.copy(alpha = 0.28f)))
        if (obj.mediaKind == MediaKind.VIDEO) {
            Box(Modifier.align(Alignment.Center).size(32.dp).clip(CircleShape).background(PhotoOverlay.scrim), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, null, tint = PhotoOverlay.onScrim, modifier = Modifier.size(22.dp))
            }
        }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(Space.xs + 2.dp).size(20.dp).clip(CircleShape).background(PhotoOverlay.scrim),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (obj.availableLocally) Icons.Filled.CloudDone else Icons.Filled.Cloud, null, Modifier.size(14.dp),
                tint = if (obj.availableLocally) PhotoOverlay.successOnScrim else PhotoOverlay.onScrim,
            )
        }
        if (obj.state == SyncState.RESTORING) {
            LinearProgressIndicator(
                progress = { progress?.fraction ?: 0f }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 3.dp),
                color = PhotoOverlay.onScrim, trackColor = PhotoOverlay.scrim,
            )
        }
        if (selectionMode && obj.state != SyncState.RESTORING) {
            Box(
                Modifier.align(Alignment.TopStart).padding(Space.s).size(22.dp).clip(CircleShape)
                    .background(if (selected) primary else PhotoOverlay.scrim).border(1.5.dp, if (selected) primary else PhotoOverlay.onScrim, CircleShape),
                contentAlignment = Alignment.Center,
            ) { if (selected) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp)) }
        }
    }
}

@Composable
private fun RemoteList(
    layout: LibraryLayout,
    vm: RemoteViewModel,
    state: LazyListState,
    selected: Set<String>,
    selecting: Boolean,
    downloads: Map<String, Progress>,
    sectionSelection: Set<Int>,
    onOpenPhoto: (RemoteObject) -> Unit,
    onLongPressFeedback: () -> Unit,
) {
    LaunchedEffect(state, layout) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.let { if (it.isEmpty()) null else it.first().index to it.last().index } }
            .collect { r -> if (r != null) vm.window.ensure(layout.itemRange(r.first, r.second)) }
    }
    LazyColumn(Modifier.widthIn(max = Space.maxContent).fillMaxSize(), state = state) {
        items(layout.entryCount, contentType = { if (layout.isHeader(it)) 0 else 1 }) { e ->
            val index = layout.itemIndexOf(e)
            if (index < 0) {
                RemoteHeading(layout, layout.sectionOfEntry(e), selecting, sectionSelection, vm)
            } else {
                val obj = vm.window[index]
                if (obj == null) ListSkeletonRow() else RemoteRow(
                    obj, obj.key in selected, selecting, downloads[obj.key], vm,
                    onClick = { if (selecting) vm.toggle(obj.key) else onOpenPhoto(obj) },
                    onLongClick = {
                        if (obj.state != SyncState.RESTORING) {
                            if (!selecting) onLongPressFeedback()
                            vm.toggle(obj.key)
                        }
                    },
                    onCancel = { vm.cancelRestore(obj.key) },
                )
            }
        }
    }
}

@Composable
private fun ListSkeletonRow() {
    Row(Modifier.padding(horizontal = Space.screen, vertical = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.l), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(ThumbSize).clip(MaterialTheme.shapes.medium).skeleton())
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Box(Modifier.size(width = 180.dp, height = 14.dp).clip(MaterialTheme.shapes.extraSmall).skeleton())
            Box(Modifier.size(width = 120.dp, height = 12.dp).clip(MaterialTheme.shapes.extraSmall).skeleton())
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
    vm: RemoteViewModel,
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
    val primary = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent)
            .semantics { this.selected = selected }
            .combinedClickable(
                role = Role.Button,
                onClickLabel = if (selecting) "Alternar selección" else "Ver vista previa",
                onClick = onClick,
                onLongClickLabel = "Seleccionar",
                onLongClick = onLongClick,
            )
            .heightIn(min = 80.dp).padding(horizontal = Space.screen, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        Box(Modifier.size(ThumbSize)) {
            RemoteThumb(
                obj, vm::loadPreview,
                Modifier.fillMaxSize().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainer),
            )
            if (selecting && obj.state != SyncState.RESTORING) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(Space.xs).size(22.dp).clip(CircleShape)
                        .background(if (selected) primary else PhotoOverlay.scrim)
                        .border(1.5.dp, if (selected) primary else PhotoOverlay.onScrim, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { if (selected) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp)) }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(obj.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${formatBytes(obj.sizeBytes)} · ${dateText(obj)}",
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
