package app.s3vault.presentation.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.s3vault.AppContainer
import app.s3vault.domain.model.MediaItem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class GalleryViewModel(private val c: AppContainer) : ViewModel() {
    /** `null` mientras Room emite la primera lectura (estado de carga). */
    val items: StateFlow<List<MediaItem>?> = c.galleryRepository.items
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val albums = c.albumRepository.albums
    val settings = c.settings.settings

    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    /** Reindexa MediaStore (local, sin red). */
    fun refresh() {
        if (_scanning.value) return
        viewModelScope.launch {
            _scanning.value = true
            try {
                c.galleryRepository.refreshIfPermitted()
                // Si el respaldo automático está activo, lo nuevo entra en la cola.
                val s = c.settingsSnapshot()
                if (s.autoBackup && !s.paused && s.s3Config != null && c.backupRepository.enqueuePending() > 0) {
                    c.workScheduler.enqueueBackup(s.wifiOnly)
                }
            } catch (e: Exception) {
                _messages.tryEmit("No se pudo leer la galería del dispositivo.")
            } finally {
                _scanning.value = false
            }
        }
    }

    fun toggle(id: String) = _selection.update { if (id in it) it - id else it + id }
    fun startSelection(id: String) = _selection.update { it + id }
    fun clearSelection() { _selection.value = emptySet() }
    fun selectAll() { _selection.value = items.value.orEmpty().map { it.assetId }.toSet() }

    fun backupSelected() {
        val ids = _selection.value.toList()
        viewModelScope.launch {
            val s = c.settingsSnapshot()
            if (s.s3Config == null) { _messages.tryEmit("Configura primero tu bucket de Amazon S3 (Ajustes)."); return@launch }
            val n = c.backupRepository.enqueue(ids)
            _messages.tryEmit(
                when {
                    n == 0 -> "Nada que respaldar: ya están respaldadas, en cola o excluidas (vídeos)."
                    s.paused -> "$n en cola. El respaldo está en pausa; reanúdalo en la pestaña Respaldo."
                    else -> "$n en cola. El sistema las subirá cuando se cumplan las condiciones de red."
                },
            )
            clearSelection()
        }
    }

    fun addSelectionToAlbum(albumId: Long) {
        val ids = _selection.value.toList()
        viewModelScope.launch { c.albumRepository.add(albumId, ids); _messages.tryEmit("Añadidas al álbum."); clearSelection() }
    }

    fun createAlbumWithSelection(name: String) {
        val ids = _selection.value.toList()
        viewModelScope.launch {
            val id = c.albumRepository.create(name)
            c.albumRepository.add(id, ids)
            _messages.tryEmit("Álbum «${name.trim()}» creado.")
            clearSelection()
        }
    }

    fun onDeleted() { clearSelection(); refresh() }
}
