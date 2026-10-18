package app.s3vault

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.s3vault.presentation.nav.AppRoot
import app.s3vault.presentation.theme.S3VaultTheme
import app.s3vault.settings.AppSettings
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as S3VaultApp).container
        // Reasegura la programación persistente (idempotente) sin bloquear el arranque.
        lifecycleScope.launch { container.bootstrap() }
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            S3VaultTheme(settings.theme) { AppRoot() }
        }
    }
}
