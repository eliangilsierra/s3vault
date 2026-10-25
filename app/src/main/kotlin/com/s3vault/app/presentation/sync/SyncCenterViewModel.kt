package com.s3vault.app.presentation.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.s3vault.app.AppContainer
import com.s3vault.app.auth.AuthStatus
import com.s3vault.app.data.local.JobEntity
import com.s3vault.app.data.local.JobNames
import com.s3vault.app.data.local.MediaItemEntity
import com.s3vault.app.domain.MediaItem
import com.s3vault.app.domain.RemoteObject
import com.s3vault.app.domain.SyncState
import com.s3vault.app.domain.TransferEvent
import com.s3vault.app.domain.TransferEventType
import com.s3vault.app.domain.sync.SyncCounts
import com.s3vault.app.domain.sync.SyncFilter
import com.s3vault.app.presentation.remotePage
import com.s3vault.app.settings.BackupSettings
import com.s3vault.app.sync.Progress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** Everything above the list: counts, the state of the system, and what the last runs said. */
data class SyncCenterState(
    val counts: SyncCounts = SyncCounts(),
    val configured: Boolean = false,
    val auth: AuthStatus = AuthStatus.Missing,
    val needsCredentials: Boolean = false,
    val settings: BackupSettings = BackupSettings(),
    /** The last (or current) backup and restore runs. */
    val backupRun: JobEntity? = null,
    val restoreRun: JobEntity? = null,
    val lastSuccessMs: Long? = null,
    val lastFailure: TransferEvent? = null,
    /** Verified uploads and restores recorded today (the log keeps the latest 200 entries, so this is a lower bound). */
    val completedToday: Int = 0,
    val failedToday: Int = 0,
    val previewBacklog: Int = 0,
    val loaded: Boolean = false,
)

/** A line of the list. The two kinds are described from the side where the file really is. */
sealed interface SyncRow {
    val key: String

    data class Local(val item: MediaItem) : SyncRow {
        override val key: String get() = "l-${item.mediaStoreId}"
    }

    data class Cloud(val obj: RemoteObject) : SyncRow {
        override val key: String get() = "c-${obj.key}"
    }
}

data class SyncList(val filter: SyncFilter, val rows: List<SyncRow>, val loading: Boolean, val hasMore: Boolean)

@OptIn(ExperimentalCoroutinesApi::class)
class SyncCenterViewModel(private val c: AppContainer) : ViewModel() {
    private val filterFlow = MutableStateFlow(SyncFilter.ACTIVITY)
    private val limit = MutableStateFlow(PAGE)
    private val _message = MutableStateFlow<String?>(null)

    val filter: StateFlow<SyncFilter> = filterFlow
    val message: StateFlow<String?> = _message
    fun dismissMessage() { _message.value = null }

    private val counts: Flow<SyncCounts> = combine(c.db.media().observeLocalStateCounts(), c.db.remote().observeSyncCounts()) { local, remote ->
        SyncCounts.of(local.associate { it.syncState to it.n }, remote.cloudOnly, remote.restoring, remote.restoreFailed)
    }.distinctUntilChanged()

    private val today: Flow<Pair<Int, Int>> = flow { emit(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()) }
        .flatMapLatest { since -> c.db.events().observeCountsSince(since) }
        .map { list ->
            fun n(vararg t: TransferEventType) = list.filter { it.type in t }.sumOf { it.n }
            n(TransferEventType.UPLOADED, TransferEventType.RESTORED) to n(TransferEventType.UPLOAD_FAILED, TransferEventType.RESTORE_FAILED)
        }

    val state: StateFlow<SyncCenterState> = combine(
        listOf(
            counts,
            c.settings.backup,
            c.settings.needsCredentials,
            c.credentials.status,
            c.settings.s3Config,
            c.db.jobs().observeAll(),
            c.settings.lastSuccessfulTransferMs,
            c.db.events().observeLastFailure(),
            today,
            c.db.media().observeThumbnailBacklog(),
        ),
    ) { v ->
        @Suppress("UNCHECKED_CAST")
        val jobs = v[5] as List<JobEntity>
        val day = v[8] as Pair<Int, Int>
        SyncCenterState(
            counts = v[0] as SyncCounts,
            settings = v[1] as BackupSettings,
            needsCredentials = v[2] as Boolean,
            auth = v[3] as AuthStatus,
            configured = v[4] != null,
            backupRun = jobs.firstOrNull { it.name == JobNames.BACKUP },
            restoreRun = jobs.firstOrNull { it.name == JobNames.RESTORE },
            lastSuccessMs = v[6] as Long?,
            lastFailure = (v[7] as com.s3vault.app.data.local.TransferEventEntity?)?.toDomain(),
            completedToday = day.first,
            failedToday = day.second,
            previewBacklog = v[9] as Int,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncCenterState())

    /**
     * The list of the chosen filter, as many rows as the user scrolled to. Switching filter starts again from the first page, so a
     * long "Respaldadas" list never makes the next filter slow, and each list is one small indexed query.
     */
    val list: StateFlow<SyncList> = combine(filterFlow, limit) { f, l -> f to l }.flatMapLatest { (f, l) ->
        rowsFor(f, l).map { rows -> SyncList(f, rows, loading = false, hasMore = rows.size >= l) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncList(SyncFilter.ACTIVITY, emptyList(), loading = true, hasMore = false))

    private fun rowsFor(f: SyncFilter, l: Int): Flow<List<SyncRow>> = when (f) {
        SyncFilter.REMOTE_ONLY -> c.db.remote().observeCloudOnly(l).map { c.remotePage(it).map(SyncRow::Cloud) }
        SyncFilter.RESTORING -> c.db.remote().observeRestoring(l).map { c.remotePage(it).map(SyncRow::Cloud) }
        else -> c.db.media().observeLocalByStates(f.localStates, l).map { rows -> rows.map { SyncRow.Local(it.toDomain()) } }
    }

    fun select(f: SyncFilter) {
        if (f == filterFlow.value) return
        limit.value = PAGE
        filterFlow.value = f
    }

    fun loadMore() { limit.update { it + PAGE } }

    /** Progress of one upload, observed by its own row so a tick recomposes that row and nothing else. */
    fun uploadProgress(id: Long): Flow<Progress?> = c.progress.uploads.map { it[id] }.distinctUntilChanged()

    fun downloadProgress(key: String): Flow<Progress?> = c.progress.downloads.map { it[key] }.distinctUntilChanged()

    // ------------------------------------------------------------------ operations (all reuse the existing pipelines)

    fun retry(id: Long) = launch {
        val n = c.backup.retry(listOf(id))
        _message.value = if (n > 0) "En cola de nuevo. Se intentará según las restricciones de red y batería." else "Ya no estaba con error."
    }

    fun backUp(id: Long) = launch {
        val n = c.backup.enqueue(listOf(id))
        _message.value = if (n > 0) "En cola." else "Ya está en cola o respaldada."
    }

    fun cancel(id: Long) = launch {
        val n = c.backup.cancelQueued(listOf(id))
        _message.value = if (n > 0) "Cancelada. No se volverá a subir sola; elígela y pulsa Respaldar cuando quieras." else "Ya había empezado a subirse."
    }

    fun retryFailed() = launch {
        val n = c.backup.retryFailed()
        _message.value = if (n == 0) "No hay elementos con error." else "$n elemento(s) en cola de nuevo."
    }

    fun backupPending() = launch {
        val n = c.backup.enqueuePending(c.settings.currentBackup().includeVideos)
        _message.value = if (n == 0) "No hay nada pendiente." else "$n elemento(s) en cola."
    }

    fun cancelQueue() = launch {
        val n = c.backup.cancelQueued()
        _message.value = "Cola cancelada ($n). No se ha borrado nada."
    }

    fun pause() = launch { c.backup.pause() }
    fun resume() = launch { c.backup.resume() }

    fun restore(keys: List<String>) = launch {
        val n = c.remoteLibrary.restore(keys)
        _message.value = if (n == 0) "Ya estaba en cola de restauración." else "$n en cola de restauración."
    }

    fun cancelRestore(keys: List<String>) = launch {
        c.remoteLibrary.cancelRestore(keys)
        _message.value = "Restauración cancelada."
    }

    /** The backup worker also creates the missing previews once its queue is empty, under the same network rules. */
    fun createPreviews() = launch { c.scheduler.kickBackup(c.settings.currentBackup().wifiOnly) }

    private fun launch(block: suspend () -> Unit) { viewModelScope.launch { block() } }

    private companion object {
        const val PAGE = 60
    }
}

/** Convenience for screens that only need the domain model of a row. */
internal fun MediaItemEntity.asRow(): SyncRow = SyncRow.Local(toDomain())

internal val SyncState.isInFlight: Boolean get() = this == SyncState.UPLOADING || this == SyncState.VERIFYING
