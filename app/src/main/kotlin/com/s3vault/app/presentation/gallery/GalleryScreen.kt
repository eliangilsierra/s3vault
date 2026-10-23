package com.s3vault.app.presentation.gallery

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.s3vault.app.domain.library.DateKeys
import com.s3vault.app.domain.library.LibraryScope
import com.s3vault.app.domain.library.SearchParser
import com.s3vault.app.domain.library.SortField
import com.s3vault.app.presentation.GalleryViewModel
import com.s3vault.app.presentation.GridSkeleton
import com.s3vault.app.presentation.MediaCell
import com.s3vault.app.presentation.delete.DeleteHost
import com.s3vault.app.presentation.delete.DeleteViewModel
import com.s3vault.app.presentation.delete.toDeleteEntry
import com.s3vault.app.presentation.library.ActiveChipsRow
import com.s3vault.app.presentation.library.CellSkeleton
import com.s3vault.app.presentation.library.DensityMenu
import com.s3vault.app.presentation.library.FilterAction
import com.s3vault.app.presentation.library.FilterSheetConfig
import com.s3vault.app.presentation.library.LibraryFilterSheet
import com.s3vault.app.presentation.library.LibraryGrid
import com.s3vault.app.presentation.library.LibrarySearchBar
import com.s3vault.app.presentation.library.LibraryTimelineSheet
import com.s3vault.app.presentation.library.LibraryViewBar
import com.s3vault.app.presentation.library.NoResults
import com.s3vault.app.presentation.library.SearchAction
import com.s3vault.app.presentation.library.SectionHeading
import com.s3vault.app.presentation.library.TimelineAction
import com.s3vault.app.presentation.library.TimelineIndex
import com.s3vault.app.presentation.library.filterChips
import com.s3vault.app.presentation.shareMedia
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.presentation.ui.AppTopBar
import com.s3vault.app.presentation.ui.EmptyState
import com.s3vault.app.presentation.ui.PrimaryButton
import com.s3vault.app.presentation.ui.QuietButton
import com.s3vault.app.presentation.ui.SecondaryButton
import com.s3vault.app.presentation.ui.StatusBanner
import com.s3vault.app.presentation.ui.Tone
import com.s3vault.app.presentation.ui.rememberReducedMotion
import com.s3vault.app.sync.MediaAccess
import kotlinx.coroutines.launch

private fun requiredPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun count(n: Int) = "%,d".format(java.util.Locale.forLanguageTag("es"), n)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    vm: GalleryViewModel,
    deleteVm: DeleteViewModel,
    onOpen: (Long) -> Unit,
    onOpenBackup: () -> Unit = {},
    onOpenCredentials: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val browse by vm.browse.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val searchText by vm.searchInput.collectAsStateWithLifecycle()
    val density by vm.density.collectAsStateWithLifecycle()
    val uploads by vm.uploads.collectAsStateWithLifecycle()
    val summary by vm.selectionSummary.collectAsStateWithLifecycle()
    val total by vm.totalInLibrary.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val glance by vm.backupGlance.collectAsStateWithLifecycle()
    val sectionSelection by vm.sectionSelection.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var albumSheet by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(query.search.isNotEmpty()) }
    var filterSheet by remember { mutableStateOf(false) }
    var timelineSheet by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val reduced = rememberReducedMotion()
    val haptics = LocalHapticFeedback.current

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refresh() }

    // Rescan whenever the screen is shown (new photos, permission changes from system settings).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() }
    }
    // A different search, filter or order starts at the top: staying halfway down a new list would be disorienting.
    LaunchedEffect(query) { gridState.scrollToItem(0) }

    val selectionMode = state.selected.isNotEmpty()
    val parsed = remember(query.search) { SearchParser.parse(query.search) }
    val chips = filterChips(query.filters) { vm.setFilters(it) }
    val layout = browse.layout
    // Back leaves selection mode first, then the search, instead of closing the app.
    BackHandler(enabled = selectionMode) { vm.clearSelection() }
    BackHandler(enabled = !selectionMode && searching) { searching = false; if (query.isDefault) Unit else vm.resetAll() }

    fun jumpTo(section: Int?) {
        if (section != null) scope.launch { gridState.scrollToItem(layout.entryOfSection(section)) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Crossfade(
                targetState = if (selectionMode) 2 else if (searching) 1 else 0,
                animationSpec = tween(if (reduced) 0 else 150), label = "galleryBar",
            ) { mode ->
                when (mode) {
                    2 -> AppTopBar(
                        title = if (state.selected.size == 1) "1 seleccionada" else "${state.selected.size} seleccionadas",
                        subtitle = when {
                            summary.inCloud == 0 -> "Solo en este dispositivo"
                            summary.inCloud == summary.total -> "Todas con copia en S3"
                            else -> "${summary.inCloud} con copia en S3 · ${summary.total - summary.inCloud} sin copia"
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        navigation = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.Close, "Cancelar selección") } },
                        actions = {
                            IconButton(onClick = vm::selectAllMatching) { Icon(Icons.Filled.SelectAll, "Seleccionar todo") }
                            IconButton(onClick = vm::backupSelected) { Icon(Icons.Filled.CloudUpload, "Respaldar en S3") }
                            IconButton(onClick = { scope.launch { deleteVm.request(vm.selectedItems().map { it.toDeleteEntry() }) } }) {
                                Icon(Icons.Filled.Delete, "Eliminar…", tint = MaterialTheme.colorScheme.error)
                            }
                            Box {
                                IconButton(onClick = { overflow = true }) { Icon(Icons.Filled.MoreVert, "Más acciones") }
                                DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Añadir a álbum") },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                                        onClick = { overflow = false; albumSheet = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Compartir") },
                                        leadingIcon = { Icon(Icons.Filled.Share, null) },
                                        onClick = { overflow = false; scope.launch { shareMedia(ctx, vm.selectedItems()) } },
                                    )
                                }
                            }
                        },
                    )
                    1 -> LibrarySearchBar(
                        text = searchText, onText = vm::setSearchText,
                        onClose = { searching = false; if (!query.isDefault) vm.resetAll() },
                        placeholder = "Nombre, carpeta, fecha o tipo",
                        actions = { FilterAction(query.filters.activeCount) { filterSheet = true } },
                    )
                    else -> AppTopBar(
                        title = "Galería",
                        subtitle = when {
                            total == 0 -> null
                            query.isDefault -> if (total == 1) "1 elemento" else "${count(total)} elementos"
                            else -> "${count(browse.total)} de ${count(total)}"
                        },
                        actions = {
                            SearchAction { searching = true }
                            if (layout.hasHeaders) TimelineAction { timelineSheet = true }
                            FilterAction(query.filters.activeCount) { filterSheet = true }
                        },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!selectionMode) {
                ActiveChipsRow(
                    searchChips = parsed.chips, filterChips = chips,
                    onRemoveSearch = vm::removeSearchChip, onClearAll = vm::resetAll,
                )
            }
            if (state.access == MediaAccess.PARTIAL) {
                StatusBanner(
                    tone = Tone.Info,
                    title = "Acceso parcial",
                    body = "Acceso parcial: solo ves las fotos que elegiste.",
                    actionLabel = "Gestionar",
                    onAction = { launcher.launch(requiredPermissions()) },
                    modifier = Modifier.padding(horizontal = Space.screen, vertical = Space.s),
                )
            }
            if (!selectionMode && !searching && (glance.first > 0 || glance.second > 0)) {
                BackupGlanceChip(glance.first, glance.second, onOpenBackup, Modifier.padding(horizontal = Space.screen, vertical = Space.xs))
            }
            if (!selectionMode && state.access != MediaAccess.NONE && total > 0) {
                LibraryViewBar(
                    group = query.group,
                    groupEnabled = layout.hasHeaders || query.sort == SortField.CAPTURE_DATE,
                    onGroup = vm::setGroup,
                    sort = query.sort, descending = query.descending, sortFields = LibraryScope.LOCAL.sortFields,
                    onSort = vm::setSort,
                ) { DensityMenu(density, vm::setDensity) }
            }
            if (searching && !selectionMode && query.isDefault && searchText.isEmpty()) {
                SearchHints(
                    years = remember(browse.days) { TimelineIndex(browse.days).years.take(8) },
                    onPick = { vm.setSearchText(it) },
                )
            } else if (searching && !selectionMode && !query.isDefault) {
                Text(
                    if (browse.total == 1) "1 resultado en este dispositivo" else "${count(browse.total)} resultados en este dispositivo",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Space.screen, vertical = Space.xs),
                )
            }
            when {
                state.access == MediaAccess.NONE -> EmptyState(
                    icon = Icons.Filled.PhotoLibrary,
                    title = "Necesito acceso a tus fotos",
                    body = "La galería lee tus fotos y vídeos directamente del dispositivo. Nada se envía a ningún sitio hasta que configures Amazon S3 y elijas respaldar. " +
                        "Si ya lo denegaste, Android puede no volver a preguntar: abre los ajustes de la app.",
                ) {
                    PrimaryButton("Conceder acceso", { launcher.launch(requiredPermissions()) }, Modifier.fillMaxWidth())
                    QuietButton("Abrir ajustes de la app", {
                        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                    })
                }
                !browse.ready -> GridSkeleton()
                state.error != null && total == 0 -> EmptyState(
                    icon = Icons.Filled.ErrorOutline,
                    tone = Tone.Error,
                    title = "No se pudo cargar la galería",
                    body = state.error,
                ) { PrimaryButton("Reintentar", vm::refresh, Modifier.fillMaxWidth()) }
                total == 0 -> if (state.refreshing) GridSkeleton() else EmptyState(
                    icon = Icons.Filled.PhotoLibrary,
                    title = "No hay fotos",
                    body = "Cuando haya fotos o vídeos en el dispositivo aparecerán aquí.",
                )
                browse.total == 0 -> NoResults(
                    hint = noResultsHint(parsed.hasDate, query.filters.activeCount > 0, parsed.textTerms.isNotEmpty()),
                    onReset = { vm.resetAll() },
                )
                else -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
                    LibraryGrid(
                        layout = layout, source = vm.window, minCell = density.minCellDp.dp, state = gridState,
                        header = { s ->
                            val section = layout.sections[s]
                            SectionHeading(
                                label = layout.sectionLabel(s), count = section.count, selectionMode = selectionMode,
                                sectionSelected = section.key in sectionSelection, onToggleSection = { vm.toggleSection(s) },
                            )
                        },
                        cell = { _, item ->
                            if (item == null) CellSkeleton() else MediaCell(
                                item, item.mediaStoreId in state.selected, selectionMode, uploads[item.mediaStoreId],
                                onClick = { if (selectionMode) vm.toggle(it.mediaStoreId) else onOpen(it.mediaStoreId) },
                                onLongClick = {
                                    if (!selectionMode) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    vm.toggle(it.mediaStoreId)
                                },
                            )
                        },
                    )
                }
            }
        }
    }

    if (albumSheet) {
        AddToAlbumSheet(
            albums = state.albums.map { it.id to it.name },
            onPick = { vm.addSelectedToAlbum(it); albumSheet = false },
            onCreate = { vm.createAlbumWithSelected(it); albumSheet = false },
            onDismiss = { albumSheet = false },
        )
    }

    if (filterSheet) {
        val undated by vm.undatedCount.collectAsStateWithLifecycle()
        LibraryFilterSheet(
            initial = query.filters,
            config = FilterSheetConfig(LibraryScope.LOCAL, folders, undated),
            countFor = vm::countFor,
            onApply = { vm.setFilters(it); filterSheet = false },
            onDismiss = { filterSheet = false },
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
            onUndated = null, undatedLabel = "",
            onDismiss = { timelineSheet = false },
        )
    }

    DeleteHost(deleteVm, snackbar, onStarted = vm::clearSelection, onOpenCredentials = onOpenCredentials)
}

/** Says why nothing matched and what to try, so an empty result is never a dead end. */
private fun noResultsHint(date: Boolean, filters: Boolean, text: Boolean): String = buildString {
    append("Nada coincide con lo que has pedido.")
    if (text) append(" Prueba con otra parte del nombre.")
    if (date) append(" Comprueba el año o el mes: solo se muestran fechas en las que hay fotos.")
    if (filters) append(" Quita algún filtro para ampliar el resultado.")
}

/** Shown while the search is empty: what can be searched and the years that exist, one tap away. */
@Composable
private fun SearchHints(years: List<com.s3vault.app.presentation.library.YearEntry>, onPick: (String) -> Unit) {
    Column(Modifier.padding(horizontal = Space.screen, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text("Qué puedes buscar", style = MaterialTheme.typography.titleSmall)
        Text(
            "• Nombre de archivo o carpeta: IMG, WhatsApp\n• Fecha: 2023, marzo 2023, 15/03/2023, 15 de marzo\n• Tipo: fotos, vídeos\nTodo se busca en este dispositivo.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (years.isNotEmpty()) {
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                items(years, key = { it.year }) { y ->
                    AssistChip(onClick = { onPick(y.year.toString()) }, label = { Text("${y.year} · ${count(y.count)}") })
                }
            }
        }
    }
}

/** One quiet line above the grid, only while there is real backup activity or a failure to look at. */
@Composable
private fun BackupGlanceChip(active: Int, failed: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val failing = failed > 0
    val label = buildString {
        if (active > 0) append(if (active == 1) "1 en cola o subiendo" else "$active en cola o subiendo")
        if (active > 0 && failing) append(" · ")
        if (failing) append(if (failed == 1) "1 con error" else "$failed con error")
    }
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = Space.touch),
        shape = CircleShape,
        color = if (failing) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (failing) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            Modifier.padding(horizontal = Space.l, vertical = Space.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Icon(if (failing) Icons.Filled.ErrorOutline else Icons.Filled.CloudSync, null, Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text("Ver respaldo", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToAlbumSheet(
    albums: List<Pair<Long, String>>,
    onPick: (Long) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.imePadding().navigationBarsPadding().padding(horizontal = Space.screen), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text("Añadir a álbum", style = MaterialTheme.typography.titleLarge)
            Text(
                "Los álbumes son virtuales: las fotos no se mueven ni se copian.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (albums.isNotEmpty()) {
                LazyColumn(Modifier.heightIn(max = 240.dp)) {
                    items(albums, key = { it.first }) { (id, name) ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = Space.touch).clickable(role = Role.Button) { onPick(id) }.padding(vertical = Space.s),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Space.l),
                        ) {
                            Icon(Icons.Filled.PhotoAlbum, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 2)
                            Text("Añadir", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            OutlinedTextField(
                value = newName, onValueChange = { newName = it.take(60) }, label = { Text("Nuevo álbum") }, singleLine = true,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().padding(top = Space.s),
            )
            SecondaryButton("Crear y añadir", { onCreate(newName.trim()) }, Modifier.fillMaxWidth(), enabled = newName.isNotBlank())
            Column(Modifier.padding(bottom = Space.l)) {}
        }
    }
}
