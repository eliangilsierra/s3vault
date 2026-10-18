package app.s3vault.presentation.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.s3vault.AppContainer
import app.s3vault.data.remote.RemoteHead
import app.s3vault.data.repo.RemoteListItem
import app.s3vault.data.repo.RemoteRefreshResult
import app.s3vault.domain.remote.ObjectMetadata
import app.s3vault.presentation.Strings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RemoteDetails(val key: String, val head: RemoteHead?, val metadata: ObjectMetadata?, val error: String?)

class RemoteViewModel(private val c: AppContainer) : ViewModel() {
    private val repo = c.remoteLibraryRepository
    val items: StateFlow<List<RemoteListItem>?> = repo.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val lastRefresh = repo.lastRefreshMs
    val settings = c.settings.settings

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing
    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection
    private val _details = MutableStateFlow<RemoteDetails?>(null)
    val details: StateFlow<RemoteDetails?> = _details
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            try {
                when (val r = repo.refresh()) {
                    is RemoteRefreshResult.Success -> _messages.tryEmit("${r.count} objetos en tu bucket.")
                    RemoteRefreshResult.NotConfigured -> _messages.tryEmit("Configura primero tu bucket de Amazon S3.")
                    is RemoteRefreshResult.Failure -> _messages.tryEmit(Strings.failure(r.failure))
                }
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun toggle(key: String) = _selection.update { if (key in it) it - key else it + key }
    fun clearSelection() { _selection.value = emptySet() }

    fun restore(keys: List<String>) = viewModelScope.launch {
        repo.restore(keys)
        _messages.tryEmit("Restauración en cola (${keys.size}). Se guardará en Imágenes/S3Vault sin sobrescribir nada.")
        clearSelection()
    }

    fun showDetails(key: String) = viewModelScope.launch {
        _details.value = RemoteDetails(key, null, null, null)
        val r = repo.head(key)
        _details.value = r.fold(
            onSuccess = { h -> RemoteDetails(key, h, h?.let { ObjectMetadata.fromMap(it.userMetadata) }, if (h == null) "El objeto ya no existe en S3." else null) },
            onFailure = { e ->
                val f = (e as? app.s3vault.domain.remote.RemoteException)?.failure
                RemoteDetails(key, null, null, f?.let(Strings::failure) ?: "No se pudieron leer los metadatos.")
            },
        )
    }

    fun closeDetails() { _details.value = null }
    fun dismissError(key: String) = viewModelScope.launch { repo.dismissRestoreError(key) }
}
