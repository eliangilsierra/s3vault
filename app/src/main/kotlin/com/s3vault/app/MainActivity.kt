package com.s3vault.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.s3vault.app.presentation.nav.AppRoot
import com.s3vault.app.presentation.theme.S3VaultTheme
import com.s3vault.app.presentation.theme.isDarkTheme
import com.s3vault.app.settings.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as S3VaultApp).container

        // Re-evaluate credential expiry while the app is visible (cheap, local only, no network).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    // Decrypting the stored credentials goes through the Android Keystore (a binder call, 50-130 ms in StrictMode).
                    withContext(Dispatchers.IO) { container.credentials.refreshStatus() }
                    delay(30_000)
                }
            }
        }

        setContent {
            val theme by container.settings.theme.collectAsState(initial = ThemeMode.SYSTEM)
            // System-bar icon colours follow the app theme (which may differ from the system theme).
            val dark = isDarkTheme(theme)
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                )
                onDispose { }
            }
            val reducedMotion = com.s3vault.app.presentation.ui.rememberReducedMotion()
            androidx.compose.runtime.CompositionLocalProvider(com.s3vault.app.presentation.ui.LocalReducedMotion provides reducedMotion) {
                S3VaultTheme(theme) { AppRoot(container) }
            }
        }
    }
}
