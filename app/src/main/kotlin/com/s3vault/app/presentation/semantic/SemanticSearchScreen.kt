package com.s3vault.app.presentation.semantic

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.s3vault.app.data.local.JobState
import com.s3vault.app.domain.semantic.PackStatus
import com.s3vault.app.presentation.MediaCell
import com.s3vault.app.presentation.formatBytes
import com.s3vault.app.presentation.formatCount
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.presentation.ui.AppTopBar
import com.s3vault.app.presentation.ui.BackButton
import com.s3vault.app.presentation.ui.ConfirmDialog
import com.s3vault.app.presentation.ui.PrimaryButton
import com.s3vault.app.presentation.ui.QuietButton
import com.s3vault.app.presentation.ui.SecondaryButton
import com.s3vault.app.presentation.ui.SlimProgress
import com.s3vault.app.presentation.ui.StatusBanner
import com.s3vault.app.presentation.ui.Tone

/**
 * Search by what the pictures show, optional and entirely on this device. It never pretends: with the feature off, the model
 * missing or the index half built it says exactly that, and the name/folder/date search keeps working in every state.
 */
@Composable
fun SemanticSearchScreen(vm: SemanticSearchViewModel, onBack: () -> Unit, onOpenPhoto: (Long) -> Unit, onOpenCredentials: () -> Unit = {}) {
    val s by vm.state.collectAsStateWithLifecycle()
    val v by vm.view.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var query by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }
    LaunchedEffect(query, s.enabled, s.ready) { vm.search(query) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { AppTopBar("Buscar por contenido", subtitle = "Opcional · en tu móvil", navigation = { BackButton(onBack) }) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query, onValueChange = { query = it.take(120) }, singleLine = true, shape = MaterialTheme.shapes.large,
                placeholder = { Text(if (s.enabled && s.ready) "Playa al atardecer, mi perro, recibos…" else "Nombre, carpeta, fecha o tipo") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Borrar búsqueda") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { vm.search(query) }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.screen, vertical = Space.s),
            )
            if (query.isBlank()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        Modifier.widthIn(max = Space.maxContent).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.screen).padding(bottom = Space.xxl),
                        verticalArrangement = Arrangement.spacedBy(Space.m),
                    ) {
                        Setup(s, vm, onDelete = { confirmDelete = true })
                    }
                }
            } else {
                Results(v, s, onOpenPhoto)
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "¿Eliminar el modelo y los vectores?",
            text = "Se borran los ${formatBytes(s.pack.totalBytes)} del modelo y los vectores de tus fotos, y la búsqueda por contenido se desactiva. " +
                "Tus fotos no se tocan. Podrás volver a activarla (y a descargar el modelo) cuando quieras.",
            confirmLabel = "Eliminar", destructive = true, onConfirm = { vm.deleteEverything(); confirmDelete = false }, onDismiss = { confirmDelete = false },
        )
    }
}

// ------------------------------------------------------------------ setup and management

@Composable
private fun Setup(s: SemanticState, vm: SemanticSearchViewModel, onDelete: () -> Unit) {
    val dl = s.download
    val downloading = dl?.state == JobState.RUNNING
    when {
        !s.enabled -> Intro(s, vm)
        s.verifying -> StatusCard("Comprobando el modelo…", "Se verifica la huella de cada archivo antes de usarlo.") { SlimProgress(null) }
        !s.ready && downloading -> StatusCard("Descargando el modelo", "${formatBytes(dl.doneItems * 1024L)} de ${formatBytes(s.pack.totalBytes)}") {
            SlimProgress(if (dl.totalItems > 0) dl.doneItems.toFloat() / dl.totalItems else null)
            SecondaryButton("Cancelar descarga", vm::cancelDownload, Modifier.fillMaxWidth())
            Text("Si la cancelas o se corta la conexión, lo descargado se conserva y continúa desde ahí.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        !s.ready -> ModelCard(s, vm)
        else -> Ready(s, vm, onDelete)
    }
}

@Composable
private fun Intro(s: SemanticState, vm: SemanticSearchViewModel) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text("Encuentra fotos por lo que se ve", style = MaterialTheme.typography.titleMedium)
            Text("«Playa al atardecer», «mi perro», «coches», «recibos», «montañas», «capturas con texto». Es una función opcional y está desactivada.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Fact("Privacidad", "Las fotos, sus vectores y tus búsquedas nunca salen del móvil: no hay servicio de IA ni servidor. Solo se descarga el modelo, una vez.")
            Fact("Espacio", "Modelo: ${formatBytes(s.pack.totalBytes)}. Índice: unos 0,5 KB por foto (≈ 10 MB por cada 20.000). Puedes borrarlos cuando quieras.")
            Fact("Batería", "Indexar la biblioteca son de décimas de segundo a un segundo por foto según el móvil. Por defecto solo se hace cargando y con batería no baja.")
            Fact("Límites", "Es aproximado: entiende mejor el inglés que el español (se traducen palabras comunes y se te muestra la búsqueda real), y puede mezclar fotos parecidas.")
            PrimaryButton("Activar la búsqueda por contenido", vm::enable, Modifier.fillMaxWidth())
        }
    }
    Text("Mientras tanto, la búsqueda por nombre, carpeta, fecha y tipo funciona como siempre: escribe arriba.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Fact(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ModelCard(s: SemanticState, vm: SemanticSearchViewModel) {
    val dl = s.download
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text("Falta descargar el modelo", style = MaterialTheme.typography.titleMedium)
            Text(
                "${s.pack.title} · ${formatBytes(s.pack.totalBytes)} · licencia ${s.pack.license}. Se descarga de ${s.pack.host} (${s.pack.origin}) " +
                    "y se comprueba la huella SHA-256 de cada archivo antes de usarlo. Esa descarga no envía nada tuyo.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (dl != null && dl.state == JobState.NEEDS_ATTENTION) StatusBanner(Tone.Warning, "La descarga no terminó", dl.lastError)
            if (dl != null && dl.state == JobState.RETRY_LATER) StatusBanner(Tone.Info, "Sin conexión por ahora", dl.lastError ?: "Se reintentará sola; lo descargado se conserva.")
            if (s.status == PackStatus.PARTIAL) Text("Hay una descarga a medias: continuará desde donde se quedó.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SwitchLine("Descargar solo con Wi-Fi", s.wifiOnly, vm::setWifiOnly)
            PrimaryButton("Descargar el modelo (${formatBytes(s.pack.totalBytes)})", vm::startDownload, Modifier.fillMaxWidth(), icon = Icons.Filled.Download)
            QuietButton("Desactivar la función", vm::disable)
        }
    }
}

@Composable
private fun Ready(s: SemanticState, vm: SemanticSearchViewModel, onDelete: () -> Unit) {
    val running = s.indexRun?.state == JobState.RUNNING
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(
                when {
                    running -> "Indexando tus fotos…"
                    s.pending > 0 -> "Índice incompleto"
                    else -> "Búsqueda por contenido lista"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "${formatCount(s.searchable)} de ${formatCount(s.images)} fotos se pueden buscar" + if (s.pending > 0) " · quedan ${formatCount(s.pending)}" else "",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (s.images > 0) SlimProgress(s.searchable.toFloat() / s.images)
            if (s.pending > 0) {
                Text(
                    if (s.onlyCharging) "Se completa sola cuando el móvil esté cargando y con batería no baja." else "Se completa sola cuando la batería no esté baja.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (running) SecondaryButton("Pausar", vm::pauseIndexing, Modifier.fillMaxWidth())
                else SecondaryButton("Indexar ahora", vm::indexNow, Modifier.fillMaxWidth())
            }
            SwitchLine("Indexar solo cargando", s.onlyCharging, vm::setOnlyCharging)
        }
    }
    var manage by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Space.touch).clickable(role = Role.Button, onClickLabel = if (manage) "Ocultar" else "Mostrar") { manage = !manage },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Datos y gestión", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(if (manage) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (manage) Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(
                "Modelo: ${formatBytes(s.pack.totalBytes)} (${s.pack.title}, licencia ${s.pack.license}). Vectores: ${formatBytes(s.indexBytes)}. Todo está solo en este dispositivo.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SecondaryButton("Reconstruir el índice", vm::rebuild, Modifier.fillMaxWidth())
            SecondaryButton("Borrar los vectores", vm::deleteVectors, Modifier.fillMaxWidth())
            QuietButton("Desactivar la función", vm::disable)
            QuietButton("Eliminar el modelo y los vectores…", onDelete, destructive = true)
        }
    }
}

@Composable
private fun StatusCard(title: String, body: String, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
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

// ------------------------------------------------------------------ results

@Composable
private fun Results(v: SearchView, s: SemanticState, onOpen: (Long) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(112.dp), modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (!s.enabled) item(span = { GridItemSpan(maxLineSpan) }) {
            Note("Solo se busca por nombre, carpeta, fecha y tipo. Activa la búsqueda por contenido para encontrar fotos por lo que se ve.")
        } else if (!s.ready) item(span = { GridItemSpan(maxLineSpan) }) {
            Note("La búsqueda por contenido necesita el modelo (aún no está en el dispositivo). Mientras tanto se muestran las coincidencias por nombre, carpeta y fecha.")
        } else {
            if (v.ranAs != null) item(span = { GridItemSpan(maxLineSpan) }) { Note("Se ha buscado como: «${v.ranAs}» (el modelo entiende mejor el inglés).") }
            if (v.semanticUsed && v.pending > 0) item(span = { GridItemSpan(maxLineSpan) }) {
                Note("Índice incompleto: se busca en ${formatCount(v.searchable)} fotos; faltan ${formatCount(v.pending)}. Los resultados pueden estar incompletos.")
            }
            if (v.searching && v.semantic.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { SlimProgress(null, Modifier.padding(Space.screen)) }
            v.error?.let { e -> item(span = { GridItemSpan(maxLineSpan) }) { Note(e) } }
            if (v.semanticUsed) {
                item(span = { GridItemSpan(maxLineSpan) }) { Heading("Por contenido", v.semantic.size, "Ordenadas de más a menos parecidas. Es aproximado: puede incluir fotos que no coinciden.") }
                items(v.semantic, key = { "s-${it.mediaStoreId}" }) { m -> MediaCell(m, false, false, null, { onOpen(it.mediaStoreId) }, { onOpen(it.mediaStoreId) }) }
            }
        }
        if (v.byMetadata.isNotEmpty() || (!v.searching && v.semanticUsed.not())) {
            item(span = { GridItemSpan(maxLineSpan) }) { Heading("Por nombre, carpeta, fecha o tipo", v.byMetadata.size, null) }
            items(v.byMetadata, key = { "m-${it.mediaStoreId}" }) { m -> MediaCell(m, false, false, null, { onOpen(it.mediaStoreId) }, { onOpen(it.mediaStoreId) }) }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Space.screen, vertical = Space.s))
}

@Composable
private fun Heading(title: String, n: Int, note: String?) {
    Column(Modifier.padding(start = Space.screen, end = Space.screen, top = Space.l, bottom = Space.xs)) {
        Text("$title · ${if (n == 1) "1 resultado" else "$n resultados"}", style = MaterialTheme.typography.titleSmall)
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
