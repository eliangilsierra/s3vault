package app.s3vault.presentation.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.s3vault.AppContainer
import app.s3vault.domain.model.MediaItem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** [source] es `gallery` o `album:{id}`: define entre qué fotos se navega. */
class ViewerViewModel(private val c: AppContainer, source: String) : ViewModel() {
    val items: StateFlow<List<MediaItem>?> = (
        if (source.startsWith("album:")) c.albumRepository.items(source.removePrefix("album:").toLong())
        else c.galleryRepository.items
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val albums = c.albumRepository.albums
    fun albumIdsOf(assetId: String) = c.albumRepository.albumIdsOf(assetId)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    fun backup(assetId: String) = viewModelScope.launch {
        if (c.settingsSnapshot().s3Config == null) {
            _messages.tryEmit("Configura primero tu bucket de Amazon S3 (Ajustes).")
            return@launch
        }
        val n = c.backupRepository.enqueue(listOf(assetId))
        _messages.tryEmit(if (n > 0) "En cola para respaldar." else "Ya está respaldada, en cola o excluida (vídeo).")
    }

    fun toggleAlbum(albumId: Long, assetId: String, currentlyIn: Boolean) = viewModelScope.launch {
        if (currentlyIn) c.albumRepository.remove(albumId, listOf(assetId)) else c.albumRepository.add(albumId, listOf(assetId))
    }

    fun afterDelete() = viewModelScope.launch { c.galleryRepository.refreshIfPermitted() }
}
