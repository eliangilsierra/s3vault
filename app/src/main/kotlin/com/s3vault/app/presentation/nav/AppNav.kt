package com.s3vault.app.presentation.nav

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudSync
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
import com.s3vault.app.presentation.BackupViewModel
import com.s3vault.app.presentation.GalleryViewModel
import com.s3vault.app.presentation.GeneralSettingsViewModel
import com.s3vault.app.presentation.LoadingBox
import com.s3vault.app.presentation.RemoteViewModel
import com.s3vault.app.presentation.S3SettingsViewModel
import com.s3vault.app.presentation.SecurityViewModel
import com.s3vault.app.presentation.albums.AlbumDetailScreen
import com.s3vault.app.presentation.albums.AlbumsScreen
import com.s3vault.app.presentation.backup.BackupScreen
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

private const val VIEWER = "viewer/{source}/{id}"
private const val ALBUM = "album/{id}"
private const val CLOUD = "cloud/{key}"

@Composable
fun AppRoot(container: AppContainer) {
    val nav = rememberNavController()
    val factory = AppViewModelFactory(container)
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = route in TabRoutes
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
                GalleryScreen(
                    vm, deleteVm,
                    onOpen = { id -> nav.navigate("viewer/all/$id") },
                    onOpenBackup = { nav.navigateTab(Tab.Backup.route) },
                    onOpenCredentials = { nav.navigate("settings/security") },
                )
            }
            composable(Tab.Albums.route) {
                val vm: AlbumsViewModel = viewModel(factory = factory)
                AlbumsScreen(vm) { id -> nav.navigate("album/$id") }
            }
            composable(ALBUM, listOf(navArgument("id") { type = NavType.LongType })) { e ->
                val vm: AlbumsViewModel = viewModel(factory = factory)
                val deleteVm: DeleteViewModel = viewModel(factory = factory)
                val id = e.arguments!!.getLong("id")
                AlbumDetailScreen(
                    vm, deleteVm, id, onBack = { nav.popBackStack() },
                    onOpen = { mid -> nav.navigate("viewer/album-$id/$mid") },
                    onOpenCredentials = { nav.navigate("settings/security") },
                )
            }
            composable(Tab.Backup.route) {
                val vm: BackupViewModel = viewModel(factory = factory)
                BackupScreen(vm, onOpenS3Settings = { nav.navigate("settings/s3") }, onOpenSecurity = { nav.navigate("settings/security") })
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
            composable(CLOUD, listOf(navArgument("key") { type = NavType.StringType })) { e ->
                val vm: RemoteViewModel = viewModel(factory = factory)
                val deleteVm: DeleteViewModel = viewModel(factory = factory)
                val items = vm.state.collectAsStateWithLifecycle().value.items
                if (items.isEmpty()) LoadingBox() else CloudViewerScreen(
                    items = items, startKey = e.arguments!!.getString("key")!!, vm = vm, deleteVm = deleteVm,
                    onBack = { nav.popBackStack() }, onOpenCredentials = { nav.navigate("settings/security") },
                )
            }
            composable(Tab.Settings.route) {
                val costVm: StorageCostViewModel = viewModel(factory = factory)
                val cost by costVm.state.collectAsStateWithLifecycle()
                SettingsHome(
                    onS3 = { nav.navigate("settings/s3") },
                    onSecurity = { nav.navigate("settings/security") },
                    onGeneral = { nav.navigate("settings/general") },
                    onCosts = { nav.navigate("settings/costs") },
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
                val items: List<MediaItem>? = if (source == "all") {
                    val vm: GalleryViewModel = viewModel(factory = factory)
                    vm.state.collectAsStateWithLifecycle().value.items
                } else {
                    val vm: AlbumsViewModel = viewModel(factory = factory)
                    val albumId = source.removePrefix("album-").toLong()
                    androidx.compose.runtime.remember(albumId) { vm.albumMedia(albumId) }.collectAsStateWithLifecycle(initialValue = null).value
                }
                if (items == null) LoadingBox() else ViewerScreen(
                    items = items, startId = startId,
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
