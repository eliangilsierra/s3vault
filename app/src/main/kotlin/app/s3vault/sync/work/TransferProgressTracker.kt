package app.s3vault.sync.work

import app.s3vault.domain.repo.ProgressSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class TransferProgress(val doneBytes: Long, val totalBytes: Long) {
    val fraction: Float get() = if (totalBytes <= 0) 0f else (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

/**
 * Progreso efímero por archivo para la interfaz. No es la cola: la cola vive en Room, de modo que
 * perder este estado (muerte del proceso) no pierde trabajo.
 */
class TransferProgressTracker : ProgressSink {
    private val _state = MutableStateFlow<Map<String, TransferProgress>>(emptyMap())
    val state: StateFlow<Map<String, TransferProgress>> = _state.asStateFlow()

    override fun onProgress(assetId: String, doneBytes: Long, totalBytes: Long) {
        _state.update { current ->
            if (totalBytes > 0 && doneBytes >= totalBytes) return@update current - assetId
            val previous = current[assetId]
            val newFraction = if (totalBytes > 0) doneBytes.toFloat() / totalBytes else 0f
            // Limitar las actualizaciones a saltos de ~1 % para no saturar la interfaz.
            if (previous != null && newFraction - previous.fraction < 0.01f) current
            else current + (assetId to TransferProgress(doneBytes, totalBytes))
        }
    }

    fun clear() = _state.update { emptyMap() }
}
