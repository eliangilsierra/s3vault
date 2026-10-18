package app.s3vault.presentation.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.s3vault.data.repo.MediaPermissions
import app.s3vault.presentation.common.rememberContainer
import app.s3vault.settings.AppSettings
import app.s3vault.settings.ThemeMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onOpenS3Config: () -> Unit, onOpenSecurity: () -> Unit) {
    val c = rememberContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by c.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    var permVersion by remember { mutableIntStateOf(0) }
    val hasLocation = remember(permVersion) { MediaPermissions.hasMediaLocation(context) }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permVersion++ }

    Scaffold(topBar = { TopAppBar(title = { Text("Ajustes") }) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Conexión")
            NavCard(
                "Amazon S3",
                if (s.s3Config != null) "${s.bucket} · ${s.region}" + (if (s.prefix.isNotEmpty()) " · ${s.prefix}" else "") else "Sin configurar",
                onOpenS3Config,
            )
            NavCard("Seguridad y credenciales", "Credenciales temporales, Identity Center o claves estáticas", onOpenSecurity)

            SectionTitle("Respaldo")
            ToggleRow(
                "Respaldo automático", "Revisa fotos nuevas cada ~6 h y cuando cambia la galería. Android puede aplazarlo.",
                s.autoBackup, enabled = s.s3Config != null,
            ) { on ->
                scope.launch {
                    c.settings.setAutoBackup(on)
                    c.workScheduler.configureAutoBackup(on, s.wifiOnly)
                    if (on) {
                        c.galleryRepository.refreshIfPermitted()
                        c.backupRepository.backupAllPending()
                    }
                }
            }
            ToggleRow("Solo con Wi‑Fi (o red sin tarifa)", "Evita gastar datos móviles en subidas y descargas.", s.wifiOnly) { on ->
                scope.launch {
                    c.settings.setWifiOnly(on)
                    if (s.autoBackup) c.workScheduler.configureAutoBackup(true, on)
                }
            }
            ToggleRow("Excluir vídeos del respaldo", "Los vídeos pesan mucho y aumentan el costo de AWS.", s.excludeVideos) { on ->
                scope.launch { c.settings.setExcludeVideos(on) }
            }
            Text("Transferencias simultáneas", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..3).forEach { n ->
                    FilterChip(selected = s.concurrency == n, onClick = { scope.launch { c.settings.setConcurrency(n) } }, label = { Text("$n") })
                }
            }

            SectionTitle("Ubicación de las fotos")
            Text(
                if (hasLocation) "Concedido: los respaldos conservan la ubicación (EXIF GPS) de las fotos originales."
                else "Sin este permiso, Android entrega las fotos sin su ubicación GPS y el respaldo no la incluirá. Es opcional.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!hasLocation) OutlinedButton(onClick = { locationLauncher.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION) }) { Text("Conceder ubicación de medios") }

            SectionTitle("Apariencia")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { m ->
                    FilterChip(
                        selected = s.theme == m, onClick = { scope.launch { c.settings.setTheme(m) } },
                        label = { Text(when (m) { ThemeMode.SYSTEM -> "Sistema"; ThemeMode.LIGHT -> "Claro"; ThemeMode.DARK -> "Oscuro" }) },
                    )
                }
            }

            SectionTitle("Restauración")
            Text(
                "Las fotos restauradas se guardan como archivos nuevos en Imágenes/S3Vault (Películas/S3Vault para vídeos), tras verificar su SHA-256. " +
                    "Nunca se sobrescribe un archivo existente: si el nombre ya existe se añade « (1)».",
                style = MaterialTheme.typography.bodySmall,
            )

            SectionTitle("Costos de AWS")
            Text(
                "Pagas directamente a AWS por: almacenamiento en S3, solicitudes PUT/GET/LIST, descargas (transferencia de salida), versiones de objetos si activas S3 Versioning " +
                    "(la app no lo activa) y cargas multiparte incompletas (la app no usa multiparte). Esta app no genera infraestructura ni cobros por sí misma, pero tampoco puede prometer que S3 sea gratis.",
                style = MaterialTheme.typography.bodySmall,
            )

            SectionTitle("Acerca de")
            Text("S3 Vault 0.1.0 · Galería local con respaldo directo a tu propio bucket de Amazon S3. Sin backend, sin cuentas, sin telemetría.", style = MaterialTheme.typography.bodySmall)
            Text("Eliminar fotos del teléfono o de un álbum nunca borra copias en S3, y esta versión no puede borrar objetos remotos.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    HorizontalDivider()
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun NavCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

