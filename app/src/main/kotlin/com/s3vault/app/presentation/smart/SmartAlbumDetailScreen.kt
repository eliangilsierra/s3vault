package com.s3vault.app.presentation.smart

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.s3vault.app.domain.library.GroupBy
import com.s3vault.app.presentation.GridSkeleton
import com.s3vault.app.presentation.MediaCell
import com.s3vault.app.presentation.delete.DeleteHost
import com.s3vault.app.presentation.delete.DeleteViewModel
import com.s3vault.app.presentation.formatCount
import com.s3vault.app.presentation.library.CellSkeleton
import com.s3vault.app.presentation.library.LibraryGrid
import com.s3vault.app.presentation.library.SectionHeading
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.presentation.ui.AppTopBar
import com.s3vault.app.presentation.ui.BackButton
import com.s3vault.app.presentation.ui.ChoiceChip
import com.s3vault.app.presentation.ui.EmptyState
import com.s3vault.app.settings.GridDensity
import kotlinx.coroutines.launch

/**
 * The photos of one smart album, on the same paged grid as the gallery (only the cells on screen exist). The album is a live
 * question: when a photo is backed up, deleted or restored it appears or disappears here by itself.
 */
@Composable
fun SmartAlbumDetailScreen(
    albumId: String,
    vm: SmartAlbumDetailViewModel,
    deleteVm: DeleteViewModel,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onOpenCredentials: () -> Unit,
) {
    LaunchedEffect(albumId) { vm.open(albumId) }
    val data by vm.data.collectAsStateWithLifecycle()
    val missing by vm.missing.collectAsStateWithLifecycle()
    val selected by vm.selection.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val uploads by vm.uploads.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting) { vm.clearSelection() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }

    val d = data
    val browse = d?.browse?.collectAsStateWithLifecycle()?.value
    val query = d?.queries?.collectAsStateWithLifecycle()?.value

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selecting) {
                AppTopBar(
                    title = if (selected.size == 1) "1 seleccionada" else "${selected.size} seleccionadas",
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    navigation = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.Close, "Cancelar selección") } },
                    actions = {
                        IconButton(onClick = vm::selectAll) { Icon(Icons.Filled.SelectAll, "Seleccionar todo el álbum") }
                        IconButton(onClick = vm::backupSelected) { Icon(Icons.Filled.CloudUpload, "Respaldar en S3") }
                        IconButton(onClick = { scope.launch { deleteVm.request(vm.selectedDeleteEntries()) } }) {
                            Icon(Icons.Filled.Delete, "Eliminar…", tint = MaterialTheme.colorScheme.error)
                        }
                    },
                )
            } else {
                AppTopBar(
                    d?.album?.title ?: "Álbum inteligente",
                    subtitle = browse?.takeIf { it.ready }?.let { if (it.total == 1) "1 elemento" else "${formatCount(it.total)} elementos" },
                    navigation = { BackButton(onBack) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                missing -> EmptyState(icon = Icons.Filled.Close, title = "Este álbum ya no existe", body = "Pudo eliminarse desde otra pantalla.")
                d == null || browse == null || !browse.ready -> GridSkeleton()
                browse.total == 0 -> EmptyState(
                    icon = d.album.icon.vector(),
                    title = "Ahora no hay nada aquí",
                    body = d.album.subtitle + ". Se rellena solo cuando haya fotos que lo cumplan.",
                )
                else -> {
                    if (!selecting) {
                        Row(Modifier.padding(horizontal = Space.screen, vertical = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            listOf(GroupBy.DAY to "Día", GroupBy.MONTH to "Mes", GroupBy.YEAR to "Año").forEach { (g, label) ->
                                ChoiceChip(query?.group == g, label, { vm.setGroup(g) })
                            }
                        }
                    }
                    Box(Modifier.fillMaxSize()) {
                        LibraryGrid(
                            layout = browse.layout, source = d.window, minCell = GridDensity.MEDIUM.minCellDp.dp, state = gridState,
                            header = { s -> SectionHeading(label = browse.layout.sectionLabel(s), count = browse.layout.sections[s].count) },
                            cell = { _, item ->
                                if (item == null) CellSkeleton() else MediaCell(
                                    item, item.mediaStoreId in selected, selecting, uploads[item.mediaStoreId],
                                    onClick = { if (selecting) vm.toggle(it.mediaStoreId) else onOpen(it.mediaStoreId) },
                                    onLongClick = { vm.toggle(it.mediaStoreId) },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
    DeleteHost(deleteVm, snackbar, onStarted = vm::clearSelection, onOpenCredentials = onOpenCredentials)
}
