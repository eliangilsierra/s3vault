package com.s3vault.app.presentation.privacy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.s3vault.app.AppContainer
import com.s3vault.app.data.local.MediaItemEntity
import com.s3vault.app.data.privacy.PrivateMover
import com.s3vault.app.data.privacy.PrivateVaultStore
import com.s3vault.app.domain.MediaKind
import com.s3vault.app.domain.privacy.PrivateAlbum
import com.s3vault.app.domain.privacy.PrivateItem
import com.s3vault.app.domain.privacy.UnlockPrompt
import com.s3vault.app.domain.privacy.VaultAvailability
import com.s3vault.app.domain.privacy.VaultLockedException
import com.s3vault.app.presentation.delete.toDeleteEntry
import com.s3vault.app.sync.DeleteEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val UnlockText = UnlockPrompt("Álbumes privados", "Verifica tu identidad para abrirlos")

/** What the unlock attempt means for the person, in words. Null when there is nothing to say (it worked or they cancelled). */
internal fun PrivateVaultStore.UnlockOutcome.message(): String? = when (this) {
    PrivateVaultStore.UnlockOutcome.Unlocked, PrivateVaultStore.UnlockOutcome.Cancelled -> null
    PrivateVaultStore.UnlockOutcome.NeedsScreenLock -> "Para proteger los álbumes privados necesitas un PIN, patrón, contraseña o huella en este dispositivo."
    is PrivateVaultStore.UnlockOutcome.KeyLost -> reason
    is PrivateVaultStore.UnlockOutcome.Failed -> message
}

data class PrivateHubState(
    /** Null until the first check finishes. */
    val availability: VaultAvailability? = null,
    val unlocked: Boolean = false,
    val albums: List<PrivateAlbum> = emptyList(),
    val autoLockMs: Long = 60_000L,
    val busy: Boolean = false,
)

class PrivateHubViewModel(private val c: AppContainer) : ViewModel() {
    private val store get() = c.privateVault
    private val availability = MutableStateFlow<VaultAvailability?>(null)
    private val busy = MutableStateFlow(false)
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    fun dismissMessage() { _message.value = null }

    val state: StateFlow<PrivateHubState> = combine(
        availability, c.privateSession.unlocked, store.observeAlbums(), c.settings.privateAutoLockMs, busy,
    ) { a, u, albums, auto, b -> PrivateHubState(a, u, albums, auto, b) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PrivateHubState())

    init { refresh() }

    fun refresh() { viewModelScope.launch { availability.value = store.availability() } }

    /** Asks for the user's identity; the first time this is also what creates the vault. */
    fun unlock() {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try { store.unlock(UnlockText).message()?.let { _message.value = it } } finally { busy.value = false }
            availability.value = store.availability()
        }
    }

    fun lockNow() { store.lock() }

    fun createAlbum(name: String) {
        viewModelScope.launch {
            try { store.createAlbum(name) } catch (e: VaultLockedException) { _message.value = "El álbum privado está bloqueado." } catch (e: IllegalArgumentException) { _message.value = e.message }
        }
    }

    fun renameAlbum(id: Long, name: String) {
        viewModelScope.launch { try { store.renameAlbum(id, name) } catch (e: VaultLockedException) { _message.value = "El álbum privado está bloqueado." } catch (e: IllegalArgumentException) { _message.value = e.message } }
    }

    fun deleteAlbum(id: Long, name: String) {
        viewModelScope.launch {
            try { store.deleteAlbum(id); _message.value = "Álbum «$name» y sus fotos eliminados." } catch (e: VaultLockedException) { _message.value = "El álbum privado está bloqueado." }
        }
    }

    fun setAutoLock(ms: Long) {
        viewModelScope.launch { c.settings.setPrivateAutoLockMs(ms); c.privateSession.autoLockMs = ms }
    }

    fun resetLostVault() {
        viewModelScope.launch {
            store.resetLostVault()
            availability.value = store.availability()
            _message.value = "Se eliminaron los archivos protegidos que ya no se podían abrir."
        }
    }
}

data class PrivateAlbumState(val loaded: Boolean = false, val name: String? = null, val items: List<PrivateItem> = emptyList(), val selected: Set<Long> = emptySet(), val working: String? = null, val albums: List<PrivateAlbum> = emptyList())

class PrivateAlbumViewModel(private val c: AppContainer) : ViewModel() {
    private val store get() = c.privateVault
    private var albumId = -1L
    private val selected = MutableStateFlow<Set<Long>>(emptySet())
    private val working = MutableStateFlow<String?>(null)
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    fun dismissMessage() { _message.value = null }

    private val _state = MutableStateFlow(PrivateAlbumState())
    val state: StateFlow<PrivateAlbumState> = _state
    private var collector: Job? = null

    fun open(id: Long) {
        if (albumId == id && collector?.isActive == true) return
        albumId = id
        collector?.cancel()
        collector = viewModelScope.launch {
            combine(store.observeItems(id), store.observeAlbums(), selected, working) { list, albums, sel, w ->
                // A selection can only hold pictures that still exist.
                val alive = list.mapTo(HashSet()) { it.id }
                PrivateAlbumState(true, albums.firstOrNull { it.id == id }?.name, list, sel.filterTo(HashSet()) { it in alive }, w, albums)
            }.collect { _state.value = it }
        }
    }

    fun toggle(id: Long) { selected.value = selected.value.let { if (id in it) it - id else it + id } }
    fun selectAll() { selected.value = _state.value.items.mapTo(HashSet()) { it.id } }
    fun clearSelection() { selected.value = emptySet() }

    /** Puts the selected pictures back in the gallery (Pictures/S3Vault). Each sealed copy is removed only after its decrypted copy is in place. */
    fun moveOut(onDone: (restored: Int) -> Unit = {}) {
        val chosen = _state.value.items.filter { it.id in selected.value }
        if (chosen.isEmpty() || working.value != null) return
        viewModelScope.launch {
            working.value = "Devolviendo fotos a la galería…"
            try {
                val r = c.privateMover.moveOut(chosen, com.s3vault.app.MediaStoreRestoreSink(c.appContext)) { d, t -> working.value = "Devolviendo fotos a la galería… $d de $t" }
                clearSelection()
                _message.value = buildString {
                    append(if (r.restored.size == 1) "1 foto vuelve a la galería (Pictures/S3Vault)." else "${r.restored.size} fotos vuelven a la galería (Pictures/S3Vault).")
                    if (r.failed.isNotEmpty()) append(" ${r.failed.size} no se pudieron devolver y siguen protegidas.")
                    if (r.stopped) append(" Se bloqueó el álbum antes de terminar.")
                }
                onDone(r.restored.size)
            } catch (e: CancellationException) {
                throw e
            } finally { working.value = null }
        }
    }

    /** Deletes the selected pictures for good. The screen asks for confirmation first. */
    fun deleteSelected() {
        val ids = selected.value.toList()
        if (ids.isEmpty() || working.value != null) return
        viewModelScope.launch {
            working.value = "Eliminando…"
            try {
                store.remove(ids); clearSelection()
                _message.value = if (ids.size == 1) "1 foto eliminada del álbum privado." else "${ids.size} fotos eliminadas del álbum privado."
            } finally { working.value = null }
        }
    }

    fun moveSelectedTo(albumId: Long, name: String) {
        val ids = selected.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch { store.move(ids, albumId); clearSelection(); _message.value = "Movidas a «$name»." }
    }
}

// ---------------------------------------------------------------------------------------------------------------- moving pictures in

sealed interface MoveState {
    data object Idle : MoveState

    /** The vault is closed or does not exist yet: the person has to verify their identity first. */
    class NeedsUnlock(val creating: Boolean, val busy: Boolean) : MoveState

    /** The device cannot protect the vault at all. */
    data object NeedsScreenLock : MoveState

    /** Pick (or create) the album. [images] and [videos] describe the selection; only images are moved. */
    class ChooseAlbum(val albums: List<PrivateAlbum>, val images: Int, val videos: Int) : MoveState

    /** The explicit confirmation: says what will and will not happen. */
    class Confirm(val albumId: Long, val albumName: String, val images: Int, val videos: Int, val inCloud: Int) : MoveState

    class Working(val done: Int, val total: Int) : MoveState

    /** [originals] are the entries of the pictures that now have a verified copy, ready for the delete flow if the user wants it. */
    class Done(val albumName: String, val moved: Int, val skipped: Int, val failed: Int, val stopped: Boolean, val originals: List<DeleteEntry>) : MoveState
}

/**
 * The "move to a private album" flow started from a selection. It copies; it never deletes. Removing the originals is handed to the
 * existing delete flow afterwards, which asks its own questions (device or S3, with the usual warnings).
 */
class PrivateMoveViewModel(private val c: AppContainer) : ViewModel() {
    private val store get() = c.privateVault
    private val _state = MutableStateFlow<MoveState>(MoveState.Idle)
    val state: StateFlow<MoveState> = _state
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    fun dismissMessage() { _message.value = null }

    private var rows: List<MediaItemEntity> = emptyList()
    private var job: Job? = null

    fun start(ids: List<Long>) {
        if (ids.isEmpty() || _state.value != MoveState.Idle) return
        viewModelScope.launch {
            rows = withContext(Dispatchers.IO) { ids.chunked(400).flatMap { c.db.media().getAll(it) } }
            when (store.availability()) {
                VaultAvailability.NEEDS_SCREEN_LOCK -> _state.value = MoveState.NeedsScreenLock
                VaultAvailability.KEY_LOST -> { _state.value = MoveState.Idle; _message.value = "La clave de los álbumes privados se perdió. Abre «Álbumes privados» para ver qué hacer." }
                VaultAvailability.NOT_CREATED -> _state.value = MoveState.NeedsUnlock(creating = true, busy = false)
                VaultAvailability.READY -> if (c.privateSession.unlocked.value) showAlbums() else _state.value = MoveState.NeedsUnlock(creating = false, busy = false)
            }
        }
    }

    fun unlock() {
        val s = _state.value as? MoveState.NeedsUnlock ?: return
        if (s.busy) return
        viewModelScope.launch {
            _state.value = MoveState.NeedsUnlock(s.creating, busy = true)
            val out = store.unlock(UnlockPrompt("Álbumes privados", if (s.creating) "Verifica tu identidad para crear el álbum privado" else "Verifica tu identidad para abrirlos"))
            when (out) {
                PrivateVaultStore.UnlockOutcome.Unlocked -> showAlbums()
                PrivateVaultStore.UnlockOutcome.Cancelled -> _state.value = MoveState.NeedsUnlock(s.creating, busy = false)
                else -> { _state.value = MoveState.Idle; _message.value = out.message() }
            }
        }
    }

    private suspend fun showAlbums() {
        val albums = store.observeAlbums().first()
        _state.value = MoveState.ChooseAlbum(albums, rows.count { it.kind == MediaKind.IMAGE && it.present }, rows.count { it.kind == MediaKind.VIDEO })
    }

    fun chooseAlbum(album: PrivateAlbum) = confirm(album.id, album.name)

    fun createAndChoose(name: String) {
        viewModelScope.launch {
            try { confirm(store.createAlbum(name), name.trim()) } catch (e: IllegalArgumentException) { _message.value = e.message } catch (e: VaultLockedException) { _state.value = MoveState.Idle; _message.value = "El álbum privado está bloqueado." }
        }
    }

    private fun confirm(albumId: Long, name: String) {
        _state.value = MoveState.Confirm(
            albumId, name, rows.count { it.kind == MediaKind.IMAGE && it.present }, rows.count { it.kind == MediaKind.VIDEO },
            rows.count { it.kind == MediaKind.IMAGE && it.present && it.remoteKey != null },
        )
    }

    fun back() {
        when (_state.value) {
            is MoveState.Confirm -> viewModelScope.launch { showAlbums() }
            else -> cancel()
        }
    }

    /** Leaves the flow; before the work starts nothing has changed, and while it runs a cancel stops after the current picture. */
    fun cancel() {
        val s = _state.value
        if (s is MoveState.Working) { job?.cancel(); return }
        _state.value = MoveState.Idle; rows = emptyList()
    }

    fun confirmMove() {
        val s = _state.value as? MoveState.Confirm ?: return
        _state.value = MoveState.Working(0, rows.size)
        job = viewModelScope.launch {
            try {
                val r = c.privateMover.moveIn(rows, s.albumId) { d, t -> _state.value = MoveState.Working(d, t) }
                val movedIds = r.moved.toSet()
                val originals = withContext(Dispatchers.IO) { rows.filter { it.mediaStoreId in movedIds }.map { it.toDomain().toDeleteEntry() } }
                _state.value = MoveState.Done(s.albumName, r.moved.size, r.skipped.size, r.failed.size, r.stopped, originals)
            } catch (e: CancellationException) {
                // Pictures copied before the cancel are complete and verified: they stay, the originals are untouched.
                _state.value = MoveState.Idle
                _message.value = "Cancelado. Lo que ya se copió está en el álbum privado; los originales no se tocaron."
            }
        }
    }

    fun finish() { _state.value = MoveState.Idle; rows = emptyList() }
}
