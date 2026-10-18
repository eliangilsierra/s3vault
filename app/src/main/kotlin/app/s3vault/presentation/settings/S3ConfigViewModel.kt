package app.s3vault.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.s3vault.AppContainer
import app.s3vault.data.remote.ConnectionReport
import app.s3vault.domain.model.ConfigError
import app.s3vault.domain.model.ConfigValidation
import app.s3vault.domain.model.S3Config
import app.s3vault.domain.model.ServerSideEncryption
import app.s3vault.logging.SecureLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class S3Form(
    val region: String = "",
    val bucket: String = "",
    val prefix: String = "",
    val sse: ServerSideEncryption = ServerSideEncryption.BUCKET_DEFAULT,
)

class S3ConfigViewModel(private val c: AppContainer) : ViewModel() {
    private val _form = MutableStateFlow(S3Form())
    val form: StateFlow<S3Form> = _form
    private val _errors = MutableStateFlow<List<ConfigError>>(emptyList())
    val errors: StateFlow<List<ConfigError>> = _errors
    private val _report = MutableStateFlow<ConnectionReport?>(null)
    val report: StateFlow<ConnectionReport?> = _report
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _info = MutableStateFlow<String?>(null)
    val info: StateFlow<String?> = _info
    val settings = c.settings.settings

    init {
        viewModelScope.launch {
            val s = c.settingsSnapshot()
            _form.value = S3Form(s.region, s.bucket, s.prefix, s.sse)
        }
    }

    fun update(transform: (S3Form) -> S3Form) {
        _form.value = transform(_form.value)
        _errors.value = emptyList()
        _report.value = null
        _info.value = null
    }

    private fun validate(): S3Config? {
        val f = _form.value
        return when (val v = S3Config.create(f.region, f.bucket, f.prefix, f.sse)) {
            is ConfigValidation.Valid -> v.config.also { _errors.value = emptyList() }
            is ConfigValidation.Invalid -> { _errors.value = v.errors; null }
        }
    }

    fun save() {
        val config = validate() ?: return
        viewModelScope.launch {
            c.connectionRepository.save(config)
            // Se muestra la versión normalizada (p. ej. el prefijo termina en «/»).
            _form.value = S3Form(config.region, config.bucket, config.prefix, config.sse)
            _info.value = "Configuración guardada."
        }
    }

    /** Guarda lo escrito (si es válido) y comprueba contra AWS. Acción siempre iniciada por el usuario. */
    fun test(permissions: Boolean) {
        val config = validate() ?: return
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _report.value = null
            try {
                c.connectionRepository.save(config)
                _report.value = c.connectionRepository.test(config, permissions)
                if (_report.value?.connected == true) c.backupRepository.resumeAfterUserAction()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SecureLog.w("S3Config", "La prueba de conexión falló", e)
                _info.value = "No se pudo completar la prueba."
            } finally {
                _busy.value = false
            }
        }
    }

    fun disconnect() = viewModelScope.launch {
        c.connectionRepository.disconnect()
        _form.value = S3Form()
        _report.value = null
        _info.value = "Desconectado. Se eliminaron las credenciales y la configuración locales. Tus objetos en S3 no se tocaron."
    }

    suspend fun settingsOnce() = c.settings.settings.first()
}
