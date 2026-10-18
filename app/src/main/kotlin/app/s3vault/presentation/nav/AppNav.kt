package app.s3vault.presentation.nav

import android.net.Uri
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.s3vault.presentation.albums.AlbumDetailScreen
import app.s3vault.presentation.albums.AlbumsScreen
import app.s3vault.presentation.backup.BackupScreen
import app.s3vault.presentation.gallery.GalleryScreen
import app.s3vault.presentation.remote.RemoteScreen
import app.s3vault.presentation.settings.S3ConfigScreen
import app.s3vault.presentation.settings.SecurityScreen
import app.s3vault.presentation.settings.SettingsScreen
import app.s3vault.presentation.viewer.ViewerScreen

private object Routes {
    const val GALLERY = "gallery"
    const val ALBUMS = "albums"
    const val BACKUP = "backup"
    const val REMOTE = "remote"
    const val SETTINGS = "settings"
    const val S3_CONFIG = "s3config"
    const val SECURITY = "security"
    const val ALBUM = "album/{id}"
    const val VIEWER = "viewer/{source}/{asset}"
    fun album(id: Long) = "album/$id"
    fun viewer(source: String, asset: String) = "viewer/${Uri.encode(source)}/${Uri.encode(asset)}"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.GALLERY, "Galería", Icons.Filled.Home),
    Tab(Routes.ALBUMS, "Álbumes", Icons.Filled.List),
    Tab(Routes.BACKUP, "Respaldo", Icons.Filled.Refresh),
    Tab(Routes.REMOTE, "Remoto", Icons.Filled.Search),
    Tab(Routes.SETTINGS, "Ajustes", Icons.Filled.Settings),
)

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = TABS.any { it.route == route }

    androidx.compose.material3.Scaffold(
        // Cada pantalla gestiona sus propios insets (barra superior, visor a pantalla completa…).
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBar) NavigationBar {
                TABS.forEach { tab ->
                    NavigationBarItem(
                        selected = route == tab.route,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.GALLERY, modifier = Modifier.padding(padding)) {
            composable(Routes.GALLERY) { GalleryScreen(onOpenViewer = { nav.navigate(Routes.viewer("gallery", it)) }) }
            composable(Routes.ALBUMS) { AlbumsScreen(onOpenAlbum = { nav.navigate(Routes.album(it)) }) }
            composable(Routes.BACKUP) {
                BackupScreen(onOpenS3Config = { nav.navigate(Routes.S3_CONFIG) }, onOpenSecurity = { nav.navigate(Routes.SECURITY) })
            }
            composable(Routes.REMOTE) { RemoteScreen(onOpenS3Config = { nav.navigate(Routes.S3_CONFIG) }) }
            composable(Routes.SETTINGS) {
                SettingsScreen(onOpenS3Config = { nav.navigate(Routes.S3_CONFIG) }, onOpenSecurity = { nav.navigate(Routes.SECURITY) })
            }
            composable(Routes.S3_CONFIG) {
                S3ConfigScreen(onBack = { nav.popBackStack() }, onOpenSecurity = { nav.navigate(Routes.SECURITY) })
            }
            composable(Routes.SECURITY) { SecurityScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.ALBUM, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.arguments?.getLong("id") ?: return@composable
                AlbumDetailScreen(
                    albumId = id, onBack = { nav.popBackStack() },
                    onOpenViewer = { nav.navigate(Routes.viewer("album:$id", it)) },
                )
            }
            composable(Routes.VIEWER, arguments = listOf(navArgument("source") { type = NavType.StringType }, navArgument("asset") { type = NavType.StringType })) { entry ->
                val source = entry.arguments?.getString("source") ?: "gallery"
                val asset = entry.arguments?.getString("asset") ?: return@composable
                ViewerScreen(source = source, initialAssetId = asset, onBack = { nav.popBackStack() })
            }
        }
    }
}
