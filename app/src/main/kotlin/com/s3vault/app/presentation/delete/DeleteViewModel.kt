package com.s3vault.app.presentation.delete

import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.s3vault.app.AppContainer
import com.s3vault.app.data.local.PlatformMediaDelete
import com.s3vault.app.domain.MediaItem
import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
import com.s3vault.app.domain.RemoteObject
import com.s3vault.app.security.SafeLog
import com.s3vault.app.sync.CloudDeleteResult
import com.s3vault.app.sync.DeleteEntry
import com.s3vault.app.sync.DeleteImpact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

fun MediaItem.toDeleteEntry() = DeleteEntry(displayName, mediaStoreId, uri, remoteKey, hasLocalCopy = present)

/** Every state the deletion flow can be in. The UI renders it; nothing destructive happens before a Confirm* state is accepted. */
sealed interface DeleteUiState {
    data object Idle : DeleteUiState

    /** "Which copy?" — the user must pick exactly one location. */
    data class Choose(val entries: List<DeleteEntry>, val impact: DeleteImpact) : DeleteUiState

    data class ConfirmDevice(val entries: List<DeleteEntry>, val impact: DeleteImpact) : DeleteUiState

    data class ConfirmCloud(val entries: List<DeleteEntry>, val impact: DeleteImpact, val bucket: String?) : DeleteUiState

    /** [total] <= 0 means indeterminate. */
    data class Working(val message: String, val done: Int, val total: Int, val cancellable: Boolean) : DeleteUiState

    /** Android's own confirmation dialog. The host launches [request] once ([launched] guards against re-launching after rotation). */
    data class SystemDialog(val request: IntentSender, val launched: Boolean) : DeleteUiState

    data class CloudFailed(
        val result: CloudDeleteResult,
        val total: Int,
        val needsCredentials: Boolean,
        val summary: String,
    ) : DeleteUiState
}

sealed interface DeleteEvent {
    /** A destructive operation was accepted: screens leave selection mode. */
    data object Started : DeleteEvent

    data class Message(val text: String) : DeleteEvent
}

/**
 * Deletion of local files and of S3 objects. They are two distinct operations with their own confirmation, and this
 * class has no path that performs both from a single user choice.
 */
class DeleteViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<DeleteUiState>(DeleteUiState.Idle)
    val state: StateFlow<DeleteUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<DeleteEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<DeleteEvent> = _events.asSharedFlow()

    private var job: Job? = null

    // Local deletion bookkeeping: chunks still to confirm with Android, and everything that was requested.
    private val chunks = ArrayDeque<List<DeleteEntry>>()
    private var requested: List<DeleteEntry> = emptyList()
    private var currentChunk: List<DeleteEntry> = emptyList()

    private var lastCloudEntries: List<DeleteEntry> = emptyList()

    // ------------------------------------------------------------------ entry points

    /** Local files (gallery, viewer, albums): the user chooses which copy to delete. */
    fun request(entries: List<DeleteEntry>) {
        if (entries.isEmpty() || _state.value != DeleteUiState.Idle) return
        _state.value = DeleteUiState.Choose(entries, DeleteImpact.of(entries))
    }

    /**
     * S3 library: only the cloud copy can be deleted from there. Whether a copy also exists on this device decides how
     * strong the warning is, so it is checked against the real files right now rather than trusting the list on screen.
     */
    fun requestCloud(objects: List<RemoteObject>) {
        if (objects.isEmpty() || _state.value != DeleteUiState.Idle) return
        viewModelScope.launch {
            val onDevice = objects.map { it.key }.chunked(400).flatMap { c.db.media().withRemoteKeys(it) }
                .filter { it.present }.mapNotNull { it.remoteKey }.toSet()
            val entries = objects.map { o ->
                val restored = c.db.remote().get(o.key)?.restoredUri
                val hasLocal = o.key in onDevice || (restored != null && c.mediaGateway.exists(restored))
                DeleteEntry(o.displayName, null, null, o.key, hasLocalCopy = hasLocal)
            }
            if (_state.value == DeleteUiState.Idle) showCloudConfirmation(entries)
        }
    }

    fun chooseDevice() {
        val s = _state.value as? DeleteUiState.Choose ?: return
        val impact = s.impact
        if (impact.onDevice == 0) return
        _state.value = DeleteUiState.ConfirmDevice(s.entries, impact)
    }

    fun chooseCloud() {
        val s = _state.value as? DeleteUiState.Choose ?: return
        if (s.impact.inCloud == 0) return
        showCloudConfirmation(s.entries)
    }

    fun dismiss() {
        if (_state.value is DeleteUiState.Working || _state.value is DeleteUiState.SystemDialog) return
        _state.value = DeleteUiState.Idle
    }

    // ------------------------------------------------------------------ device

    /** [onlyBackedUp]: skip the files that exist nowhere else. */
    fun confirmDevice(onlyBackedUp: Boolean = false) {
        val s = _state.value as? DeleteUiState.ConfirmDevice ?: return
        val targets = s.entries.filter { it.hasLocalCopy && it.uri != null && (!onlyBackedUp || it.remoteKey != null) }
        if (targets.isEmpty()) { _state.value = DeleteUiState.Idle; return }
        requested = targets
        chunks.clear()
        // One Android dialog per chunk (binder limits) on Android 11+, one per file on Android 10.
        targets.chunked(if (PlatformMediaDelete.supportsBatchRequest) DEVICE_CHUNK else 1).forEach { chunks.addLast(it) }
        _events.tryEmit(DeleteEvent.Started)
        nextChunk()
    }

    fun markDialogLaunched() {
        val s = _state.value as? DeleteUiState.SystemDialog ?: return
        _state.value = s.copy(launched = true)
    }

    fun onSystemDialogResult(accepted: Boolean) {
        if (_state.value !is DeleteUiState.SystemDialog) return
        if (!accepted) { chunks.clear(); finishDevice(); return }
        if (!PlatformMediaDelete.supportsBatchRequest) {
            // Android 10: consent was granted for this one file, now the delete itself can go through.
            val uri = Uri.parse(currentChunk.single().uri!!)
            _state.value = DeleteUiState.Working("Eliminando…", 0, 0, cancellable = false)
            viewModelScope.launch {
                PlatformMediaDelete.deleteDirect(c.appContext.contentResolver, uri)
                nextChunk()
            }
        } else {
            nextChunk()
        }
    }

    private fun nextChunk() {
        val chunk = chunks.removeFirstOrNull()
        if (chunk == null) { finishDevice(); return }
        currentChunk = chunk
        if (PlatformMediaDelete.supportsBatchRequest) {
            try {
                val sender = PlatformMediaDelete.batchRequest(c.appContext, chunk.map { Uri.parse(it.uri!!) })
                _state.value = DeleteUiState.SystemDialog(sender, launched = false)
            } catch (e: Exception) {
                SafeLog.w(TAG, "Android refused to build the delete request: " + e::class.java.simpleName)
                chunks.clear()
                finishDevice()
            }
        } else {
            val uri = Uri.parse(chunk.single().uri!!)
            _state.value = DeleteUiState.Working("Eliminando…", 0, 0, cancellable = false)
            viewModelScope.launch {
                when (val r = PlatformMediaDelete.deleteDirect(c.appContext.contentResolver, uri)) {
                    PlatformMediaDelete.Direct.Deleted, PlatformMediaDelete.Direct.Failed -> nextChunk()
                    is PlatformMediaDelete.Direct.NeedsConsent -> _state.value = DeleteUiState.SystemDialog(r.intentSender, launched = false)
                }
            }
        }
    }

    /** Whatever the dialogs said, the index is settled from what Android really did. */
    private fun finishDevice() {
        val entries = requested
        _state.value = DeleteUiState.Working("Actualizando la biblioteca…", 0, 0, cancellable = false)
        viewModelScope.launch {
            val text = try {
                val r = c.localDeletion.settle(entries)
                when {
                    r.deleted == 0 -> "No se eliminó nada del dispositivo."
                    else -> buildString {
                        append("Eliminadas del dispositivo: ${r.deleted}.")
                        if (r.keptInCloud > 0) append(" ${r.keptInCloud} siguen en S3.")
                        if (r.lost > 0) append(" ${r.lost} no tenían copia en S3.")
                        if (r.remaining > 0) append(" ${r.remaining} no se eliminaron.")
                    }
                }
            } catch (e: Exception) {
                SafeLog.e(TAG, "Settling local deletion failed", e)
                "Se eliminaron archivos, pero no se pudo actualizar la biblioteca. Desliza para actualizar."
            }
            _state.value = DeleteUiState.Idle
            _events.tryEmit(DeleteEvent.Message(text))
        }
    }

    // ------------------------------------------------------------------ cloud

    private fun showCloudConfirmation(entries: List<DeleteEntry>) {
        viewModelScope.launch {
            val bucket = c.settings.currentS3Config()?.bucket
            _state.value = DeleteUiState.ConfirmCloud(entries, DeleteImpact.of(entries), bucket)
        }
    }

    fun confirmCloud() {
        val s = _state.value as? DeleteUiState.ConfirmCloud ?: return
        runCloud(s.entries.filter { it.remoteKey != null })
    }

    fun retryCloud() {
        val s = _state.value as? DeleteUiState.CloudFailed ?: return
        val failed = s.result.failedKeys.toSet()
        runCloud(lastCloudEntries.filter { it.remoteKey in failed })
    }

    private fun runCloud(entries: List<DeleteEntry>) {
        val keys = entries.mapNotNull { it.remoteKey }.distinct()
        if (keys.isEmpty()) { _state.value = DeleteUiState.Idle; return }
        lastCloudEntries = entries
        _events.tryEmit(DeleteEvent.Started)
        var processed = 0
        _state.value = DeleteUiState.Working("Eliminando de S3…", 0, keys.size, cancellable = true)
        job = viewModelScope.launch {
            try {
                val r = c.cloudDeletion.delete(keys) { done, total ->
                    processed = done
                    _state.value = DeleteUiState.Working("Eliminando de S3…", done, total, cancellable = true)
                }
                finishCloud(r, keys.size)
            } catch (e: CancellationException) {
                _state.value = DeleteUiState.Idle
                _events.tryEmit(DeleteEvent.Message("Cancelado tras procesar $processed de ${keys.size}. Lo ya eliminado de S3 no se puede recuperar."))
                throw e
            } catch (e: RemoteException) {
                _state.value = DeleteUiState.Idle
                _events.tryEmit(DeleteEvent.Message(e.message ?: e.kind.userMessage))
            } catch (e: Exception) {
                SafeLog.e(TAG, "Cloud deletion failed unexpectedly", e)
                _state.value = DeleteUiState.Idle
                _events.tryEmit(DeleteEvent.Message(RemoteErrorKind.UNKNOWN.userMessage))
            }
        }
    }

    fun cancelWork() {
        job?.cancel()
    }

    private fun finishCloud(r: CloudDeleteResult, requestedKeys: Int) {
        if (r.failed.isEmpty()) {
            _state.value = DeleteUiState.Idle
            val skipped = if (r.skippedRestoring > 0) " ${r.skippedRestoring} se están restaurando y no se tocaron." else ""
            _events.tryEmit(DeleteEvent.Message("Eliminadas de S3: ${r.deleted.size}. Las copias del dispositivo no se han tocado.$skipped"))
            return
        }
        val kinds = r.failed.values.groupingBy { it }.eachCount()
        val summary = kinds.entries.joinToString("\n") { (k, n) -> "• " + (if (n == 1) "1 elemento" else "$n elementos") + ": ${deleteFailureText(k)}" }
        _state.value = DeleteUiState.CloudFailed(
            result = r, total = requestedKeys,
            needsCredentials = kinds.keys.any { it == RemoteErrorKind.CREDENTIALS_EXPIRED || it == RemoteErrorKind.CREDENTIALS_MISSING || it == RemoteErrorKind.CREDENTIALS_INVALID },
            summary = summary,
        )
    }

    companion object {
        private const val TAG = "DeleteViewModel"
        private const val DEVICE_CHUNK = 500

        fun deleteFailureText(k: RemoteErrorKind): String = when (k) {
            RemoteErrorKind.ACCESS_DENIED ->
                "tu política IAM no permite borrar objetos. Es lo habitual: la política recomendada lo prohíbe a propósito. " +
                    "Para activarlo sigue docs/iam-policy.md («Permitir borrar desde la app»)."
            else -> k.userMessage
        }
    }
}
