package app.s3vault.presentation.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.s3vault.auth.AuthMethod
import app.s3vault.auth.CredentialInputError
import app.s3vault.auth.CredentialStatus
import app.s3vault.presentation.Strings
import app.s3vault.presentation.common.Notice
import app.s3vault.presentation.common.appViewModel
import app.s3vault.presentation.common.openUrl

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(onBack: () -> Unit) {
    val vm = appViewModel { SecurityViewModel(it) }
    val context = LocalContext.current
    val status by vm.status.collectAsStateWithLifecycle(initialValue = CredentialStatus.NotConfigured)
    val errors by vm.errors.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }

    // Esta pantalla muestra y recibe secretos: se bloquean capturas de pantalla y grabaciones.
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Seguridad y credenciales") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Volver") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(status)
            message?.let { Notice(it) }
            if (errors.isNotEmpty()) Notice(errors.joinToString("\n") { Strings.credentialInput(it) }, isError = true)

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0; vm.clearMessages() }, text = { Text("Temporales") })
                Tab(selected = tab == 1, onClick = { tab = 1; vm.clearMessages() }, text = { Text("Identity Center") })
                Tab(selected = tab == 2, onClick = { tab = 2; vm.clearMessages() }, text = { Text("Claves estáticas") })
            }
            when (tab) {
                0 -> TemporaryForm(vm, errors)
                1 -> IdentityCenterForm(vm)
                else -> StaticForm(vm, errors)
            }

            TextButton(onClick = { confirmClear = true }, enabled = status != CredentialStatus.NotConfigured) {
                Text("Eliminar credenciales de este dispositivo", color = MaterialTheme.colorScheme.error)
            }
            PrivacyCard()
        }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Eliminar credenciales") },
        text = { Text("Se borrarán del dispositivo (y la clave del Keystore que las protegía). Deberás introducirlas de nuevo para respaldar o restaurar. No se elimina nada en S3.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearCredentials() }) { Text("Eliminar") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } },
    )
}

@Composable
private fun StatusCard(status: CredentialStatus) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Estado de la autenticación", style = MaterialTheme.typography.titleSmall)
            when (status) {
                CredentialStatus.NotConfigured -> Text("Sin credenciales. La galería funciona; para respaldar necesitas configurarlas.")
                is CredentialStatus.Valid -> {
                    Text("Método: ${methodName(status.method)} · clave ${status.maskedKeyId}")
                    Text(
                        status.expiresAt?.let { "Vigentes hasta ${Strings.dateTime(it.toEpochMilli())}" }
                            ?: "Sin caducidad (clave estática o sesión renovable).",
                    )
                }
                is CredentialStatus.ExpiringSoon -> {
                    Text("Método: ${methodName(status.method)} · clave ${status.maskedKeyId}")
                    Text("Caducan en ${Strings.duration(status.remaining.seconds)}. Renuévalas pronto.", color = MaterialTheme.colorScheme.error)
                }
                is CredentialStatus.Expired -> Text(
                    "Caducaron${status.expiredAt?.let { " el ${Strings.dateTime(it.toEpochMilli())}" } ?: ""}. El respaldo está detenido y conserva lo pendiente; introduce credenciales nuevas.",
                    color = MaterialTheme.colorScheme.error,
                )
                is CredentialStatus.RenewalFailed -> Text(
                    "No se pudo renovar la sesión. Inicia sesión de nuevo en la pestaña Identity Center.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun methodName(m: AuthMethod) = when (m) {
    AuthMethod.TEMPORARY_MANUAL -> "credenciales temporales"
    AuthMethod.IDENTITY_CENTER -> "IAM Identity Center"
    AuthMethod.STATIC_KEYS -> "claves estáticas"
}

@Composable
private fun TemporaryForm(vm: SecurityViewModel, errors: List<CredentialInputError>) {
    // Los secretos se mantienen solo en memoria (remember, no rememberSaveable): no entran en el estado guardado de la actividad.
    var pasted by remember { mutableStateOf("") }
    var accessKeyId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var expiration by remember { mutableStateOf("") }
    val hint by vm.parsedHint.collectAsStateWithLifecycle()
    LaunchedEffect(hint) {
        hint?.let { h ->
            if (h.accessKeyId.isNotEmpty()) accessKeyId = h.accessKeyId
            if (h.secret.isNotEmpty()) secret = h.secret
            if (h.token.isNotEmpty()) token = h.token
            if (h.expiration.isNotEmpty()) expiration = h.expiration
            vm.consumeParsedHint(); pasted = ""
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Notice(
            "Recomendado. Las credenciales temporales (STS) caducan solas, así que una fuga tiene alcance limitado. " +
                "Obténlas con tu propio proveedor de identidad, p. ej. «aws sts get-session-token», «aws sts assume-role» o " +
                "«aws configure export-credentials». Cuando caduquen, la app detiene las transferencias, conserva la cola y te pide otras nuevas: " +
                "no hay renovación automática con este método.",
        )
        OutlinedTextField(
            pasted, { pasted = it }, label = { Text("Pegar salida de la AWS CLI (opcional)") }, minLines = 2, maxLines = 5,
            modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        )
        OutlinedButton(onClick = { vm.parsePasted(pasted) }, enabled = pasted.isNotBlank()) { Text("Rellenar campos") }
        SecretField(accessKeyId, { accessKeyId = it.trim() }, "Access Key ID (ASIA…)", hide = false, error = CredentialInputError.ACCESS_KEY_ID_FORMAT in errors)
        SecretField(secret, { secret = it.trim() }, "Secret Access Key", hide = true, error = CredentialInputError.SECRET_FORMAT in errors)
        SecretField(token, { token = it.trim() }, "Session Token", hide = true, error = CredentialInputError.SESSION_TOKEN_REQUIRED in errors)
        OutlinedTextField(
            expiration, { expiration = it.trim() }, label = { Text("Expiración (ISO-8601, p. ej. 2026-01-31T12:00:00Z)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            isError = CredentialInputError.EXPIRATION_REQUIRED in errors || CredentialInputError.ALREADY_EXPIRED in errors,
        )
        Button(onClick = {
            vm.saveTemporary(accessKeyId, secret, token, expiration) { accessKeyId = ""; secret = ""; token = ""; expiration = "" }
        }) { Text("Guardar credenciales temporales") }
    }
}

@Composable
private fun StaticForm(vm: SecurityViewModel, errors: List<CredentialInputError>) {
    var accessKeyId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var understood by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Notice(
            "Solo para usuarios avanzados y MENOS segura que las credenciales temporales: una clave estática no caduca y, si se filtra, " +
                "sirve hasta que la revoques en IAM. Usa un usuario IAM dedicado con la política mínima (nunca claves de la cuenta raíz ni de un administrador). " +
                "El Keystore protege el almacenamiento local, pero no impide que un dispositivo comprometido las use.",
            isError = true,
        )
        SecretField(accessKeyId, { accessKeyId = it.trim() }, "Access Key ID (AKIA…)", hide = false, error = CredentialInputError.ACCESS_KEY_ID_FORMAT in errors)
        SecretField(secret, { secret = it.trim() }, "Secret Access Key", hide = true, error = CredentialInputError.SECRET_FORMAT in errors)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(understood, { understood = it })
            Text("Entiendo los riesgos y que usaré un usuario IAM de permisos mínimos.", style = MaterialTheme.typography.bodySmall)
        }
        Button(enabled = understood, onClick = { vm.saveStatic(accessKeyId, secret) { accessKeyId = ""; secret = "" } }) {
            Text("Guardar claves estáticas")
        }
    }
}

@Composable
private fun IdentityCenterForm(vm: SecurityViewModel) {
    val state by vm.ic.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var startUrl by remember { mutableStateOf("") }
    var region by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Notice(
            "Credenciales temporales renovables sin backend propio. Requiere que tu organización tenga habilitado AWS IAM Identity Center y que tu " +
                "usuario tenga asignado un conjunto de permisos con acceso al bucket. Inicias sesión una vez en el navegador (autorización de dispositivo) " +
                "y la app renueva las credenciales con el refresh token mientras tu sesión de Identity Center siga vigente; después tendrás que iniciar sesión de nuevo. " +
                "Función nueva: aún sin validar contra una cuenta real de AWS.",
        )
        when (val s = state) {
            IdentityCenterUi.Idle, is IdentityCenterUi.Failed -> {
                if (s is IdentityCenterUi.Failed) Notice(s.message, isError = true)
                OutlinedTextField(startUrl, { startUrl = it.trim() }, label = { Text("URL del portal de acceso (https://…awsapps.com/start)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(region, { region = it.trim().lowercase() }, label = { Text("Región de Identity Center (p. ej. eu-west-1)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { vm.startIdentityCenter(startUrl, region) }, enabled = startUrl.isNotBlank() && region.isNotBlank()) { Text("Iniciar sesión") }
            }
            IdentityCenterUi.Starting -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is IdentityCenterUi.WaitingApproval -> {
                Text("Abre este enlace, comprueba que el código coincide y aprueba el acceso:")
                Text(s.userCode, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)
                Button(onClick = { openUrl(context, s.verificationUrl) }) { Text("Abrir en el navegador") }
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Esperando tu aprobación…", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = vm::cancelIdentityCenter) { Text("Cancelar") }
            }
            is IdentityCenterUi.ChooseAccount -> {
                Text("Elige la cuenta de AWS:")
                s.accounts.forEach { a -> OutlinedButton(onClick = { vm.chooseAccount(a.accountId) }, modifier = Modifier.fillMaxWidth()) { Text("${a.accountName} (${a.accountId})") } }
                TextButton(onClick = vm::cancelIdentityCenter) { Text("Cancelar") }
            }
            is IdentityCenterUi.ChooseRole -> {
                Text("Elige el rol (conjunto de permisos):")
                s.roles.forEach { r -> OutlinedButton(onClick = { vm.chooseRole(s.accountId, r) }, modifier = Modifier.fillMaxWidth()) { Text(r) } }
                TextButton(onClick = vm::cancelIdentityCenter) { Text("Cancelar") }
            }
            IdentityCenterUi.Saving -> LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, label: String, hide: Boolean, error: Boolean) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, isError = error,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (hide) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        // Teclado de contraseña: sin sugerencias ni aprendizaje de texto.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
    )
}

@Composable
private fun PrivacyCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Qué se guarda y dónde", style = MaterialTheme.typography.titleSmall)
            Text("• En el teléfono: tus fotos, un índice local con metadatos y el estado de cada respaldo (base de datos privada de la app), y las credenciales cifradas con una clave del Android Keystore. Nada va a copias de seguridad de Google.", style = MaterialTheme.typography.bodySmall)
            Text("• En tu bucket de S3: los originales, con el nombre original, fecha de captura, dimensiones y SHA-256 como metadatos del objeto. Cualquier identidad con permiso de lectura sobre el bucket puede verlos; no es cifrado de extremo a extremo.", style = MaterialTheme.typography.bodySmall)
            Text("• En ningún servidor del desarrollador: no existe backend, cuentas ni telemetría. La app solo habla con Amazon S3 (y, si lo eliges, con IAM Identity Center) por HTTPS.", style = MaterialTheme.typography.bodySmall)
            Text("• Borrar una foto del teléfono, quitarla de un álbum, cerrar sesión o desinstalar la app NO elimina nada en S3. Esta versión no puede borrar objetos remotos.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
