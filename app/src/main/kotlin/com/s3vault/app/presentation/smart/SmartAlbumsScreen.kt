package com.s3vault.app.presentation.smart

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.s3vault.app.domain.MediaKind
import com.s3vault.app.domain.library.DateRange
import com.s3vault.app.domain.smart.SmartAlbum
import com.s3vault.app.domain.smart.SmartBackup
import com.s3vault.app.domain.smart.SmartFilter
import com.s3vault.app.domain.smart.SmartIcon
import com.s3vault.app.presentation.formatAge
import com.s3vault.app.presentation.formatCount
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.presentation.ui.AppTopBar
import com.s3vault.app.presentation.ui.BackButton
import com.s3vault.app.presentation.ui.ChoiceChip
import com.s3vault.app.presentation.ui.ConfirmDialog
import com.s3vault.app.presentation.ui.GroupDivider
import com.s3vault.app.presentation.ui.PrimaryButton
import com.s3vault.app.presentation.ui.SectionHeader
import com.s3vault.app.presentation.ui.SettingsGroup
import com.s3vault.app.presentation.ui.SettingsRow
import com.s3vault.app.presentation.ui.StatusBanner
import com.s3vault.app.presentation.ui.Tone
import kotlinx.coroutines.delay

fun SmartIcon.vector(): ImageVector = when (this) {
    SmartIcon.RECENT -> Icons.Filled.Schedule
    SmartIcon.FAVORITE -> Icons.Filled.Favorite
    SmartIcon.SCREENSHOT -> Icons.Filled.Screenshot
    SmartIcon.VIDEO -> Icons.Filled.Videocam
    SmartIcon.LARGE -> Icons.Filled.Storage
    SmartIcon.NOT_BACKED_UP -> Icons.Filled.CloudOff
    SmartIcon.RECOVERABLE -> Icons.Filled.CleaningServices
    SmartIcon.RESTORED -> Icons.Filled.Restore
    SmartIcon.METADATA -> Icons.Filled.HelpOutline
    SmartIcon.YEAR -> Icons.Filled.CalendarMonth
    SmartIcon.CUSTOM -> Icons.Filled.AutoAwesome
}

private fun items(n: Int) = if (n == 1) "1 elemento" else "${formatCount(n)} elementos"

/**
 * Every smart album in one place: the ones the app defines, the years that have photos, the ones the user made, and a few over the
 * S3 listing. They are questions about metadata, so they never need maintaining. Cloud counts come from the saved listing and are
 * labelled with its age and completeness instead of being presented as the whole bucket.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartAlbumsScreen(
    vm: SmartAlbumsViewModel,
    onBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenCloud: (CloudAlbum) -> Unit,
) {
    val home by vm.home.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<SmartAlbum?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SmartAlbum?>(null) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { AppTopBar("Álbumes inteligentes", subtitle = "Se rellenan solos con lo que ya sabe la app", navigation = { BackButton(onBack) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                Icon(Icons.Filled.Add, "Crear álbum inteligente")
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = Space.maxContent).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen).padding(bottom = 96.dp)) {
                if (home.custom.isNotEmpty()) {
                    SectionHeader("Tus álbumes inteligentes")
                    SettingsGroup {
                        home.custom.forEachIndexed { i, a ->
                            if (i > 0) GroupDivider()
                            SettingsRow(
                                title = a.album.title, subtitle = items(a.count), icon = a.album.icon.vector(), onClick = { onOpenAlbum(a.album.id) },
                                trailing = {
                                    Row {
                                        IconButton(onClick = { editing = a.album }) { Icon(Icons.Filled.Edit, "Editar ${a.album.title}") }
                                        IconButton(onClick = { deleting = a.album }) { Icon(Icons.Filled.Delete, "Eliminar ${a.album.title}") }
                                    }
                                },
                            )
                        }
                    }
                }
                SectionHeader("En este dispositivo")
                SettingsGroup {
                    home.builtIn.forEachIndexed { i, a ->
                        if (i > 0) GroupDivider()
                        SettingsRow(
                            title = a.album.title, subtitle = if (a.count == 0) "Vacío · ${a.album.subtitle}" else "${items(a.count)} · ${a.album.subtitle}",
                            icon = a.album.icon.vector(), onClick = { onOpenAlbum(a.album.id) },
                        )
                    }
                }
                if (home.years.isNotEmpty()) {
                    SectionHeader("Por año")
                    SettingsGroup {
                        home.years.forEachIndexed { i, a ->
                            if (i > 0) GroupDivider()
                            SettingsRow(title = a.album.title, subtitle = items(a.count), icon = a.album.icon.vector(), onClick = { onOpenAlbum(a.album.id) })
                        }
                    }
                }
                SectionHeader("En Amazon S3")
                if (!home.configured) {
                    Text("Configura S3 para ver álbumes de tu bucket.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val l = home.listing
                    StatusBanner(
                        tone = if (l == null || !l.complete) Tone.Warning else Tone.Info,
                        title = when {
                            l == null -> "Aún no hay lista de S3"
                            !l.complete -> "La última lista quedó incompleta"
                            else -> "Según la lista guardada ${formatAge(l.atMs)}"
                        },
                        body = if (l == null) "Abre la pestaña S3 y actualiza la lista para que estos álbumes tengan datos."
                        else "Estos recuentos salen de la lista descargada (${formatCount(l.count)} objetos), no de una consulta a S3. Pueden no reflejar cambios recientes${if (!l.complete) " y la lista no llegó al final" else ""}.",
                    )
                    SettingsGroup(Modifier.padding(top = Space.s)) {
                        home.cloud.forEachIndexed { i, a ->
                            if (i > 0) GroupDivider()
                            SettingsRow(title = a.title, subtitle = "${items(a.count)} en la lista · ${a.subtitle}", icon = cloudIcon(a.id), onClick = { onOpenCloud(a) })
                        }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        val base = editing
        AlbumEditorSheet(
            initialName = base?.title.orEmpty(), initial = base?.filter ?: SmartFilter(), vm = vm,
            onSave = { name, f -> vm.save(base?.id?.removePrefix("custom:")?.toLongOrNull(), name, f); creating = false; editing = null },
            onDismiss = { creating = false; editing = null },
        )
    }
    deleting?.let { a ->
        ConfirmDialog(
            title = "¿Eliminar «${a.title}»?", text = "Solo se elimina la definición del álbum. Las fotos no se tocan, ni en el dispositivo ni en S3.",
            confirmLabel = "Eliminar álbum", destructive = true, onConfirm = { vm.delete(a); deleting = null }, onDismiss = { deleting = null },
        )
    }
}

private fun cloudIcon(id: String): ImageVector = when (id) {
    "cloud:videos" -> Icons.Filled.Videocam
    "cloud:large" -> Icons.Filled.Storage
    "cloud:undated" -> Icons.Filled.CalendarMonth
    else -> Icons.Filled.CloudOff
}

/**
 * Builds a smart album from metadata filters. The count under the form is the real number of photos that match right now, so
 * the album is never saved blind. Every control narrows the album; with none it matches everything on the device.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AlbumEditorSheet(initialName: String, initial: SmartFilter, vm: SmartAlbumsViewModel, onSave: (String, SmartFilter) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    var f by remember { mutableStateOf(initial) }
    var preview by remember { mutableIntStateOf(-1) }
    val folders by vm.folders.collectAsStateWithLifecycle()
    val years by vm.years.collectAsStateWithLifecycle()
    LaunchedEffect(f) { delay(250); preview = vm.previewCount(f) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.imePadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen).padding(bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Text(if (initialName.isEmpty()) "Nuevo álbum inteligente" else "Editar álbum", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(60) }, label = { Text("Nombre") }, singleLine = true, shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth(),
            )
            Group("Tipo") {
                ChoiceChip(f.kinds == setOf(MediaKind.IMAGE), "Fotos", { f = f.copy(kinds = if (f.kinds == setOf(MediaKind.IMAGE)) emptySet() else setOf(MediaKind.IMAGE)) })
                ChoiceChip(f.kinds == setOf(MediaKind.VIDEO), "Vídeos", { f = f.copy(kinds = if (f.kinds == setOf(MediaKind.VIDEO)) emptySet() else setOf(MediaKind.VIDEO)) })
            }
            SwitchLine("Solo favoritas", f.favorites) { f = f.copy(favorites = it) }
            SwitchLine("Solo capturas de pantalla", f.screenshots) { f = f.copy(screenshots = it) }
            Group("Tamaño mínimo") {
                listOf(null to "Cualquiera", 5L to "5 MB", 25L to "25 MB", 100L to "100 MB").forEach { (mb, label) ->
                    ChoiceChip(f.minSizeBytes == mb?.times(1024 * 1024), label, { f = f.copy(minSizeBytes = mb?.times(1024 * 1024)) })
                }
            }
            Group("Añadidas en los últimos") {
                listOf(null to "Siempre", 7 to "7 días", 30 to "30 días", 90 to "90 días", 365 to "1 año").forEach { (d, label) ->
                    ChoiceChip(f.addedWithinDays == d, label, { f = f.copy(addedWithinDays = d) })
                }
            }
            if (years.isNotEmpty()) Group("Año de la foto") {
                ChoiceChip(f.dateRange == null, "Cualquiera", { f = f.copy(dateRange = null) })
                years.forEach { y -> ChoiceChip(f.dateRange?.fromDay == y * 10_000 + 101, y.toString(), { f = f.copy(dateRange = DateRange(y * 10_000 + 101, y * 10_000 + 1231)) }) }
            }
            Group("Respaldo") {
                listOf(null to "Cualquiera", SmartBackup.NOT_BACKED_UP to "Sin respaldar", SmartBackup.BACKED_UP to "Respaldadas").forEach { (b, label) ->
                    ChoiceChip(f.backup == b, label, { f = f.copy(backup = b) })
                }
            }
            SwitchLine("Solo las listas para liberar espacio", f.recoverable) { f = f.copy(recoverable = it) }
            SwitchLine("Solo las sin fecha o sin datos completos", f.missingMetadata) { f = f.copy(missingMetadata = it) }
            if (folders.isNotEmpty()) Group("Carpeta") {
                folders.take(12).forEach { fc -> ChoiceChip(fc.name in f.folders, fc.name, { f = f.copy(folders = if (fc.name in f.folders) f.folders - fc.name else f.folders + fc.name) }) }
            }
            OutlinedTextField(
                value = f.nameContains, onValueChange = { f = f.copy(nameContains = it.take(40)) }, label = { Text("El nombre contiene") }, singleLine = true,
                shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
            )
            Text(
                when {
                    preview < 0 -> "Calculando…"
                    f.isEmpty -> "Sin condiciones: incluiría todo (${items(preview)})."
                    else -> "Ahora coinciden ${items(preview)}. Se actualiza solo cuando cambien tus fotos."
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryButton("Guardar álbum", { onSave(name, f) }, Modifier.fillMaxWidth(), enabled = name.isNotBlank())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) { content() }
    }
}

@Composable
private fun SwitchLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.touch).clickable(role = Role.Switch) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
