package app.s3vault.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.s3vault.auth.CredentialStatus
import app.s3vault.data.remote.Check
import app.s3vault.data.remote.ConnectionReport
import app.s3vault.data.remote.IamPolicies
import app.s3vault.domain.model.ConfigError
import app.s3vault.domain.model.ServerSideEncryption
import app.s3vault.presentation.Strings
import app.s3vault.presentation.common.Notice
import app.s3vault.presentation.common.appViewModel
import app.s3vault.settings.AppSettings

private val REGIONS = listOf(
    "us-east-1", "us-east-2", "us-west-1", "us-west-2", "ca-central-1", "ca-west-1", "mx-central-1", "sa-east-1",
    "eu-west-1", "eu-west-2", "eu-west-3", "eu-central-1", "eu-central-2", "eu-north-1", "eu-south-1", "eu-south-2",
    "ap-south-1", "ap-south-2", "ap-northeast-1", "ap-northeast-2", "ap-northeast-3", "ap-southeast-1", "ap-southeast-2",
    "ap-southeast-3", "ap-southeast-4", "ap-southeast-5", "ap-east-1", "me-south-1", "me-central-1", "af-south-1", "il-central-1",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S3ConfigScreen(onBack: () -> Unit, onOpenSecurity: () -> Unit) {
    val vm = appViewModel { S3ConfigViewModel(it) }
    val form by vm.form.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    var regionMenu by remember { mutableStateOf(false) }
    var sseMenu by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var showPolicy by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configuración de Amazon S3") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Volver") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Notice(
                "Esta app se conecta solo a Amazon S3, con tu propio bucket privado. Tú administras la cuenta, los permisos y los costos de AWS. " +
                    "Nunca habilites acceso público para arreglar un error de autorización.",
            )

            Box {
                OutlinedTextField(
                    value = form.region, onValueChange = { v -> vm.update { it.copy(region = v.trim().lowercase()) } },
                    label = { Text("Región AWS (p. ej. eu-west-1)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = ConfigError.INVALID_REGION in errors,
                    supportingText = { if (ConfigError.INVALID_REGION in errors) Text("Región no válida.") },
                    trailingIcon = { TextButton(onClick = { regionMenu = true }) { Text("Elegir") } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                DropdownMenu(expanded = regionMenu, onDismissRequest = { regionMenu = false }) {
                    REGIONS.forEach { r ->
                        DropdownMenuItem(text = { Text(r) }, onClick = { vm.update { it.copy(region = r) }; regionMenu = false })
                    }
                }
            }
            OutlinedTextField(
                value = form.bucket, onValueChange = { v -> vm.update { it.copy(bucket = v.trim()) } },
                label = { Text("Nombre del bucket") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                isError = ConfigError.INVALID_BUCKET in errors,
                supportingText = { Text(if (ConfigError.INVALID_BUCKET in errors) "Nombre no válido (3–63 caracteres: minúsculas, números, guiones y puntos)." else "Debe existir ya: la app no crea recursos en AWS.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = form.prefix, onValueChange = { v -> vm.update { it.copy(prefix = v.trim()) } },
                label = { Text("Prefijo (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                isError = ConfigError.INVALID_PREFIX in errors,
                supportingText = { Text(if (ConfigError.INVALID_PREFIX in errors) "Solo letras, números, «.», «_», «-» y «/»." else "Las fotos se guardan en «prefijo/photos/…». Todas las operaciones respetan este prefijo.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Box {
                OutlinedButton(onClick = { sseMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Cifrado en servidor: " + sseLabel(form.sse))
                }
                DropdownMenu(expanded = sseMenu, onDismissRequest = { sseMenu = false }) {
                    ServerSideEncryption.entries.forEach { e ->
                        DropdownMenuItem(text = { Text(sseLabel(e)) }, onClick = { vm.update { it.copy(sse = e) }; sseMenu = false })
                    }
                }
            }
            Text(
                "S3 cifra por defecto con SSE-S3. Este cifrado es del lado del servidor: quien tenga permisos sobre el bucket puede leer el contenido. No es cifrado de extremo a extremo.",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::save, enabled = !busy) { Text("Guardar") }
                OutlinedButton(onClick = onOpenSecurity) { Text("Credenciales") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.test(false) }, enabled = !busy) { Text("Probar conexión") }
                OutlinedButton(onClick = { vm.test(true) }, enabled = !busy) { Text("Probar permisos") }
            }
            Text(
                "«Probar conexión» hace una sola solicitud LIST. «Probar permisos» además escribe y lee un objeto de prueba de ~30 bytes " +
                    "(.s3vault/connection-check.txt) que se sobrescribe cada vez; la app no tiene permiso de borrado.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            info?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            report?.let { ReportView(it) }

            ConnectionStatus(settings)

            TextButton(onClick = { showPolicy = true }, enabled = form.bucket.isNotBlank()) { Text("Ver política IAM recomendada") }
            TextButton(onClick = { confirmDisconnect = true }) {
                Text("Desconectar y eliminar credenciales locales", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmDisconnect) AlertDialog(
        onDismissRequest = { confirmDisconnect = false },
        title = { Text("Desconectar") },
        text = { Text("Se eliminarán de este dispositivo las credenciales y la configuración de S3 y se detendrán los trabajos programados. Los objetos que ya están en tu bucket NO se eliminan.") },
        confirmButton = { TextButton(onClick = { confirmDisconnect = false; vm.disconnect() }) { Text("Desconectar") } },
        dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancelar") } },
    )

    if (showPolicy) {
        val clipboard = LocalClipboardManager.current
        val prefix = app.s3vault.domain.model.S3ConfigRules.normalizePrefix(form.prefix) ?: ""
        val policy = IamPolicies.identityPolicy(form.bucket.ifBlank { "TU-BUCKET" }, prefix)
        AlertDialog(
            onDismissRequest = { showPolicy = false },
            title = { Text("Política IAM mínima") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Adjúntala a un usuario o rol IAM dedicado. Sin permisos de borrado ni administrativos.", style = MaterialTheme.typography.bodySmall)
                    Text(policy, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(policy)) }) { Text("Copiar") } },
            dismissButton = { TextButton(onClick = { showPolicy = false }) { Text("Cerrar") } },
        )
    }
}

private fun sseLabel(e: ServerSideEncryption) = when (e) {
    ServerSideEncryption.BUCKET_DEFAULT -> "predeterminado del bucket"
    ServerSideEncryption.AES256 -> "SSE-S3 (AES256) explícito"
    ServerSideEncryption.AWS_KMS -> "SSE-KMS (clave aws/s3)"
}

@Composable
private fun ConnectionStatus(s: AppSettings) {
    val text = when {
        s.s3Config == null -> "Estado: sin configurar."
        s.connectionVerifiedAtMs != null -> "Estado: conectado (verificado el ${Strings.dateTime(s.connectionVerifiedAtMs)})."
        else -> "Estado: configurado, pendiente de verificar."
    }
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ReportView(r: ConnectionReport) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CheckRow("Credenciales", credentialLine(r.credentials), r.credentials !is CredentialStatus.NotConfigured && r.credentials !is CredentialStatus.Expired && r.credentials !is CredentialStatus.RenewalFailed)
        CheckRow("Conexión y listado (s3:ListBucket)", r.list)
        if (r.put !is Check.NotRun) CheckRow("Escritura (s3:PutObject)", r.put)
        if (r.get !is Check.NotRun) CheckRow("Lectura (s3:GetObject)", r.get)
    }
}

private fun credentialLine(s: CredentialStatus): String = when (s) {
    CredentialStatus.NotConfigured -> "no configuradas"
    is CredentialStatus.Valid -> "vigentes" + (s.expiresAt?.let { " hasta ${Strings.dateTime(it.toEpochMilli())}" } ?: " (claves estáticas)")
    is CredentialStatus.ExpiringSoon -> "caducan en ${Strings.duration(s.remaining.seconds)}"
    is CredentialStatus.Expired -> "caducadas"
    is CredentialStatus.RenewalFailed -> "no se pudieron renovar"
}

@Composable
private fun CheckRow(label: String, check: Check) = when (check) {
    Check.Ok -> CheckRow(label, "correcto", true)
    is Check.Failed -> CheckRow(label, Strings.failure(check.failure), false)
    Check.NotRun -> Unit
}

@Composable
private fun CheckRow(label: String, detail: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Close, contentDescription = if (ok) "Correcto" else "Fallo",
            tint = if (ok) Color(0xFF2E9E5B) else MaterialTheme.colorScheme.error,
        )
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}
