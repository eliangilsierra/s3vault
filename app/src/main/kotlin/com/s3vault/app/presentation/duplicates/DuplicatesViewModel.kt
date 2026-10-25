package com.s3vault.app.presentation.duplicates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.s3vault.app.AppContainer
import com.s3vault.app.data.local.DuplicateIgnoredEntity
import com.s3vault.app.data.local.DuplicateKind
import com.s3vault.app.data.local.GroupMemberRow
import com.s3vault.app.data.local.GroupSummaryRow
import com.s3vault.app.data.local.GroupTotals
import com.s3vault.app.data.local.JobEntity
import com.s3vault.app.data.local.JobNames
import com.s3vault.app.domain.MediaItem
import com.s3vault.app.domain.duplicates.KeepCandidate
import com.s3vault.app.domain.duplicates.KeepSuggestion
import com.s3vault.app.domain.duplicates.KeeperSuggestion
import com.s3vault.app.presentation.delete.toDeleteEntry
import com.s3vault.app.presentation.formatBytes
import com.s3vault.app.sync.DeleteEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One member as the screen shows it. Local and cloud availability are separate facts. */
data class MemberView(val item: MediaItem, val distance: Int, val sharesCloudObject: Boolean)

data class GroupView(
    val summary: GroupSummaryRow,
    val members: List<MemberView>,
    val suggestion: KeepSuggestion,
    /** The copy that stays: the user's choice, or the suggestion. */
    val keepId: Long,
) {
    val others: List<MemberView> get() = members.filter { it.item.mediaStoreId != keepId }
    val recoverableBytes: Long get() = others.sumOf { it.item.sizeBytes }
}

data class DuplicatesState(
    val tab: DuplicateKind = DuplicateKind.EXACT,
    val exact: GroupTotals = GroupTotals(0, 0, 0),
    val similar: GroupTotals = GroupTotals(0, 0, 0),
    val run: JobEntity? = null,
    val needHash: Int = 0,
    val needAnalysis: Int = 0,
    val ignored: Int = 0,
    val loaded: Boolean = false,
) {
    val pending: Int get() = needHash + needAnalysis
}

/** A deletion the user asked for, ready for the confirmation flow. [cloud] means the user chose to also look at S3 for these. */
data class DeletionRequest(val entries: List<DeleteEntry>, val note: String, val cloud: Boolean)

@OptIn(ExperimentalCoroutinesApi::class)
class DuplicatesViewModel(private val c: AppContainer) : ViewModel() {
    private val tabFlow = MutableStateFlow(DuplicateKind.EXACT)
    private val limit = MutableStateFlow(PAGE)
    private val choices = MutableStateFlow<Map<Long, Long>>(emptyMap())
    private val _message = MutableStateFlow<String?>(null)
    private val requests = Channel<DeletionRequest>(Channel.BUFFERED)

    val message: StateFlow<String?> = _message
    val deletionRequests: Flow<DeletionRequest> = requests.receiveAsFlow()
    fun dismissMessage() { _message.value = null }

    val state: StateFlow<DuplicatesState> = combine(
        listOf(
            tabFlow, c.db.duplicates().observeTotals(DuplicateKind.EXACT), c.db.duplicates().observeTotals(DuplicateKind.SIMILAR),
            c.db.jobs().observe(JobNames.DUPLICATES), c.db.duplicates().observeNeedHash(), c.db.duplicates().observeNeedAnalysis(),
            c.db.duplicates().observeIgnoredCount(),
        ),
    ) { v ->
        DuplicatesState(
            tab = v[0] as DuplicateKind, exact = v[1] as GroupTotals, similar = v[2] as GroupTotals, run = v[3] as JobEntity?,
            needHash = v[4] as Int, needAnalysis = v[5] as Int, ignored = v[6] as Int, loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DuplicatesState())

    /** The groups of the open tab with their members, as many as the user scrolled to. */
    val groups: StateFlow<List<GroupView>> = combine(tabFlow, limit) { t, l -> t to l }.flatMapLatest { (tab, l) ->
        c.db.duplicates().observeGroups(tab, l).flatMapLatest { summaries ->
            if (summaries.isEmpty()) flowOf(emptyList())
            else combine(c.db.duplicates().observeMembers(summaries.map { it.id }), choices) { rows, picks -> build(tab, summaries, rows, picks) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun build(tab: DuplicateKind, summaries: List<GroupSummaryRow>, rows: List<GroupMemberRow>, picks: Map<Long, Long>): List<GroupView> {
        val byGroup = rows.groupBy { it.groupId }
        return summaries.mapNotNull { s ->
            val ms = byGroup[s.id] ?: return@mapNotNull null
            if (ms.size < 2) return@mapNotNull null
            val keys = ms.mapNotNull { it.media.remoteKey }
            val views = ms.map { r -> MemberView(r.media.toDomain(), r.distance, r.media.remoteKey != null && keys.count { it == r.media.remoteKey } > 1) }
            val candidates = ms.map { r ->
                KeepCandidate(r.media.mediaStoreId, r.media.width, r.media.height, r.media.sizeBytes, r.media.remoteKey != null, r.media.folderName, r.media.dateAddedSec, r.media.dateTakenMs)
            }
            val suggestion = if (tab == DuplicateKind.EXACT) KeeperSuggestion.exact(candidates) else KeeperSuggestion.similar(candidates)
            val keep = picks[s.id]?.takeIf { id -> views.any { it.item.mediaStoreId == id } } ?: suggestion.keepId
            GroupView(s, views, suggestion, keep)
        }
    }

    fun selectTab(t: DuplicateKind) { limit.value = PAGE; tabFlow.value = t }
    fun loadMore() { limit.update { it + PAGE } }
    fun chooseKeeper(groupId: Long, mediaId: Long) = choices.update { it + (groupId to mediaId) }

    // ------------------------------------------------------------------ scanning

    /** The card above the list shows that it is running; a snackbar on top of the first group would only get in the way. */
    fun startScan() { c.scheduler.kickDuplicateScan() }
    fun cancelScan() { c.scheduler.cancelDuplicateScan() }

    /** "Not duplicates": remembered for exactly this set of pictures. A different set (a new copy) is offered again. */
    fun ignore(g: GroupView) {
        viewModelScope.launch {
            c.db.duplicates().ignore(DuplicateIgnoredEntity(g.summary.signature, System.currentTimeMillis()))
            _message.value = "Hecho. No se volverá a proponer este grupo."
        }
    }

    fun restoreIgnored() { viewModelScope.launch { c.db.duplicates().clearIgnored(); _message.value = "Se vuelven a proponer los grupos descartados." } }

    // ------------------------------------------------------------------ deleting (always through the confirmation flow)

    /** Removes the other copies from this device. Nothing is deleted here: the request goes to the confirmation and Android's dialog. */
    fun deleteOthersFromDevice(g: GroupView) {
        val entries = g.others.map { it.item.toDeleteEntry() }
        val note = "Se conserva «${g.members.first { it.item.mediaStoreId == g.keepId }.item.displayName}». Libera unos ${formatBytes(g.recoverableBytes)}."
        viewModelScope.launch { requests.send(DeletionRequest(entries, note, cloud = false)) }
    }

    /**
     * The user also wants to look at removing S3 objects of the other copies. Objects the kept copy points to are left out: the files
     * of a group can share one S3 object, and deleting it would take the kept copy's backup with it.
     */
    fun deleteOthersFromCloud(g: GroupView) {
        val keeperKey = g.members.first { it.item.mediaStoreId == g.keepId }.item.remoteKey
        val candidates = g.others.filter { it.item.remoteKey != null }
        val usable = candidates.filter { it.item.remoteKey != keeperKey }
        if (usable.isEmpty()) {
            _message.value = if (candidates.isEmpty()) "Las otras copias no están en S3." else "Las otras copias comparten el objeto de S3 con la que conservas: no se ofrece borrarlo."
            return
        }
        val skipped = candidates.size - usable.size
        val note = if (skipped > 0) "$skipped comparten objeto de S3 con la copia que conservas y no se incluyen." else ""
        viewModelScope.launch { requests.send(DeletionRequest(usable.map { it.item.toDeleteEntry() }, note, cloud = true)) }
    }

    /** Applies the suggestion to every group of identical files: a batch the user reviews in the confirmation before anything happens. */
    fun reviewAllIdentical() {
        viewModelScope.launch {
            val (entries, bytes, groupsCount) = withContext(Dispatchers.IO) {
                val summaries = c.db.duplicates().observeGroups(DuplicateKind.EXACT, Int.MAX_VALUE).first()
                var total = 0L
                val out = ArrayList<DeleteEntry>()
                for (chunk in summaries.chunked(300)) {
                    val rows = c.db.duplicates().observeMembers(chunk.map { it.id }).first()
                    for ((_, ms) in rows.groupBy { it.groupId }) {
                        if (ms.size < 2) continue
                        val keep = KeeperSuggestion.exact(ms.map { KeepCandidate(it.media.mediaStoreId, it.media.width, it.media.height, it.media.sizeBytes, it.media.remoteKey != null, it.media.folderName, it.media.dateAddedSec, it.media.dateTakenMs) }).keepId
                        ms.filter { it.media.mediaStoreId != keep }.forEach { out += it.media.toDomain().toDeleteEntry(); total += it.media.sizeBytes }
                    }
                }
                Triple(out, total, summaries.size)
            }
            if (entries.isEmpty()) { _message.value = "No hay copias idénticas que quitar."; return@launch }
            requests.send(DeletionRequest(entries, "$groupsCount grupos: se conserva una copia de cada uno y se liberan unos ${formatBytes(bytes)}.", cloud = false))
        }
    }

    private companion object { const val PAGE = 30 }
}
