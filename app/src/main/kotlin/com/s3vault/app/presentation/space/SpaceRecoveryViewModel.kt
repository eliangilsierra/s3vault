package com.s3vault.app.presentation.space

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.s3vault.app.AppContainer
import com.s3vault.app.data.local.FolderCount
import com.s3vault.app.data.local.MediaItemEntity
import com.s3vault.app.data.local.SpaceBucketRow
import com.s3vault.app.data.local.SpaceGroupRow
import com.s3vault.app.data.local.SpaceRecoverySql
import com.s3vault.app.domain.integrity.AuditCost
import com.s3vault.app.domain.integrity.AuditCosts
import com.s3vault.app.domain.space.SafetyRules
import com.s3vault.app.domain.space.SpaceBucket
import com.s3vault.app.domain.space.SpaceGrouping
import com.s3vault.app.domain.space.VerifiedBatch
import com.s3vault.app.presentation.delete.toDeleteEntry
import com.s3vault.app.presentation.formatBytes
import com.s3vault.app.sync.AuditOutcome
import com.s3vault.app.sync.DeleteEntry
import com.s3vault.app.sync.MediaAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the "check in S3, then delete" flow stands. Nothing is deleted before the check ends. */
sealed interface CheckState {
    data object Idle : CheckState
    data class Review(val count: Int, val bytes: Long, val cost: AuditCost) : CheckState
    data class Running(val done: Int, val total: Int) : CheckState

    /** The check ended and nothing may go to Android's dialog (or some were left out): the user must see why. */
    data class Blocked(val batch: VerifiedBatch, val requested: Int) : CheckState
}

/** The user confirmed and the files that passed are waiting for Android's own confirmation. */
data class DeviceDeletion(val entries: List<DeleteEntry>, val note: String)

data class SpaceState(
    val rules: SafetyRules = SafetyRules(),
    val buckets: Map<SpaceBucket, SpaceBucketRow> = emptyMap(),
    val bucket: SpaceBucket = SpaceBucket.VERIFIED,
    val grouping: SpaceGrouping = SpaceGrouping.MONTH,
    val access: MediaAccess = MediaAccess.FULL,
    val region: String = "",
    val configured: Boolean = false,
    val loaded: Boolean = false,
) {
    fun n(b: SpaceBucket) = buckets[b]?.n ?: 0
    fun bytes(b: SpaceBucket) = buckets[b]?.bytes ?: 0L
    val excluded: Map<SpaceBucket, SpaceBucketRow> get() = buckets.filterKeys { it.excluded }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SpaceRecoveryViewModel(private val c: AppContainer) : ViewModel() {
    private val bucketFlow = MutableStateFlow(SpaceBucket.VERIFIED)
    private val groupingFlow = MutableStateFlow(SpaceGrouping.MONTH)
    private val limit = MutableStateFlow(PAGE)
    private val selection = MutableStateFlow<Set<Long>>(emptySet())
    private val check = MutableStateFlow<CheckState>(CheckState.Idle)
    private val _message = MutableStateFlow<String?>(null)
    private val access = MutableStateFlow(c.mediaAccess())
    private val deletions = Channel<DeviceDeletion>(Channel.BUFFERED)
    private var job: Job? = null

    val selected: StateFlow<Set<Long>> = selection
    val checkState: StateFlow<CheckState> = check
    val message: StateFlow<String?> = _message
    val deviceDeletions: Flow<DeviceDeletion> = deletions.receiveAsFlow()
    fun dismissMessage() { _message.value = null }
    fun refreshAccess() { access.value = c.mediaAccess() }

    private val rules: Flow<SafetyRules> = c.settings.spaceRules

    private val buckets: Flow<Map<SpaceBucket, SpaceBucketRow>> = rules.flatMapLatest { r ->
        c.db.media().observeSpaceBuckets(SpaceRecoverySql.buckets(r, System.currentTimeMillis())).map { l -> l.associate { SpaceBucket.valueOf(it.cls) to it } }
    }

    val state: StateFlow<SpaceState> = combine(rules, buckets, bucketFlow, groupingFlow, access, c.settings.s3Config) { arr ->
        @Suppress("UNCHECKED_CAST")
        SpaceState(
            rules = arr[0] as SafetyRules, buckets = arr[1] as Map<SpaceBucket, SpaceBucketRow>, bucket = arr[2] as SpaceBucket,
            grouping = arr[3] as SpaceGrouping, access = arr[4] as MediaAccess,
            region = (arr[5] as com.s3vault.app.domain.S3Config?)?.region.orEmpty(), configured = arr[5] != null, loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpaceState())

    val groups: StateFlow<List<SpaceGroupRow>> = combine(rules, bucketFlow, groupingFlow) { r, b, g -> Triple(r, b, g) }.flatMapLatest { (r, b, g) ->
        if (!b.isCandidate) kotlinx.coroutines.flow.flowOf(emptyList())
        else c.db.media().observeSpaceGroups(SpaceRecoverySql.groups(r, System.currentTimeMillis(), b, g))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The first rows of the chosen bucket; the list grows as the user scrolls. */
    val rows: StateFlow<List<MediaItemEntity>> = combine(rules, bucketFlow, groupingFlow, limit) { r, b, g, l -> arrayOf(r, b, g, l) }.flatMapLatest { a ->
        c.db.media().observeSpaceRows(SpaceRecoverySql.rows(a[0] as SafetyRules, System.currentTimeMillis(), a[1] as SpaceBucket, a[2] as SpaceGrouping, a[3] as Int))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val folders: StateFlow<List<FolderCount>> = c.db.media().observeFolders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), emptyList())

    // ------------------------------------------------------------------ view and selection

    fun chooseBucket(b: SpaceBucket) { limit.value = PAGE; selection.value = emptySet(); bucketFlow.value = b }
    fun chooseGrouping(g: SpaceGrouping) { limit.value = PAGE; groupingFlow.value = g }
    fun loadMore() { limit.update { it + PAGE } }
    fun toggle(id: Long) = selection.update { if (id in it) it - id else it + id }
    fun clearSelection() { selection.value = emptySet() }

    /** Selects (or, if all of them already are, deselects) a whole section, however many rows are loaded. */
    fun toggleGroup(key: String) {
        viewModelScope.launch {
            val st = state.value
            val ids = withContext(Dispatchers.IO) {
                c.db.media().idList(SpaceRecoverySql.ids(st.rules, System.currentTimeMillis(), st.bucket, st.grouping, key))
            }
            selection.update { cur -> if (cur.containsAll(ids)) cur - ids.toSet() else cur + ids }
        }
    }

    fun selectAllInBucket() {
        viewModelScope.launch {
            val st = state.value
            selection.value = withContext(Dispatchers.IO) {
                c.db.media().idList(SpaceRecoverySql.ids(st.rules, System.currentTimeMillis(), st.bucket)).toSet()
            }
        }
    }

    fun saveRules(r: SafetyRules) { viewModelScope.launch { c.settings.saveSpaceRules(r); selection.value = emptySet() } }

    // ------------------------------------------------------------------ verify, review, check, delete

    /** Confirms in S3, now, the backed-up files that nothing recent vouches for. They become recoverable only if S3 says so. */
    fun verifyUnverified() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            val st = state.value
            val ids = withContext(Dispatchers.IO) { c.db.media().idList(SpaceRecoverySql.ids(st.rules, System.currentTimeMillis(), SpaceBucket.UNVERIFIED)) }
            val keys = withContext(Dispatchers.IO) { ids.chunked(400).flatMap { c.db.media().getAll(it) }.mapNotNull { it.remoteKey }.toSet() }
            if (keys.isEmpty()) return@launch
            check.value = CheckState.Running(0, keys.size)
            try {
                val outcome = c.integrityAuditor.runDeep(onlyKeys = keys, limit = keys.size) { done, total -> check.value = CheckState.Running(done, total) }
                _message.value = when (outcome) {
                    is AuditOutcome.Completed -> "Comprobadas ${outcome.audit.checked}: ${outcome.audit.verified} confirmadas en S3" +
                        (if (outcome.audit.missing + outcome.audit.changed > 0) ", ${outcome.audit.missing + outcome.audit.changed} con problemas (no se ofrecen)" else "") +
                        (if (outcome.audit.unknown > 0) ", ${outcome.audit.unknown} sin concluir" else "") + "."
                    is AuditOutcome.NeedsCredentials -> outcome.reason.userMessage
                    is AuditOutcome.RetryLater -> outcome.reason.userMessage + " No se ha confirmado nada."
                    is AuditOutcome.Blocked -> outcome.reason.userMessage
                    AuditOutcome.NotConfigured -> "Amazon S3 no está configurado."
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                check.value = CheckState.Idle
            }
        }
    }

    /** Opens the review of the selected batch: what will be checked and what it costs, before anything is asked of S3. */
    fun review() {
        val ids = selection.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { ids.chunked(400).flatMap { c.db.media().getAll(it) }.sumOf { it.sizeBytes } }
            val region = state.value.region
            check.value = CheckState.Review(ids.size, bytes, AuditCosts.deep(ids.size, region))
        }
    }

    fun dismissCheck() { if (check.value !is CheckState.Running) check.value = CheckState.Idle }

    fun cancelCheck() { job?.cancel() }

    /** The user confirmed the review: check every selected file in S3 now, then hand the ones that pass to Android's dialog. */
    fun confirmCheck() {
        if (job?.isActive == true) return
        val ids = selection.value.toList()
        if (ids.isEmpty()) return
        job = viewModelScope.launch {
            check.value = CheckState.Running(0, ids.size)
            try {
                val batch = c.spaceRecovery.verifyForDeletion(ids, state.value.rules) { done, total -> check.value = CheckState.Running(done, total) }
                if (batch.safeIds.isEmpty()) {
                    check.value = CheckState.Blocked(batch, ids.size)
                    return@launch
                }
                val entries = withContext(Dispatchers.IO) {
                    batch.safeIds.chunked(400).flatMap { c.db.media().getAll(it) }.map { it.toDomain().toDeleteEntry() }
                }
                val freed = bytesOf(batch.safeIds)
                val note = buildString {
                    append("Comprobadas ahora en S3 (SHA-256): ${entries.size}.")
                    if (batch.droppedCount > 0) {
                        append(" ${batch.droppedCount} se dejaron fuera: ")
                        append(batch.dropped.entries.joinToString("; ") { (r, n) -> "$n · ${r.label.replaceFirstChar { ch -> ch.lowercase() }}" })
                        append(".")
                    }
                    append(" Se liberan unos ${formatBytes(freed)}.")
                }
                check.value = CheckState.Idle
                clearSelection()
                deletions.send(DeviceDeletion(entries, note))
            } catch (e: CancellationException) {
                check.value = CheckState.Idle
                _message.value = "Comprobación cancelada. No se eliminó nada."
                throw e
            }
        }
    }

    private suspend fun bytesOf(ids: List<Long>): Long = withContext(Dispatchers.IO) { ids.chunked(400).flatMap { c.db.media().getAll(it) }.sumOf { it.sizeBytes } }

    private companion object { const val PAGE = 80 }
}
