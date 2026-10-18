package app.s3vault.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.s3vault.AppContainer
import app.s3vault.auth.AccountInfo
import app.s3vault.auth.CredentialInputError
import app.s3vault.auth.CredentialInputResult
import app.s3vault.auth.CredentialStatus
import app.s3vault.auth.CredentialTextParser
import app.s3vault.auth.IdentityCenterSignIn
import app.s3vault.auth.OidcTokens
import app.s3vault.logging.Redactor
import app.s3vault.logging.SecureLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.io.IOException

/** Progreso del inicio de sesión con IAM Identity Center. */
sealed interface IdentityCenterUi {
    data object Idle : IdentityCenterUi
    data object Starting : IdentityCenterUi
    data class WaitingApproval(val userCode: String, val verificationUrl: String) : IdentityCenterUi
    data class ChooseAccount(val accounts: List<AccountInfo>) : IdentityCenterUi
    data class ChooseRole(val accountId: String, val roles: List<String>) : IdentityCenterUi
    data object Saving : IdentityCenterUi
    data class Failed(val message: String) : IdentityCenterUi
}

class SecurityViewModel(private val c: AppContainer) : ViewModel() {
    /** Estado local de credenciales, sin red; se refresca cada 15 s para mostrar la cuenta atrás. */
    val status: Flow<CredentialStatus> = flow {
        while (true) { emit(c.credentials.status()); delay(15_000) }
    }

    private val _errors = MutableStateFlow<List<CredentialInputError>>(emptyList())
    val errors: StateFlow<List<CredentialInputError>> = _errors
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    private val _ic = MutableStateFlow<IdentityCenterUi>(IdentityCenterUi.Idle)
    val ic: StateFlow<IdentityCenterUi> = _ic
    private val _parsedHint = MutableStateFlow<ParsedFields?>(null)
    val parsedHint: StateFlow<ParsedFields?> = _parsedHint

    private var signInJob: Job? = null
    private var pending: IdentityCenterSignIn.Pending? = null
    private var tokens: OidcTokens? = null

    data class ParsedFields(val accessKeyId: String, val secret: String, val token: String, val expiration: String)

    fun clearMessages() { _errors.value = emptyList(); _message.value = null }

    /** Interpreta texto pegado (salida de la AWS CLI, variables de entorno…). No guarda nada. */
    fun parsePasted(text: String) {
        val p = CredentialTextParser.parse(text)
        p.secretAccessKey?.let(Redactor::registerSecret)
        p.sessionToken?.let(Redactor::registerSecret)
        _parsedHint.value = ParsedFields(p.accessKeyId.orEmpty(), p.secretAccessKey.orEmpty(), p.sessionToken.orEmpty(), p.expiration?.toString().orEmpty())
        if (p.accessKeyId == null && p.secretAccessKey == null) _message.value = "No se reconoció ninguna credencial en el texto pegado."
    }

    fun consumeParsedHint() { _parsedHint.value = null }

    fun saveTemporary(accessKeyId: String, secret: String, token: String, expiration: String, onSaved: () -> Unit) {
        clearMessages()
        Redactor.registerSecret(secret); Redactor.registerSecret(token)
        val exp = CredentialTextParser.parseInstant(expiration)
        viewModelScope.launch {
            when (val r = c.credentials.saveTemporary(accessKeyId, secret, token, exp)) {
                is CredentialInputResult.Ok -> { afterSaved(); onSaved() }
                is CredentialInputResult.Rejected -> _errors.value = r.errors
            }
        }
    }

    fun saveStatic(accessKeyId: String, secret: String, onSaved: () -> Unit) {
        clearMessages()
        Redactor.registerSecret(secret)
        viewModelScope.launch {
            when (val r = c.credentials.saveStatic(accessKeyId, secret)) {
                is CredentialInputResult.Ok -> { afterSaved(); onSaved() }
                is CredentialInputResult.Rejected -> _errors.value = r.errors
            }
        }
    }

    private suspend fun afterSaved() {
        _message.value = "Credenciales guardadas cifradas en este dispositivo. Usa «Probar conexión» para comprobarlas."
        c.backupRepository.resumeAfterUserAction()
    }

    fun clearCredentials() = viewModelScope.launch {
        c.workScheduler.cancelBackup()
        c.credentials.clear()
        Redactor.clearKnownSecrets()
        _message.value = "Credenciales eliminadas de este dispositivo. Los objetos en S3 no se tocaron."
    }

    // ---- IAM Identity Center -------------------------------------------------------------

    fun startIdentityCenter(startUrl: String, ssoRegion: String) {
        signInJob?.cancel()
        _ic.value = IdentityCenterUi.Starting
        signInJob = viewModelScope.launch {
            try {
                when (val start = c.identityCenterSignIn.begin(startUrl, ssoRegion)) {
                    IdentityCenterSignIn.StartResult.InvalidInput -> {
                        _ic.value = IdentityCenterUi.Failed("La URL de inicio debe ser https:// y la región válida (p. ej. eu-west-1).")
                    }
                    is IdentityCenterSignIn.StartResult.Started -> {
                        pending = start.pending
                        val a = start.pending.authorization
                        _ic.value = IdentityCenterUi.WaitingApproval(a.userCode, a.verificationUriComplete ?: a.verificationUri)
                        when (val approval = c.identityCenterSignIn.awaitApproval(start.pending)) {
                            is IdentityCenterSignIn.Approval.Granted -> {
                                tokens = approval.tokens
                                val accounts = c.identityCenterSignIn.accounts(start.pending, approval.tokens)
                                _ic.value = when {
                                    accounts.isEmpty() -> IdentityCenterUi.Failed("Tu usuario no tiene cuentas asignadas en IAM Identity Center.")
                                    accounts.size == 1 -> loadRoles(accounts[0].accountId)
                                    else -> IdentityCenterUi.ChooseAccount(accounts)
                                }
                            }
                            IdentityCenterSignIn.Approval.Denied -> _ic.value = IdentityCenterUi.Failed("Rechazaste la autorización en el navegador.")
                            IdentityCenterSignIn.Approval.Expired -> _ic.value = IdentityCenterUi.Failed("El código caducó. Inicia el proceso de nuevo.")
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ic.value = IdentityCenterUi.Failed(describe(e))
            }
        }
    }

    fun chooseAccount(accountId: String) {
        viewModelScope.launch {
            _ic.value = try { loadRoles(accountId) } catch (e: CancellationException) { throw e } catch (e: Exception) { IdentityCenterUi.Failed(describe(e)) }
        }
    }

    private suspend fun loadRoles(accountId: String): IdentityCenterUi {
        val p = pending ?: return IdentityCenterUi.Idle
        val t = tokens ?: return IdentityCenterUi.Idle
        val roles = c.identityCenterSignIn.roles(p, t, accountId)
        return when {
            roles.isEmpty() -> IdentityCenterUi.Failed("No hay roles asignados a tu usuario en esa cuenta.")
            roles.size == 1 -> { finish(accountId, roles[0]); IdentityCenterUi.Saving }
            else -> IdentityCenterUi.ChooseRole(accountId, roles)
        }
    }

    fun chooseRole(accountId: String, role: String) {
        _ic.value = IdentityCenterUi.Saving
        viewModelScope.launch { finish(accountId, role) }
    }

    private suspend fun finish(accountId: String, role: String) {
        val p = pending; val t = tokens
        if (p == null || t == null) { _ic.value = IdentityCenterUi.Idle; return }
        try {
            val ok = c.identityCenterSignIn.finish(p, t, accountId, role)
            if (ok) {
                pending = null; tokens = null
                _ic.value = IdentityCenterUi.Idle
                afterSaved()
                _message.value = "Sesión de IAM Identity Center guardada. Las credenciales se renovarán solas mientras la sesión lo permita."
            } else {
                _ic.value = IdentityCenterUi.Failed("AWS rechazó la solicitud de credenciales para ese rol.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _ic.value = IdentityCenterUi.Failed(describe(e))
        }
    }

    fun cancelIdentityCenter() {
        signInJob?.cancel(); pending = null; tokens = null
        _ic.value = IdentityCenterUi.Idle
    }

    private fun describe(e: Exception): String {
        SecureLog.w("Security", "Fallo en el inicio de sesión de Identity Center", e)
        return if (e is IOException) "Sin conexión con AWS. Inténtalo de nuevo." else "AWS rechazó la solicitud o devolvió un error inesperado."
    }
}
