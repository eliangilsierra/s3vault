package com.s3vault.app.presentation.nav

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.navigation.NavBackStackEntry
import com.s3vault.app.presentation.ui.rememberReducedMotion
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.s3vault.app.AppContainer
import com.s3vault.app.domain.MediaItem
import com.s3vault.app.presentation.AlbumsViewModel
import com.s3vault.app.presentation.AppViewModelFactory
import com.s3vault.app.presentation.GalleryViewModel
import com.s3vault.app.presentation.LibraryViewerViewModel
import com.s3vault.app.presentation.GeneralSettingsViewModel
import com.s3vault.app.presentation.LoadingBox
import com.s3vault.app.presentation.CloudViewerViewModel
import com.s3vault.app.presentation.RemoteViewModel
import com.s3vault.app.presentation.S3SettingsViewModel
import com.s3vault.app.presentation.SecurityViewModel
import com.s3vault.app.presentation.albums.AlbumDetailScreen
import com.s3vault.app.presentation.albums.AlbumsScreen
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ImageSearch
import com.s3vault.app.presentation.semantic.SemanticSearchScreen
import com.s3vault.app.presentation.semantic.SemanticSearchViewModel
import androidx.compose.material.icons.filled.CleaningServices
import com.s3vault.app.presentation.smart.SmartAlbumDetailScreen
import com.s3vault.app.presentation.smart.SmartAlbumDetailViewModel
import com.s3vault.app.presentation.smart.SmartAlbumsScreen
import com.s3vault.app.presentation.smart.SmartAlbumsViewModel
import com.s3vault.app.presentation.smart.SmartViewerViewModel
import androidx.compose.material.icons.filled.ContentCopy
import com.s3vault.app.presentation.duplicates.DuplicatesScreen
import com.s3vault.app.presentation.duplicates.DuplicatesViewModel
import androidx.compose.material.icons.automirrored.filled.FactCheck
import com.s3vault.app.presentation.space.SpaceRecoveryScreen
import com.s3vault.app.presentation.space.SpaceRecoveryViewModel
import com.s3vault.app.presentation.integrity.IntegrityScreen
import com.s3vault.app.presentation.integrity.IntegrityViewModel
import com.s3vault.app.presentation.tools.Tool
import com.s3vault.app.presentation.tools.ToolsScreen
import com.s3vault.app.presentation.sync.SyncCenterScreen
import com.s3vault.app.presentation.sync.SyncCenterViewModel
import com.s3vault.app.presentation.gallery.GalleryScreen
import com.s3vault.app.presentation.delete.DeleteViewModel
import com.s3vault.app.presentation.remote.RemoteScreen
import com.s3vault.app.presentation.viewer.CloudViewerScreen
import com.s3vault.app.presentation.settings.GeneralSettingsScreen
import com.s3vault.app.presentation.settings.S3SettingsScreen
import com.s3vault.app.presentation.settings.SecurityScreen
import com.s3vault.app.presentation.settings.SettingsHome
import com.s3vault.app.presentation.settings.StorageCostScreen
import com.s3vault.app.presentation.settings.StorageCostViewModel
import com.s3vault.app.presentation.settings.summaryLine
import com.s3vault.app.presentation.viewer.ViewerScreen
import kotlinx.coroutines.launch

private enum class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Gallery("gallery", "Galería", Icons.Outlined.PhotoLibrary, Icons.Filled.PhotoLibrary),
    Albums("albums", "Álbumes", Icons.Outlined.PhotoAlbum, Icons.Filled.PhotoAlbum),
    Backup("backup", "Respaldo", Icons.Outlined.CloudSync, Icons.Filled.CloudSync),
    Remote("remote", "S3", Icons.Outlined.CloudQueue, Icons.Filled.CloudQueue),
    Settings("settings", "Ajustes", Icons.Outlined.Settings, Icons.Filled.Settings),
}

private val TabRoutes = Tab.entries.map { it.route }.toSet()

/** The library tools, in the order the hub shows them. Each entry is a route; the hub never knows what a tool does. */
private val LibraryTools = listOf(
    Tool("sync", "Centro de sincronización", "Qué está en este dispositivo, en S3 o en camino, y por qué algo falló", Icons.Filled.CloudSync, "Respaldo"),
    Tool("integrity", "Auditoría del respaldo", "Comprueba que S3 sigue teniendo lo que respaldaste (solo lee)", Icons.AutoMirrored.Filled.FactCheck, "Respaldo"),
    Tool("semantic", "Buscar por contenido", "Encuentra fotos por lo que se ve. Opcional, en tu móvil, sin enviar nada", Icons.Filled.ImageSearch, "Organizar"),
    Tool("smart", "Álbumes inteligentes", "Favoritas, capturas, vídeos, grandes, sin respaldar, por año… y los tuyos", Icons.Filled.AutoAwesome, "Organizar"),
    Tool("private", "Álbumes privados", "Fotos cifradas que solo se abren con tu huella o PIN. Copia, no mueve ni borra", Icons.Filled.Lock, "Privacidad"),
    Tool("duplicates", "Duplicados y parecidas", "Copias idénticas y fotos probablemente iguales, para elegir cuál conservar", Icons.Filled.ContentCopy, "Este dispositivo"),
    Tool("space", "Liberar espacio", "Quita del móvil lo que S3 ya tiene verificado. Nunca toca S3", Icons.Filled.CleaningServices, "Este dispositivo"),
)

private const val VIEWER = "viewer/{source}/{id}"
private const val ALBUM = "album/{id}"
private const val CLOUD = "cloud/{key}?album={album}"

@Composable
fun AppRoot(container: AppContainer) {
    val nav = rememberNavController()
    val factory = AppViewModelFactory(container)
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = route in TabRoutes

    // The private albums close behind the user's back (timeout, screen off, "bloquear ahora"): whatever private screen is open is
    // replaced by the locked entrance at once, so a stale screen never stays reachable. Restored back stacks after the process died
    // start locked, so they take the same path.
    val unlocked by container.privateSession.unlocked.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(unlocked, route) {
        if (!unlocked && route != null && route.startsWith("private/")) {
            if (!nav.popBackStack("private", inclusive = false)) nav.navigate("private") { popUpTo(route) { inclusive = true } }
        }
    }
    val reduced = rememberReducedMotion()
    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        when {
            reduced -> EnterTransition.None
            // Moving deeper (detail, settings page, viewer): a short fade with a hint of horizontal travel.
            targetState.destination.route !in TabRoutes -> fadeIn(tween(200)) + slideInHorizontally(tween(220)) { it / 14 }
            else -> fadeIn(tween(180))
        }
    }
    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (reduced) ExitTransition.None else fadeOut(tween(120))
    }
    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (reduced) EnterTransition.None else fadeIn(tween(180))
    }
    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        when {
            reduced -> ExitTransition.None
            initialState.destination.route !in TabRoutes -> fadeOut(tween(140)) + slideOutHorizontally(tween(200)) { it / 14 }
            else -> fadeOut(tween(120))
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // Full-screen routes (viewer, settings) manage their own insets.
        // Tab screens own their top bar (which pads for the status bar itself), so only the bottom insets are applied here.
        contentWindowInsets = if (showBar) ScaffoldDefaults.contentWindowInsets.exclude(WindowInsets.statusBars) else WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBar) {
                // Five one-word labels share the bar's width, so the text scale is capped here (and only here):
                // at larger system font sizes the labels would otherwise be clipped. Everything else scales fully.
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = density.fontScale.coerceAtMost(1.15f))) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                    Tab.entries.forEach { tab ->
                        val selected = route == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = { nav.navigateTab(tab.route) },
                            icon = { Icon(if (selected) tab.selectedIcon else tab.icon, null) },
                            label = { Text(tab.label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
                }
            }
        },
    ) { padding ->
        NavHost(
            nav, startDestination = Tab.Gallery.route, modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            enterTransition = enter, exitTransition = exit, popEnterTransition = popEnter, popExitTransition = popExit,
        ) {
            composable(Tab.Gallery.route) {
                val vm: GalleryViewModel = viewModel(factory = factory)
                val deleteVm: DeleteViewModel = viewModel(factory = factory)
                val moveVm: com.s3vault.app.presentation.privacy.PrivateMoveViewModel = viewModel(factory = factory)
                GalleryScreen(
                    vm, deleteVm,
                    moveVm = moveVm, onOpenPrivate = { nav.navigate("private") },
                    onOpen = { id -> nav.navigate("viewer/all/$id") },
                    onOpenBackup = { nav.navigateTab(Tab.Backup.route) },
                    onOpenCredentials = { nav.navigate("settings/security") },
                    onOpenTools = { nav.navigate("tools") },
                )
            }
            composable(Tab.Albums.route) {
                val vm: AlbumsViewModel = viewModel(factory = factory)
                val smartVm: SmartAlbumsViewModel = viewModel(factory = factory)
                val smart by smartVm.home.collectAsStateWithLifecycle()
                AlbumsScreen(
                    vm, onOpenAlbum = { id -> nav.navigate("album/$id") },
                    smart = smart, onOpenSmart = { id -> nav.navigate("smart/" + android.net.Uri.encode(id)) }, onOpenSmartAll = { nav.navigate("smart") },
                )
            }
            composable(ALBUM, listOf(navArgument("id") { type = NavType.LongType })) { e ->
                val vm: AlbumsViewModel = viewModel(factory = factory)
                val deleteVm: DeleteViewModel = viewModel(factory = factory)
                val id = e.arguments!!.getLong("id")
                AlbumDetailScreen(
                    vm, deleteVm, id, onBack = { nav.popBackStack() },
                    onOpen = { mid -> nav.navigate("viewer/album-$id/$mid") },
                    onOpenCloud = { obj -> nav.navigate("cloud/" + android.net.Uri.encode(obj.key) + "?album=$id") },
                    onOpenCredentials = { nav.navigate("settings/security") },
                )
            }
            composable(Tab.Backup.route) {
                val vm: SyncCenterViewModel = viewModel(factory = factory)
                SyncCenterScreen(
                    vm,
                    onOpenS3Settings = { nav.navigate("settings/s3") },
                    onOpenSecurity = { nav.navigate("settings/security") },
                    onOpenPhoto = { id -> nav.navigate("viewer/one/$id") },
                    onOpenCloudPhoto = { key -> nav.navigate("cloud/" + android.net.Uri.encode(key)) },
                )
            }
            composable(Tab.Remote.route) {
                val vm: RemoteViewModel = viewModel(factory = factory)
                val deleteVm: DeleteViewModel = viewModel(factory = factory)
                RemoteScreen(
                    vm, deleteVm,
                    onOpenS3Settings = { nav.navigate("settings/s3") },
                    onOpenSecurity = { nav.navigate("settings/security") },
                    onOpenPhoto = { obj -> nav.navigate("cloud/" + android.net.Uri.encode(obj.key)) },
                )
            }
            composable(
                CLOUD,
                listOf(
                    navArgument("key") { type = NavType.StringType },
                    navArgument("album") { type = NavType.LongType; defaultValue = -1L },
                ),
            ) { e ->
                val vm: RemoteViewModel = viewModel(factory = factory)
                val deleteVm: DeleteViewModel = viewModel(factory = factory)
                val viewerVm: CloudViewerViewModel = viewModel(factory = factory)
                val startKey = e.arguments!!.getString("key")!!
                val fromAlbum = e.arguments!!.getLong("album")
                androidx.compose.runtime.LaunchedEffect(startKey, fromAlbum) {
                    if (fromAlbum >= 0) viewerVm.openAlbum(fromAlbum, startKey) else viewerVm.open(startKey)
                }
                val data = viewerVm.data.collectAsStateWithLifecycle().value
                if (data == null) LoadingBox() else CloudViewerScreen(
                    items = data.source, startIndex = data.startIndex, vm = vm, deleteVm = deleteVm,
                    onBack = { nav.popBackStack() }, onOpenCredentials = { nav.navigate("settings/security") },
                )
            }
            composable("tools") {
                ToolsScreen(LibraryTools, onBack = { nav.popBackStack() }) { id ->
                    when (id) {
                        "sync" -> nav.navigateTab(Tab.Backup.route)
                        else -> nav.navigate(id)
                    }
                }
            }
            composable("private") {
                val vm: com.s3vault.app.presentation.privacy.PrivateHubViewModel = viewModel(factory = factory)
                com.s3vault.app.presentation.privacy.PrivateHubScreen(
                    vm, secure = container.secureWindows, onBack = { nav.popBackStack() }, onOpenAlbum = { id -> nav.navigate("private/$id") },
                )
            }
            composable("private/{albumId}", listOf(navArgument("albumId") { type = NavType.LongType })) { e ->
                val vm: com.s3vault.app.presentation.privacy.PrivateAlbumViewModel = viewModel(factory = factory)
                val albumId = e.arguments!!.getLong("albumId")
                com.s3vault.app.presentation.privacy.PrivateAlbumScreen(
                    vm, container.privateImages, albumId, unlocked = unlocked, secure = container.secureWindows,
                    onBack = { nav.popBackStack() }, onOpen = { item -> nav.navigate("private/$albumId/$item") },
                )
            }
            composable(
                "private/{albumId}/{itemId}",
                listOf(navArgument("albumId") { type = NavType.LongType }, navArgument("itemId") { type = NavType.LongType }),
            ) { e ->
                val vm: com.s3vault.app.presentation.privacy.PrivateAlbumViewModel = viewModel(factory = factory)
                com.s3vault.app.presentation.privacy.PrivateViewerScreen(
                    vm, container.privateImages, e.arguments!!.getLong("albumId"), e.arguments!!.getLong("itemId"),
                    unlocked = unlocked, secure = container.secureWindows, onBack = { nav.popBackStack() },
                )
            }
            composable("semantic") {
                val vm: SemanticSearchViewModel = viewModel(factory = factory)
                SemanticSearchScreen(vm, onBack = { nav.popBackStack() }, onOpenPhoto = { id -> nav.navigate("viewer/one/$id") })
            }
            composable("smart") {
                val vm: SmartAlbumsViewModel = viewModel(factory = factory)
                SmartAlbumsScreen(
                    vm, onBack = { nav.popBackStack() },
                    onOpenAlbum = { id -> nav.navigate("smart/" + android.net.Uri.encode(id)) },
                    onOpenCloud = { a -> vm.openCloud(a); nav.navigateTab(Tab.Remote.route) },
                )
            }
            composable("smart/{id}", listOf(navArgument("id") { type = NavType.StringType })) { e ->
                val vm: SmartAlbumDetailViewModel = viewModel(factory = factory)
                val deleteVm: DeleteViewModel = viewModel(factory = factory)
                val id = e.arguments!!.getString("id")!!
                SmartAlbumDetailScreen(
                    id, vm, deleteVm, onBack = { nav.popBackStack() },
                    onOpen = { mid -> nav.navigate("viewer/smart-" + android.net.Uri.encode(id) + "/$mid") },
                    onOpenCredentials = { nav.navigate("settings/security") },
                )
            }
            composable("duplicates") {
                val vm: DuplicatesViewModel = viewModel(factory = factory)
                val deleteVm: DeleteViewModel = viewModel(factory = factory)
                DuplicatesScreen(vm, deleteVm, onBack = { nav.popBackStack() }, onOpenCredentials = { nav.navigate("settings/security") })
            }
            composable("space") {
                val vm: SpaceRecoveryViewModel = viewModel(factory = factory)
                val deleteVm: DeleteViewModel = viewModel(factory = factory)
                SpaceRecoveryScreen(
                    vm, deleteVm, onBack = { nav.popBackStack() }, onOpenIntegrity = { nav.navigate("integrity") },
                    onOpenCredentials = { nav.navigate("settings/security") },
                )
            }
            composable("integrity") {
                val vm: IntegrityViewModel = viewModel(factory = factory)
                IntegrityScreen(vm, onBack = { nav.popBackStack() }, onOpenS3Settings = { nav.navigate("settings/s3") }, onOpenSecurity = { nav.navigate("settings/security") })
            }
            composable(Tab.Settings.route) {
                val costVm: StorageCostViewModel = viewModel(factory = factory)
                val cost by costVm.state.collectAsStateWithLifecycle()
                SettingsHome(
                    onS3 = { nav.navigate("settings/s3") },
                    onSecurity = { nav.navigate("settings/security") },
                    onGeneral = { nav.navigate("settings/general") },
                    onCosts = { nav.navigate("settings/costs") },
                    onTools = { nav.navigate("tools") },
                    costSummary = cost.summaryLine(),
                )
            }
            composable("settings/costs") {
                val vm: StorageCostViewModel = viewModel(factory = factory)
                StorageCostScreen(
                    vm, onBack = { nav.popBackStack() },
                    onOpenS3Settings = { nav.navigate("settings/s3") }, onOpenSecurity = { nav.navigate("settings/security") },
                )
            }
            composable("settings/s3") {
                val vm: S3SettingsViewModel = viewModel(factory = factory)
                S3SettingsScreen(vm, onBack = { nav.popBackStack() }, onSecurity = { nav.navigate("settings/security") })
            }
            composable("settings/security") {
                val vm: SecurityViewModel = viewModel(factory = factory)
                SecurityScreen(vm, onBack = { nav.popBackStack() })
            }
            composable("settings/general") {
                val vm: GeneralSettingsViewModel = viewModel(factory = factory)
                GeneralSettingsScreen(vm, onBack = { nav.popBackStack() })
            }
            composable(
                VIEWER,
                listOf(navArgument("source") { type = NavType.StringType }, navArgument("id") { type = NavType.LongType }),
            ) { e ->
                val deleteVm: DeleteViewModel = viewModel(factory = factory)
                val source = e.arguments!!.getString("source")!!
                val startId = e.arguments!!.getLong("id")
                val scope = rememberCoroutineScope()
                // "all" is the gallery as the user left it (search, filters, order): a window over the same query.
                // An album is small, so it is a plain list.
                val data: com.s3vault.app.presentation.library.ItemSource<MediaItem>?
                val startIndex: Int
                if (source == "all") {
                    val viewerVm: LibraryViewerViewModel = viewModel(factory = factory)
                    androidx.compose.runtime.LaunchedEffect(startId) { viewerVm.open(startId) }
                    val d = viewerVm.data.collectAsStateWithLifecycle().value
                    data = d?.source
                    startIndex = d?.startIndex ?: 0
                } else if (source.startsWith("smart-")) {
                    val viewerVm: SmartViewerViewModel = viewModel(factory = factory)
                    androidx.compose.runtime.LaunchedEffect(source, startId) { viewerVm.open(source.removePrefix("smart-"), startId) }
                    val d = viewerVm.data.collectAsStateWithLifecycle().value
                    data = d?.source
                    startIndex = d?.startIndex ?: 0
                } else if (source == "one") {
                    // One photo opened from somewhere that is not a list of the gallery (for example the sync centre).
                    val item by androidx.compose.runtime.produceState<MediaItem?>(null, startId) {
                        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { container.db.media().get(startId)?.toDomain() }
                    }
                    data = item?.let { com.s3vault.app.presentation.library.ListSource(listOf(it)) }
                    startIndex = 0
                } else {
                    val vm: AlbumsViewModel = viewModel(factory = factory)
                    val albumId = source.removePrefix("album-").toLong()
                    val list = androidx.compose.runtime.remember(albumId) { vm.albumMedia(albumId) }.collectAsStateWithLifecycle(initialValue = null).value
                    data = list?.let { com.s3vault.app.presentation.library.ListSource(it) }
                    startIndex = list?.indexOfFirst { it.mediaStoreId == startId }?.coerceAtLeast(0) ?: 0
                }
                if (data == null) LoadingBox() else ViewerScreen(
                    items = data, startIndex = startIndex,
                    onBack = { nav.popBackStack() },
                    deleteVm = deleteVm,
                    onOpenCredentials = { nav.navigate("settings/security") },
                    onBackup = { item ->
                        val n = container.backup.enqueue(listOf(item.mediaStoreId))
                        when {
                            container.settings.currentS3Config() == null -> "En cola. Configura Amazon S3 para que se suba."
                            n == 0 -> "Esta foto ya está respaldada o en cola."
                            else -> "En cola. El sistema decidirá cuándo subirla según las restricciones (red, batería)."
                        }
                    },
                )
            }
        }
    }
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
