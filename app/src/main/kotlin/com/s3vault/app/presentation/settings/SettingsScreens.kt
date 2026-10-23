package com.s3vault.app.presentation.settings

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.s3vault.app.BuildConfig
import com.s3vault.app.auth.AuthStatus
import com.s3vault.app.domain.AuthMethod
import com.s3vault.app.domain.ConfigValidator
import com.s3vault.app.presentation.GeneralSettingsViewModel
import com.s3vault.app.presentation.S3SettingsViewModel
import com.s3vault.app.presentation.SecurityUiState
import com.s3vault.app.presentation.SecurityViewModel
import com.s3vault.app.presentation.formatDateTime
import com.s3vault.app.presentation.theme.Space
import com.s3vault.app.presentation.theme.status
import com.s3vault.app.presentation.ui.AppTopBar
import com.s3vault.app.presentation.ui.BackButton
import com.s3vault.app.presentation.ui.ChoiceChip
import com.s3vault.app.presentation.ui.ConfirmDialog
import com.s3vault.app.presentation.ui.GroupDivider
import com.s3vault.app.presentation.ui.PrimaryButton
import com.s3vault.app.presentation.ui.QuietButton
import com.s3vault.app.presentation.ui.SecondaryButton
import com.s3vault.app.presentation.ui.SectionHeader
import com.s3vault.app.presentation.ui.SettingsGroup
import com.s3vault.app.presentation.ui.SettingsRow
import com.s3vault.app.presentation.ui.StatusBanner
import com.s3vault.app.presentation.ui.SwitchRow
import com.s3vault.app.presentation.ui.Tone
import com.s3vault.app.settings.ThemeMode
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

/** Blocks screenshots and the recents thumbnail while a screen that handles secrets is visible. */
@Composable
private fun SecureWindow() {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        val window = (ctx as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

@Composable
private fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(title, navigation = { BackButton(onBack) }) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = Space.maxContent).fillMaxSize().verticalScroll(scrollState)
                    .padding(horizontal = Space.screen).padding(top = Space.s, bottom = Space.xxl),
                verticalArrangement = Arrangement.spacedBy(Space.m),
            ) { content() }
        }
    }
}

// ------------------------------------------------------------------ Home

@Composable
fun SettingsHome(onS3: () -> Unit, onSecurity: () -> Unit, onGeneral: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar("Ajustes") },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = Space.maxContent).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = Space.screen).padding(bottom = Space.xxl),
        ) {
            SectionHeader("Conexión con AWS")
            SettingsGroup {
                SettingsRow(
                    title = "Amazon S3", subtitle = "Región, bucket, prefijo y pruebas de conexión",
                    icon = Icons.Filled.Cloud, onClick = onS3,
                )
                GroupDivider()
                SettingsRow(
                    title = "Seguridad y credenciales", subtitle = "Credenciales temporales o claves estáticas, expiración y borrado",
                    icon = Icons.Filled.Lock, onClick = onSecurity,
                )
            }
            SectionHeader("Preferencias")
            SettingsGroup {
                SettingsRow(
                    title = "Ajustes generales", subtitle = "Tema, respaldo automático, Wi-Fi, vídeos y privacidad",
                    icon = Icons.Filled.Tune, onClick = onGeneral,
                )
            }
            SectionHeader("Acerca de")
            SettingsGroup {
                SettingsRow(title = "Versión", subtitle = BuildConfig.VERSION_NAME)
                GroupDivider(Space.l)
                SettingsRow(
                    title = "Sin cuentas ni telemetría",
                    subtitle = "La app solo habla con tu propio bucket de Amazon S3. No hay servidores del desarrollador.",
                )
            }
        }
        }
    }
}

// ------------------------------------------------------------------ Amazon S3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S3SettingsScreen(vm: S3SettingsViewModel, onBack: () -> Unit, onSecurity: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    var confirmDisconnect by remember { mutableStateOf(false) }
    SecureWindow()
    val scroll = rememberScrollState()
    // Field errors sit next to the fields at the top, but "Guardar" is further down: bring them into view.
    LaunchedEffect(s.issues) { if (s.issues.isNotEmpty()) scroll.animateScrollTo(0) }

    SettingsScaffold("Amazon S3", onBack, scroll) {
        StatusBanner(
            Tone.Info, "Solo tu bucket privado",
            "Esta app se conecta exclusivamente a Amazon S3 y solo a tu propio bucket privado. No se crea ningún recurso en AWS ni se habilita acceso público.",
        )

        SectionHeader("Destino")
        Text(
            "Un bucket es el contenedor privado de tu cuenta de AWS donde se guardan las copias.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = s.form.region,
                onValueChange = { v -> vm.edit { it.copy(region = v.trim().lowercase()) }; expanded = true },
                label = { Text("Región AWS") },
                placeholder = { Text("eu-west-1") },
                isError = s.issues.containsKey(ConfigValidator.Field.REGION),
                supportingText = { Text(s.issues[ConfigValidator.Field.REGION] ?: "Debe coincidir con la región del bucket.") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
                modifier = Modifier.fillMaxWidth().menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryEditable),
            )
            val matches = ConfigValidator.knownRegions.filter { it.contains(s.form.region) }
            if (matches.isNotEmpty()) {
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    matches.take(12).forEach { r ->
                        DropdownMenuItem(text = { Text(r) }, onClick = { vm.edit { it.copy(region = r) }; expanded = false })
                    }
                }
            }
        }

        OutlinedTextField(
            value = s.form.bucket, onValueChange = { v -> vm.edit { it.copy(bucket = v.trim()) } },
            label = { Text("Nombre del bucket") },
            isError = s.issues.containsKey(ConfigValidator.Field.BUCKET),
            supportingText = { Text(s.issues[ConfigValidator.Field.BUCKET] ?: "Solo el nombre, sin s3:// ni URL.") },
            singleLine = true, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            value = s.form.prefix, onValueChange = { v -> vm.edit { it.copy(prefix = v) } },
            label = { Text("Prefijo (opcional)") },
            isError = s.issues.containsKey(ConfigValidator.Field.PREFIX),
            supportingText = { Text(s.issues[ConfigValidator.Field.PREFIX] ?: "Ejemplo: mis-fotos. Todas las operaciones quedan limitadas a este prefijo.") },
            singleLine = true, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Uri),
        )

        SectionHeader("Método de autenticación")
        MethodOption(
            selected = s.form.method == AuthMethod.TEMPORARY, title = "Credenciales temporales (recomendado)",
            body = "Access key + secret + session token con fecha de expiración. Caducan solas.",
        ) { vm.edit { it.copy(method = AuthMethod.TEMPORARY) } }
        MethodOption(
            selected = s.form.method == AuthMethod.STATIC, title = "Access key y secret estáticas (avanzado)",
            body = "Claves permanentes: si se filtran, valen hasta que las revoques. Usa un usuario IAM dedicado y sin permisos administrativos.",
        ) { vm.edit { it.copy(method = AuthMethod.STATIC) } }

        PrimaryButton("Guardar configuración", vm::save, Modifier.fillMaxWidth(), enabled = s.dirty || !s.saved)

        SectionHeader("Conexión")
        ConnectionSummary(s.auth, s.connection.verifiedAtMs, s.connection.lastResultOk)
        if (s.auth is AuthStatus.Missing || s.auth is AuthStatus.Expired) {
            SecondaryButton("Introducir credenciales", onSecurity, Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            SecondaryButton("Probar conexión", vm::testConnection, Modifier.weight(1f), enabled = s.busy == null && s.saved)
            SecondaryButton("Probar permisos", vm::testPermissions, Modifier.weight(1f), enabled = s.busy == null && s.saved)
        }
        Text(
            "«Probar permisos» escribe y lee un objeto de unos 30 bytes (.s3vault/connection-check.txt) en tu prefijo. No toca tus fotos ni borra nada.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        s.busy?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
        s.resultMessage?.let {
            StatusBanner(
                if (s.resultOk == false) Tone.Error else Tone.Success,
                if (s.resultOk == false) "La comprobación falló" else "Comprobación correcta",
                it,
            )
        }
        s.permissions?.let { report ->
            SettingsGroup {
                report.checks.forEachIndexed { i, c ->
                    if (i > 0) GroupDivider()
                    Row(
                        Modifier.fillMaxWidth().padding(Space.l).semantics(mergeDescendants = true) {},
                        horizontalArrangement = Arrangement.spacedBy(Space.m),
                    ) {
                        Icon(
                            if (c.allowed) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                            if (c.allowed) "Permitido" else "Denegado",
                            Modifier.size(20.dp),
                            tint = if (c.allowed) MaterialTheme.status.success else MaterialTheme.colorScheme.error,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(c.operation, style = MaterialTheme.typography.bodyMedium)
                            if (c.error != null) {
                                Text(c.error.userMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            } else if (!c.allowed) {
                                Text(
                                    "No se pudo comprobar porque la escritura anterior falló.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        QuietButton(
            "Desconectar y eliminar credenciales locales", { confirmDisconnect = true },
            Modifier.fillMaxWidth().padding(top = Space.l), destructive = true,
        )
    }

    if (confirmDisconnect) {
        ConfirmDialog(
            title = "¿Desconectar?",
            text = "Se borrarán de este dispositivo las credenciales y la configuración del bucket, y se cancelarán los trabajos pendientes. " +
                "Los objetos que ya están en S3 NO se eliminan, y tus fotos locales tampoco.",
            confirmLabel = "Desconectar",
            destructive = true,
            onConfirm = { vm.disconnect(); confirmDisconnect = false },
            onDismiss = { confirmDisconnect = false },
        )
    }
}

/** A selectable option rendered as a quiet panel; the selected one is outlined in the accent colour. */
@Composable
private fun MethodOption(selected: Boolean, title: String, body: String, onSelect: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                .heightIn(min = Space.touch).padding(vertical = Space.s, horizontal = Space.s),
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
            verticalAlignment = Alignment.Top,
        ) {
            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(Space.m))
            Column(Modifier.padding(top = Space.m, bottom = Space.m, end = Space.m), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ConnectionSummary(auth: AuthStatus, verifiedAt: Long?, ok: Boolean?) {
    val creds = when (auth) {
        AuthStatus.Missing -> "Sin credenciales"
        is AuthStatus.Valid -> "Credenciales guardadas (${auth.info.accessKeyIdHint})"
        is AuthStatus.ExpiringSoon -> "Credenciales a punto de caducar"
        is AuthStatus.Expired -> "Credenciales caducadas"
    }
    val last = when {
        verifiedAt == null -> "Aún no verificada"
        ok == true -> "Última comprobación correcta: ${formatDateTime(verifiedAt)}"
        else -> "Última comprobación fallida: ${formatDateTime(verifiedAt)}"
    }
    val (icon, tint) = when {
        auth is AuthStatus.Expired || (verifiedAt != null && ok == false) -> Icons.Filled.ErrorOutline to MaterialTheme.colorScheme.error
        auth is AuthStatus.Valid && ok == true -> Icons.Filled.CheckCircle to MaterialTheme.status.success
        else -> Icons.Filled.Lock to MaterialTheme.colorScheme.onSurfaceVariant
    }
    SettingsGroup {
        Row(Modifier.fillMaxWidth().padding(Space.l), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            Icon(icon, null, Modifier.size(22.dp), tint = tint)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Estado: $creds", style = MaterialTheme.typography.bodyMedium)
                Text(last, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ------------------------------------------------------------------ Security & credentials

@Composable
fun SecurityScreen(vm: SecurityViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var showProtection by remember { mutableStateOf(false) }
    SecureWindow()
    val scroll = rememberScrollState()
    // Keep the expiry countdown honest while the screen is open.
    LaunchedEffect(Unit) { while (true) { vm.refreshStatus(); delay(15_000) } }
    // Feedback appears beside the credential status at the top; the save buttons are further down, so bring it into view.
    LaunchedEffect(s.error, s.message) { if (s.error != null || s.message != null) scroll.animateScrollTo(0) }

    SettingsScaffold("Seguridad y credenciales", onBack, scroll) {
        CredentialStatusCard(s.auth)
        s.error?.let { StatusBanner(Tone.Error, "No se pudo guardar", it) }
        s.message?.let { StatusBanner(Tone.Success, "Listo", it) }

        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            ChoiceChip(s.method == AuthMethod.TEMPORARY, "Temporales (recomendado)", { vm.setMethod(AuthMethod.TEMPORARY) })
            ChoiceChip(s.method == AuthMethod.STATIC, "Estáticas", { vm.setMethod(AuthMethod.STATIC) })
        }

        if (s.method == AuthMethod.TEMPORARY) TemporaryForm(vm, s) else StaticForm(vm, s)

        Surface(
            shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth().animateContentSize(),
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (showProtection) "Ocultar" else "Mostrar", onClick = { showProtection = !showProtection })
                        .heightIn(min = Space.touch).padding(Space.l),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.m),
                ) {
                    Icon(Icons.Filled.Lock, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Cómo se protegen", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Icon(if (showProtection) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (showProtection) {
                    Text(
                        "• Se guardan cifradas con AES-256-GCM; la clave vive en Android Keystore (con hardware seguro si el dispositivo lo ofrece).\n" +
                            "• Nunca se guardan en Room, preferencias, logs ni en la nube de Android (copias de seguridad desactivadas).\n" +
                            "• Esta pantalla bloquea capturas.\n" +
                            "• Limitación: en un dispositivo comprometido (root/malware) Android Keystore no impide que una app con el mismo UID use la clave. Usa credenciales de mínimo privilegio y revócalas si pierdes el dispositivo.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = Space.l, end = Space.l, bottom = Space.l),
                    )
                }
            }
        }
        QuietButton(
            "Eliminar credenciales de este dispositivo", { confirmDelete = true },
            Modifier.fillMaxWidth(), enabled = s.auth !is AuthStatus.Missing, destructive = true,
        )
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "¿Eliminar credenciales?",
            text = "Se borran de este dispositivo. Los respaldos pendientes se conservan y se reanudarán cuando introduzcas credenciales nuevas. No se elimina nada en S3.",
            confirmLabel = "Eliminar",
            destructive = true,
            onConfirm = { vm.delete(); confirmDelete = false },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun CredentialStatusCard(auth: AuthStatus) {
    val (tone, title, body) = when (auth) {
        AuthStatus.Missing -> Triple(Tone.Info, "Sin credenciales", "Introduce credenciales para poder subir y descargar.")
        is AuthStatus.Valid -> Triple(
            Tone.Success,
            "Credenciales ${if (auth.info.method == AuthMethod.TEMPORARY) "temporales" else "estáticas"} activas",
            "Access key ${auth.info.accessKeyIdHint}" + (auth.info.expiration?.let { " · caducan ${remaining(it)} (${formatDateTime(it.toEpochMilli())})" } ?: " · sin caducidad"),
        )
        is AuthStatus.ExpiringSoon -> Triple(
            Tone.Warning,
            "Caducan pronto",
            "Access key ${auth.info.accessKeyIdHint} · caducan ${auth.info.expiration?.let { remaining(it) }}. Pega credenciales nuevas antes de que expiren; esta app no puede renovarlas sola.",
        )
        is AuthStatus.Expired -> Triple(
            Tone.Error,
            "Credenciales caducadas",
            "Las transferencias están detenidas y el trabajo pendiente se conserva. Introduce credenciales nuevas.",
        )
    }
    StatusBanner(tone, title, body)
}

private fun remaining(exp: Instant): String {
    val d = Duration.between(Instant.now(), exp)
    return when {
        d.isNegative -> "ya"
        d.toMinutes() < 1 -> "en menos de 1 min"
        d.toMinutes() < 60 -> "en ${d.toMinutes()} min"
        d.toHours() < 48 -> "en ${d.toHours()} h ${d.toMinutes() % 60} min"
        else -> "en ${d.toDays()} días"
    }
}

@Composable
private fun TemporaryForm(vm: SecurityViewModel, s: SecurityUiState) {
    Text(
        "Obtén credenciales temporales de una identidad que ya tengas configurada (por ejemplo `aws sts assume-role`, `aws sts get-session-token` o `aws configure export-credentials`) y pega aquí el resultado. Ver docs/authentication.md. " +
            "La app no las renueva sola: cuando caduquen tendrás que pegar unas nuevas.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = s.pasted, onValueChange = { v -> vm.update { it.copy(pasted = v) } },
        label = { Text("Pegar salida de la CLI (JSON o AWS_…=…)") },
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation(),
    )
    SecondaryButton("Leer datos pegados", vm::parsePasted, Modifier.fillMaxWidth(), enabled = s.pasted.isNotBlank())
    SectionHeader("Datos de la credencial")
    SecretField("Access key ID (ASIA…)", s.accessKey, secret = false) { v -> vm.update { it.copy(accessKey = v.trim()) } }
    SecretField("Secret access key", s.secret) { v -> vm.update { it.copy(secret = v.trim()) } }
    SecretField("Session token", s.sessionToken) { v -> vm.update { it.copy(sessionToken = v.trim()) } }
    OutlinedTextField(
        value = s.expiration, onValueChange = { v -> vm.update { it.copy(expiration = v.trim()) } },
        label = { Text("Expiración (ISO-8601, UTC)") }, placeholder = { Text("2026-10-22T18:30:00Z") },
        singleLine = true, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
    )
    PrimaryButton("Guardar credenciales temporales", vm::saveTemporary, Modifier.fillMaxWidth())
}

@Composable
private fun StaticForm(vm: SecurityViewModel, s: SecurityUiState) {
    StatusBanner(
        Tone.Warning, "Método para usuarios avanzados",
        "Una clave permanente no caduca: quien la obtenga puede usarla hasta que la desactives en IAM. " +
            "Usa un usuario IAM dedicado, sin permisos administrativos, limitado a tu bucket y prefijo (docs/iam-policy.md). " +
            "Nunca uses claves de la cuenta raíz.",
    )
    SecretField("Access key ID (AKIA…)", s.accessKey, secret = false) { v -> vm.update { it.copy(accessKey = v.trim()) } }
    SecretField("Secret access key", s.secret) { v -> vm.update { it.copy(secret = v.trim()) } }
    PrimaryButton("Guardar claves estáticas", vm::saveStatic, Modifier.fillMaxWidth())
}

@Composable
private fun SecretField(label: String, value: String, secret: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Password),
    )
}

// ------------------------------------------------------------------ General

@Composable
fun GeneralSettingsScreen(vm: GeneralSettingsViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    SettingsScaffold("Ajustes generales", onBack) {
        SectionHeader("Apariencia")
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            listOf(ThemeMode.SYSTEM to "Sistema", ThemeMode.LIGHT to "Claro", ThemeMode.DARK to "Oscuro").forEach { (m, l) ->
                ChoiceChip(s.theme == m, l, { vm.setTheme(m) })
            }
        }

        SectionHeader("Respaldo")
        SettingsGroup {
            SwitchRow("Respaldo automático", "Busca fotos nuevas y las sube periódicamente cuando Android lo permite.", s.backup.autoBackup, vm::setAutoBackup)
            if (s.backup.autoBackup) {
                GroupDivider()
                Column(Modifier.padding(horizontal = Space.l, vertical = Space.m)) {
                    Text("Frecuencia mínima: cada ${s.backup.intervalHours} h", style = MaterialTheme.typography.bodyLarge)
                    Slider(value = s.backup.intervalHours.toFloat(), onValueChange = { vm.setInterval(it.toInt()) }, valueRange = 1f..24f, steps = 22)
                    Text(
                        "Android puede retrasar los trabajos periódicos; no se garantiza la hora exacta.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            GroupDivider()
            SwitchRow("Solo con Wi-Fi", "Evita usar datos móviles (red no medida).", s.backup.wifiOnly, vm::setWifiOnly)
            GroupDivider()
            SwitchRow(
                "Incluir vídeos en respaldos automáticos",
                "Los vídeos pesan mucho: más coste de almacenamiento y transferencia en S3. Si activas esta opción los vídeos se encolan; si no, solo los que selecciones tú.",
                s.backup.includeVideos, vm::setIncludeVideos,
            )
            GroupDivider()
            Column(Modifier.padding(horizontal = Space.l, vertical = Space.m)) {
                Text("Subidas simultáneas: ${s.backup.maxConcurrency}", style = MaterialTheme.typography.bodyLarge)
                Slider(value = s.backup.maxConcurrency.toFloat(), onValueChange = { vm.setConcurrency(it.toInt()) }, valueRange = 1f..4f, steps = 2)
            }
        }

        SectionHeader("Privacidad y datos")
        SettingsGroup {
            val points = listOf(
                "En el teléfono: tus fotos (MediaStore), un índice local (Room) con nombres, tamaños y estado de respaldo, y las credenciales cifradas.",
                "En S3: una copia de cada foto respaldada con su nombre sanitizado, tipo, fecha de captura y huella SHA-256 en los metadatos. Cualquier identidad con acceso a tu bucket puede leerlas.",
                "No es cifrado de extremo a extremo: el cifrado en reposo es el de S3 (SSE-S3 activo por defecto en buckets nuevos; puedes elegir SSE-KMS en tu bucket).",
                "Eliminar una foto del teléfono, quitarla de un álbum, cerrar sesión o desinstalar NO borra nada en S3. Esta versión no incluye borrado remoto.",
                "No hay servidores del desarrollador, cuentas, anuncios ni telemetría.",
            )
            Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                points.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Text(
            "Versión ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.s),
        )
    }
}
