package com.s3vault.app.presentation.privacy

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.s3vault.app.data.privacy.PrivateImageCache
import com.s3vault.app.domain.privacy.PrivateAlbum
import com.s3vault.app.domain.privacy.PrivateItem
import com.s3vault.app.domain.privacy.PrivateSession
import com.s3vault.app.domain.privacy.VaultAvailability
import com.s3vault.app.presentation.LoadingBox
import com.s3vault.app.presentation.formatBytes
import com.s3vault.app.presentation.theme.PhotoOverlay
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.presentation.ui.AppTopBar
import com.s3vault.app.presentation.ui.BackButton
import com.s3vault.app.presentation.ui.ChoiceChip
import com.s3vault.app.presentation.ui.ConfirmDialog
import com.s3vault.app.presentation.ui.EmptyState
import com.s3vault.app.presentation.ui.GroupDivider
import com.s3vault.app.presentation.ui.PrimaryButton
import com.s3vault.app.presentation.ui.QuietButton
import com.s3vault.app.presentation.ui.SecondaryButton
import com.s3vault.app.presentation.ui.SectionHeader
import com.s3vault.app.presentation.ui.SettingsGroup
import com.s3vault.app.presentation.ui.SettingsRow
import com.s3vault.app.presentation.ui.SlimProgress
import com.s3vault.app.presentation.ui.StatusBanner
import com.s3vault.app.presentation.ui.Tone
import com.s3vault.app.presentation.viewer.ViewerAction
import com.s3vault.app.presentation.viewer.ZoomableBox

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Keeps this window out of screenshots, screen recordings and the recent-apps preview while a private screen is shown. [enabled] is
 * false only in instrumented tests that need to take screenshots.
 */
@Composable
internal fun SecureWindow(enabled: Boolean) {
    val ctx = LocalContext.current
    DisposableEffect(enabled) {
        val window = ctx.findActivity()?.window
        if (enabled) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (enabled) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private fun photos(n: Int) = if (n == 1) "1 foto" else "$n fotos"

/** What a private screen shows the instant the vault locks, before navigation takes the person back: nothing of the content. */
@Composable
private fun LockedPlaceholder(secure: Boolean, onBack: () -> Unit) {
    SecureWindow(secure)
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { AppTopBar("Álbumes privados", navigation = { BackButton(onBack) }) }) { padding ->
        Box(Modifier.padding(padding)) {
            EmptyState(icon = Icons.Filled.Lock, title = "Bloqueado", body = "Vuelve a «Álbumes privados» para desbloquear.")
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------- the hub

private val AutoLockLabels = listOf(0L to "Al salir de la app", PrivateSession.CHOICES_MS[1] to "1 minuto", PrivateSession.CHOICES_MS[2] to "5 minutos")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrivateHubScreen(vm: PrivateHubViewModel, secure: Boolean, onBack: () -> Unit, onOpenAlbum: (Long) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val ctx = LocalContext.current
    var newAlbum by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PrivateAlbum?>(null) }
    var deleting by remember { mutableStateOf<PrivateAlbum?>(null) }
    var resetting by remember { mutableStateOf(false) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    SecureWindow(secure)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                "Álbumes privados",
                subtitle = if (state.unlocked) "Abiertos" else "Cifrados con la clave de este dispositivo",
                navigation = { BackButton(onBack) },
                actions = { if (state.unlocked) IconButton(onClick = vm::lockNow) { Icon(Icons.Filled.Lock, "Bloquear ahora") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            when {
                state.availability == null -> LoadingBox()
                state.availability == VaultAvailability.NEEDS_SCREEN_LOCK -> EmptyState(
                    icon = Icons.Filled.Lock, title = "Necesitas un bloqueo de pantalla",
                    body = "Los álbumes privados se abren con tu huella, PIN, patrón o contraseña. Este dispositivo no tiene ninguno, así que no puedo protegerlos. Actívalo en los ajustes de Android y vuelve.",
                ) {
                    PrimaryButton("Abrir ajustes de seguridad", { ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }, Modifier.fillMaxWidth())
                }
                state.availability == VaultAvailability.KEY_LOST -> EmptyState(
                    icon = Icons.Filled.Lock, tone = Tone.Error, title = "La clave de los álbumes privados se perdió",
                    body = "Android ya no tiene la clave que abría estas fotos (por ejemplo, porque se quitó el bloqueo de pantalla, se restauró el móvil o se borraron datos de la app). " +
                        "Los archivos cifrados no se pueden recuperar. Puedes eliminarlos para liberar espacio y empezar de nuevo.",
                ) {
                    PrimaryButton("Eliminar archivos protegidos", { resetting = true }, Modifier.fillMaxWidth())
                }
                state.availability == VaultAvailability.NOT_CREATED -> Intro(busy = state.busy, onCreate = vm::unlock)
                !state.unlocked -> EmptyState(
                    icon = Icons.Filled.Lock, title = "Álbumes privados bloqueados",
                    body = "Verifica tu identidad para ver tus fotos protegidas.",
                ) {
                    PrimaryButton("Desbloquear", vm::unlock, Modifier.fillMaxWidth(), icon = Icons.Filled.LockOpen, loading = state.busy)
                }
                else -> Column(
                    Modifier.widthIn(max = Space.maxContent).fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = Space.screen).padding(bottom = Space.xxl),
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    val total = state.albums.sumOf { it.items }
                    Text(
                        if (state.albums.isEmpty()) "Todavía no hay álbumes." else "${if (state.albums.size == 1) "1 álbum" else "${state.albums.size} álbumes"} · ${photos(total)} · ${formatBytes(state.albums.sumOf { it.sealedBytes })} cifrados",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.s),
                    )
                    if (state.albums.isEmpty()) {
                        Text(
                            "Crea un álbum y, desde la galería, selecciona fotos y elige «Mover a álbum privado».",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else SettingsGroup {
                        state.albums.forEachIndexed { i, a ->
                            if (i > 0) GroupDivider()
                            AlbumRow(a, onOpen = { onOpenAlbum(a.id) }, onRename = { renaming = a }, onDelete = { deleting = a })
                        }
                    }
                    SecondaryButton("Nuevo álbum", { newAlbum = true }, Modifier.fillMaxWidth(), icon = Icons.Filled.Add)

                    SectionHeader("Bloqueo automático")
                    Text(
                        "Se bloquea al apagar la pantalla y cuando la app está fuera de primer plano más tiempo que esto.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        AutoLockLabels.forEach { (ms, label) -> ChoiceChip(state.autoLockMs == ms, label, { vm.setAutoLock(ms) }) }
                    }

                    SectionHeader("Cómo funciona")
                    HowItWorks()
                }
            }
        }
    }

    if (newAlbum) NameDialog("Nuevo álbum privado", "Crear", "", onConfirm = { vm.createAlbum(it); newAlbum = false }, onDismiss = { newAlbum = false })
    renaming?.let { a -> NameDialog("Renombrar álbum", "Guardar", a.name, onConfirm = { vm.renameAlbum(a.id, it); renaming = null }, onDismiss = { renaming = null }) }
    deleting?.let { a ->
        ConfirmDialog(
            title = "¿Eliminar «${a.name}»?",
            text = if (a.items == 0) "El álbum está vacío." else "Se eliminarán para siempre ${photos(a.items)} cifradas de este álbum. No hay papelera ni copia: si no quieres perderlas, ábrelo antes y usa «Sacar del álbum».",
            confirmLabel = "Eliminar", destructive = true,
            onConfirm = { vm.deleteAlbum(a.id, a.name); deleting = null }, onDismiss = { deleting = null },
        )
    }
    if (resetting) {
        ConfirmDialog(
            title = "¿Eliminar los archivos protegidos?",
            text = "Ya no se pueden abrir porque falta su clave. Se borrarán del móvil para siempre y podrás crear álbumes privados nuevos.",
            confirmLabel = "Eliminar", destructive = true,
            onConfirm = { vm.resetLostVault(); resetting = false }, onDismiss = { resetting = false },
        )
    }
}

@Composable
private fun AlbumRow(a: PrivateAlbum, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    SettingsRow(
        title = a.name, subtitle = "${photos(a.items)} · ${formatBytes(a.sealedBytes)}", icon = Icons.Filled.PhotoAlbum, onClick = onOpen,
        trailing = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Más acciones de ${a.name}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Renombrar") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Eliminar álbum…") }, onClick = { menu = false; onDelete() })
                }
            }
        },
    )
}

@Composable
private fun Intro(busy: Boolean, onCreate: () -> Unit) {
    Column(
        Modifier.widthIn(max = Space.maxContent).fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = Space.screen).padding(bottom = Space.xxl),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Text("Fotos que solo se abren contigo", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = Space.l))
        Text(
            "Copia fotos a un álbum cifrado con una clave que vive en este dispositivo y solo se usa después de verificar tu identidad con huella, PIN, patrón o contraseña.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HowItWorks()
        StatusBanner(
            tone = Tone.Warning, title = "No hay forma de recuperar la clave",
            body = "Si quitas el bloqueo de pantalla, restableces el móvil o borras los datos de la app, las fotos cifradas se pierden para siempre. No hay servidor ni copia de la clave. Antes de cambiar de móvil usa «Sacar del álbum».",
        )
        PrimaryButton("Crear álbumes privados", onCreate, Modifier.fillMaxWidth(), icon = Icons.Filled.LockOpen, loading = busy)
    }
}

@Composable
private fun HowItWorks() {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Point("Qué hace", "Copia cada foto cifrada (AES-256-GCM, con detección de cualquier alteración) en el almacenamiento privado de la app, con su miniatura y sus datos también cifrados.")
        Point("Qué no hace", "No toca tus originales ni los borra sin que lo confirmes aparte. La copia que ya esté en Amazon S3 no se cifra ni se borra: la decides tú.")
        Point("Dónde no aparecen", "Las fotos protegidas no salen en la galería, la búsqueda, los duplicados ni los álbumes, y no se respaldan en S3. Los vídeos todavía no se pueden proteger.")
        Point("Qué no cubre", "No protege frente a un móvil con root, ni frente a un programa malicioso mientras los álbumes están abiertos. Las pantallas privadas no admiten capturas.")
    }
}

@Composable
private fun Point(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NameDialog(title: String, confirm: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(60) }, label = { Text("Nombre") }, singleLine = true,
                shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
        },
        confirmButton = { QuietButton(confirm, { onConfirm(name.trim()) }, enabled = name.isNotBlank()) },
        dismissButton = { QuietButton("Cancelar", onDismiss) },
    )
}

// ---------------------------------------------------------------------------------------------------------------- one album

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateAlbumScreen(
    vm: PrivateAlbumViewModel, cache: PrivateImageCache, albumId: Long, unlocked: Boolean, secure: Boolean,
    onBack: () -> Unit, onOpen: (Long) -> Unit,
) {
    if (!unlocked) { LockedPlaceholder(secure, onBack); return }
    LaunchedEffect(albumId) { vm.open(albumId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current
    var confirmOut by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var moveSheet by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }
    SecureWindow(secure)
    val selectionMode = state.selected.isNotEmpty()
    BackHandler(enabled = selectionMode) { vm.clearSelection() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (selectionMode) AppTopBar(
                title = if (state.selected.size == 1) "1 seleccionada" else "${state.selected.size} seleccionadas",
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                navigation = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.Close, "Cancelar selección") } },
                actions = {
                    IconButton(onClick = vm::selectAll) { Icon(Icons.Filled.SelectAll, "Seleccionar todo") }
                    IconButton(onClick = { confirmOut = true }) { Icon(Icons.Filled.SaveAlt, "Sacar del álbum") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Eliminar…", tint = MaterialTheme.colorScheme.error) }
                    Box {
                        IconButton(onClick = { overflow = true }) { Icon(Icons.Filled.MoreVert, "Más acciones") }
                        DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                            DropdownMenuItem(
                                text = { Text("Mover a otro álbum") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null) },
                                enabled = state.albums.size > 1, onClick = { overflow = false; moveSheet = true },
                            )
                        }
                    }
                },
            ) else AppTopBar(
                title = state.name ?: "Álbum privado",
                subtitle = if (state.loaded) photos(state.items.size) else null,
                navigation = { BackButton(onBack) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.working?.let { w ->
                Column(Modifier.padding(horizontal = Space.screen, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(w, style = MaterialTheme.typography.labelLarge)
                    SlimProgress(null)
                }
            }
            when {
                !state.loaded -> LoadingBox()
                state.items.isEmpty() -> EmptyState(
                    icon = Icons.Filled.PhotoAlbum, title = "Este álbum está vacío",
                    body = "En la galería, selecciona fotos y elige «Mover a álbum privado». Se copian cifradas; tus originales no se tocan hasta que lo confirmes.",
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp), modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = Space.xl),
                ) {
                    items(state.items, key = { it.id }) { item ->
                        PrivateCell(
                            item, item.id in state.selected, selectionMode, cache,
                            onClick = { if (selectionMode) vm.toggle(item.id) else onOpen(item.id) },
                            onLongClick = {
                                if (!selectionMode) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                vm.toggle(item.id)
                            },
                        )
                    }
                }
            }
        }
    }

    if (confirmOut) {
        ConfirmDialog(
            title = "¿Sacar ${photos(state.selected.size)} del álbum privado?",
            text = "Se guardarán en la galería (Pictures/S3Vault), sin cifrar, visibles para otras apps y respaldables en S3. Cada foto sale del álbum solo después de comprobar que su copia en la galería es idéntica.",
            confirmLabel = "Sacar", onConfirm = { confirmOut = false; vm.moveOut() }, onDismiss = { confirmOut = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "¿Eliminar ${photos(state.selected.size)} para siempre?",
            text = "Se borran del álbum privado sin papelera ni copia. Si alguna tenía una copia en la galería o en S3, esa no se toca.",
            confirmLabel = "Eliminar", destructive = true, onConfirm = { confirmDelete = false; vm.deleteSelected() }, onDismiss = { confirmDelete = false },
        )
    }
    if (moveSheet) {
        ModalBottomSheet(onDismissRequest = { moveSheet = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = Space.screen).padding(bottom = Space.l), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text("Mover a otro álbum privado", style = MaterialTheme.typography.titleLarge)
                state.albums.filter { it.id != albumId }.forEach { a ->
                    SettingsRow(title = a.name, subtitle = photos(a.items), icon = Icons.Filled.PhotoAlbum, onClick = { moveSheet = false; vm.moveSelectedTo(a.id, a.name) })
                }
            }
        }
    }
}

@Composable
private fun PrivateCell(
    item: PrivateItem, selected: Boolean, selectionMode: Boolean, cache: PrivateImageCache,
    onClick: () -> Unit, onLongClick: () -> Unit,
) {
    var bmp by remember(item.id) { mutableStateOf(cache.cachedThumbnail(item.id)) }
    LaunchedEffect(item.id) { if (bmp == null) bmp = cache.thumbnail(item, 360) }
    Box(
        Modifier.aspectRatio(1f).background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .semantics(mergeDescendants = true) { contentDescription = item.meta.name; this.selected = selected }
            .combinedClickable(
                onClickLabel = if (selectionMode) "Alternar selección" else "Abrir", onClick = onClick,
                onLongClickLabel = "Seleccionar", onLongClick = onLongClick,
            ),
    ) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().padding(if (selected) 10.dp else 0.dp), contentScale = ContentScale.Crop) }
        if (selectionMode) {
            Box(Modifier.align(Alignment.TopStart).padding(6.dp).size(24.dp).background(if (selected) MaterialTheme.colorScheme.primary else PhotoOverlay.scrim, CircleShape), contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------- viewer

@Composable
fun PrivateViewerScreen(
    vm: PrivateAlbumViewModel, cache: PrivateImageCache, albumId: Long, startItemId: Long, unlocked: Boolean, secure: Boolean, onBack: () -> Unit,
) {
    if (!unlocked) { LockedPlaceholder(secure, onBack); return }
    LaunchedEffect(albumId) { vm.open(albumId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    SecureWindow(secure)
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }

    if (!state.loaded) { Box(Modifier.fillMaxSize().background(Color.Black)) { LoadingBox() }; return }
    val items = state.items
    var hadItems by remember { mutableStateOf(false) }
    LaunchedEffect(items.size) { if (items.isNotEmpty()) hadItems = true else if (hadItems) onBack() }
    if (items.isEmpty()) { Box(Modifier.fillMaxSize().background(Color.Black)); return }

    val pager = rememberPagerState(initialPage = items.indexOfFirst { it.id == startItemId }.coerceAtLeast(0)) { items.size }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var confirmOut by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val current = items.getOrNull(pager.currentPage)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, userScrollEnabled = !zoomed, beyondViewportPageCount = 1, modifier = Modifier.fillMaxSize(), key = { items.getOrNull(it)?.id ?: it }) { page ->
            val item = items.getOrNull(page) ?: return@HorizontalPager
            PrivatePage(item, cache, active = page == pager.currentPage, onZoomChanged = { zoomed = it }, onTap = { chrome = !chrome })
        }
        if (chrome && current != null) {
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(PhotoOverlay.strongScrim, Color.Transparent)))
                    .statusBarsPadding().padding(horizontal = Space.xs, vertical = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = PhotoOverlay.onScrim) }
                Column(Modifier.weight(1f).padding(end = Space.l)) {
                    Text(current.meta.name, color = PhotoOverlay.onScrim, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${pager.currentPage + 1} de ${items.size} · ${formatBytes(current.meta.sizeBytes)}", color = PhotoOverlay.onScrim.copy(alpha = 0.78f), style = MaterialTheme.typography.labelMedium)
                }
            }
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, PhotoOverlay.strongScrim)))
                    .navigationBarsPadding().padding(horizontal = Space.screen, vertical = Space.s),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ViewerAction(Icons.Filled.SaveAlt, "Sacar del álbum") { confirmOut = true }
                ViewerAction(Icons.Filled.Delete, "Eliminar") { confirmDelete = true }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp))
    }

    if (confirmOut && current != null) {
        ConfirmDialog(
            title = "¿Sacar esta foto del álbum privado?",
            text = "Se guardará en la galería (Pictures/S3Vault), sin cifrar y visible para otras apps. Sale del álbum solo después de comprobar que la copia es idéntica.",
            confirmLabel = "Sacar", onConfirm = { confirmOut = false; vm.clearSelection(); vm.toggle(current.id); vm.moveOut() }, onDismiss = { confirmOut = false },
        )
    }
    if (confirmDelete && current != null) {
        ConfirmDialog(
            title = "¿Eliminar esta foto para siempre?",
            text = "Se borra del álbum privado sin papelera ni copia. Si tenía una copia en la galería o en S3, esa no se toca.",
            confirmLabel = "Eliminar", destructive = true, onConfirm = { confirmDelete = false; vm.clearSelection(); vm.toggle(current.id); vm.deleteSelected() }, onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun PrivatePage(item: PrivateItem, cache: PrivateImageCache, active: Boolean, onZoomChanged: (Boolean) -> Unit, onTap: () -> Unit) {
    val result by produceState<Result<android.graphics.Bitmap>?>(null, item.id) {
        value = cache.full(item, 2048)?.let { Result.success(it) } ?: Result.failure(IllegalStateException("no se pudo descifrar"))
    }
    ZoomableBox(active = active, onZoomChanged = onZoomChanged, onTap = onTap) {
        val r = result
        when {
            r == null -> LoadingBox()
            r.isSuccess -> Image(r.getOrThrow().asImageBitmap(), item.meta.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else -> Box(Modifier.fillMaxSize().padding(Space.xl), contentAlignment = Alignment.Center) {
                Text(
                    "No se pudo descifrar esta foto. El archivo puede estar dañado o alterado, así que no se muestra.",
                    color = PhotoOverlay.onScrim, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------- moving in (from the gallery)

/** Drives the "Mover a álbum privado" dialogs. [onDeleteOriginals] hands the verified originals to the regular delete flow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateMoveHost(vm: PrivateMoveViewModel, snackbar: SnackbarHostState, onDeleteOriginals: (List<com.s3vault.app.sync.DeleteEntry>) -> Unit, onOpenPrivate: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }

    when (val s = state) {
        MoveState.Idle -> Unit
        MoveState.NeedsScreenLock -> AlertDialog(
            onDismissRequest = vm::cancel, shape = MaterialTheme.shapes.extraLarge, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Necesitas un bloqueo de pantalla") },
            text = { Text("Los álbumes privados se abren con tu huella, PIN, patrón o contraseña, y este dispositivo no tiene ninguno. Actívalo en los ajustes de Android y vuelve a intentarlo.") },
            confirmButton = { QuietButton("Abrir ajustes", { vm.cancel(); ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }) },
            dismissButton = { QuietButton("Cancelar", vm::cancel) },
        )
        is MoveState.NeedsUnlock -> AlertDialog(
            onDismissRequest = vm::cancel, shape = MaterialTheme.shapes.extraLarge, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(if (s.creating) "Crear álbumes privados" else "Álbumes privados bloqueados") },
            text = {
                Text(
                    if (s.creating) "Se creará una clave en este dispositivo que solo se usa tras verificar tu identidad. Si algún día se pierde (quitar el bloqueo de pantalla, restablecer el móvil), las fotos cifradas no se pueden recuperar."
                    else "Verifica tu identidad para elegir el álbum.",
                )
            },
            confirmButton = { QuietButton(if (s.busy) "Verificando…" else "Verificar identidad", vm::unlock, enabled = !s.busy) },
            dismissButton = { QuietButton("Cancelar", vm::cancel) },
        )
        is MoveState.ChooseAlbum -> {
            var name by remember { mutableStateOf("") }
            ModalBottomSheet(onDismissRequest = vm::cancel, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = Space.screen).padding(bottom = Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text("Mover a álbum privado", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (s.images == 0) "Ninguna de las fotos elegidas se puede proteger: los vídeos todavía no son compatibles."
                        else "Se copian ${photos(s.images)} cifradas. Tus originales no se tocan.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (s.images > 0) {
                        s.albums.forEach { a -> SettingsRow(title = a.name, subtitle = photos(a.items), icon = Icons.Filled.PhotoAlbum, onClick = { vm.chooseAlbum(a) }) }
                        OutlinedTextField(
                            value = name, onValueChange = { name = it.take(60) }, label = { Text("Nuevo álbum privado") }, singleLine = true,
                            shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth(),
                        )
                        SecondaryButton("Crear y elegir", { vm.createAndChoose(name) }, Modifier.fillMaxWidth(), enabled = name.isNotBlank())
                    }
                }
            }
        }
        is MoveState.Confirm -> AlertDialog(
            onDismissRequest = vm::back, shape = MaterialTheme.shapes.extraLarge, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("¿Copiar ${photos(s.images)} a «${s.albumName}»?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text("Se copian cifradas y se comprueba cada copia antes de darla por buena. Los originales NO se borran ni se mueven: seguirán en la galería hasta que tú lo decidas en el paso siguiente.", style = MaterialTheme.typography.bodyMedium)
                    if (s.inCloud > 0) Text("${photos(s.inCloud)} ya tienen copia en Amazon S3. Esa copia no se cifra ni se borra; la decides tú después.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (s.videos > 0) Text("${if (s.videos == 1) "1 vídeo" else "${s.videos} vídeos"} no se puede proteger todavía y se deja donde está.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { QuietButton("Copiar al álbum privado", vm::confirmMove) },
            dismissButton = { QuietButton("Atrás", vm::back) },
        )
        is MoveState.Working -> AlertDialog(
            onDismissRequest = {}, shape = MaterialTheme.shapes.extraLarge, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Copiando al álbum privado…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text("${s.done} de ${s.total}", style = MaterialTheme.typography.bodyMedium)
                    SlimProgress(if (s.total > 0) s.done.toFloat() / s.total else null)
                    Text("Los originales no se tocan.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {},
            dismissButton = { QuietButton("Cancelar", vm::cancel) },
        )
        is MoveState.Done -> AlertDialog(
            onDismissRequest = vm::finish, shape = MaterialTheme.shapes.extraLarge, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(if (s.moved == 0) "No se copió nada" else "${photos(s.moved)} en «${s.albumName}»") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    if (s.moved > 0) Text("Copias cifradas y verificadas. Tus originales siguen en la galería, visibles para cualquiera que abra el móvil: elimínalos si quieres que la foto solo exista en el álbum privado.", style = MaterialTheme.typography.bodyMedium)
                    if (s.failed > 0) Text("${photos(s.failed)} no se pudieron copiar y siguen como estaban.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    if (s.skipped > 0) Text("${s.skipped} omitidos (vídeos o archivos que ya no están).", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (s.stopped) Text("El álbum se bloqueó antes de terminar. Lo copiado hasta entonces es válido.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                if (s.originals.isNotEmpty()) QuietButton("Eliminar originales…", { vm.finish(); onDeleteOriginals(s.originals) })
                else QuietButton("Ver álbumes privados", { vm.finish(); onOpenPrivate() })
            },
            dismissButton = { QuietButton(if (s.originals.isNotEmpty()) "Conservar originales" else "Cerrar", vm::finish) },
        )
    }
}
