package com.s3vault.app.presentation.people

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.s3vault.app.AppContainer
import com.s3vault.app.data.faces.FaceModels
import com.s3vault.app.data.local.FaceBackupRuleEntity
import com.s3vault.app.data.local.FacePlace
import com.s3vault.app.data.local.JobEntity
import com.s3vault.app.data.local.JobNames
import com.s3vault.app.data.local.PersonRow
import com.s3vault.app.data.local.ReviewRow
import com.s3vault.app.data.local.SuggestionRow
import com.s3vault.app.domain.MediaItem
import com.s3vault.app.domain.faces.FaceModel
import com.s3vault.app.domain.faces.PeopleFacts
import com.s3vault.app.domain.faces.PeoplePhase
import com.s3vault.app.domain.faces.PeoplePhases
import com.s3vault.app.domain.faces.PersonState
import com.s3vault.app.domain.faces.isUsable
import com.s3vault.app.domain.faces.RulePolicy
import com.s3vault.app.sync.FaceRunOutcome
import com.s3vault.app.sync.RulePlan
import com.s3vault.app.sync.RuleResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the screens tell the user after something happened. Plain data; the screen turns it into words (string resources). */
class PeopleMessage(val kind: Kind, val n: Int = 0, val m: Int = 0, val undoPersonId: Long? = null) {
    enum class Kind {
        ANALYSIS_STARTED, ANALYSIS_STOPPED, INDEX_DELETED, TURNED_OFF,
        CONFIRMED, HIDDEN, UNHIDDEN, GROUP_DELETED, MERGED, PHOTOS_REMOVED, MOVED, SPLIT,
        RULE_ON, RULE_NEEDS_S3, RULE_PAUSED, RULE_RESUMED, RULE_OFF, RULE_UNDONE, EXCLUDED, REVIEW_APPROVED, REVIEW_DISMISSED,
        BACKUP_QUEUED, BACKUP_NOTHING_NEW, SUGGESTIONS_ACCEPTED, SUGGESTIONS_REJECTED, KEPT_APART, ASSIGNED,
    }
}

/** Everything the People screen shows, in one value so a frame never mixes two moments. */
data class PeopleState(
    val loaded: Boolean = false,
    val phase: PeoplePhase = PeoplePhase.NOT_STARTED,
    val enabled: Boolean = false,
    val introSeen: Boolean = false,
    val images: Int = 0,
    val analyzed: Int = 0,
    val pending: Int = 0,
    val unreadable: Int = 0,
    val stale: Int = 0,
    val faces: Int = 0,
    val people: List<PersonRow> = emptyList(),
    val hidden: List<PersonRow> = emptyList(),
    val toReview: Int = 0,
    val job: JobEntity? = null,
    val activeRules: Int = 0,
    val onlyCharging: Boolean = true,
    val gentle: Boolean = true,
    val indexBytes: Long = 0,
    val globalAutoBackup: Boolean = false,
    val s3Configured: Boolean = false,
) {
    /** Why the analysis is not going on, when it is not the user's doing. */
    val attention: Attention get() = when (job?.takeIf { it.state == com.s3vault.app.data.local.JobState.NEEDS_ATTENTION }?.lastError) {
        com.s3vault.app.sync.FaceIndexer.ATTENTION_QUOTA -> Attention.QUOTA
        com.s3vault.app.sync.FaceIndexer.ATTENTION_STORAGE -> Attention.LOW_STORAGE
        else -> Attention.NONE
    }

    enum class Attention { NONE, QUOTA, LOW_STORAGE }

    val running: Boolean get() = job?.state == com.s3vault.app.data.local.JobState.RUNNING
}

@OptIn(ExperimentalCoroutinesApi::class)
class PeopleViewModel(private val c: AppContainer) : ViewModel() {
    private val dao get() = c.db.faces()
    private val _message = MutableStateFlow<PeopleMessage?>(null)
    val message: StateFlow<PeopleMessage?> = _message
    fun dismissMessage() { _message.value = null }

    /** Whether the bundled models are intact; checked once, off the main thread (it reads the two files). */
    private val modelOk = MutableStateFlow<Boolean?>(null)
    private val indexReadable = MutableStateFlow(true)

    init {
        viewModelScope.launch { modelOk.value = withContext(Dispatchers.IO) { FaceModels.available(c.appContext) } }
        viewModelScope.launch {
            // A cheap check that the key still opens what is stored: if not, the screen says the index must be rebuilt.
            indexReadable.value = withContext(Dispatchers.IO) {
                val sample = dao.sampleSealed()
                (if (sample.isEmpty()) c.faceVault.isUsable() else sample.any { com.s3vault.app.domain.faces.FaceVectorCodec.openVector(c.faceVault, it) != null })
            }
        }
    }

    private val counts = combine(
        dao.observeImageCount(), dao.observeAnalyzed(FaceModel.MODEL_ID, FaceModel.PIPELINE_VERSION), dao.observeNeedAnalysis(FaceModel.MODEL_ID, FaceModel.PIPELINE_VERSION),
        dao.observeUnreadable(), dao.observeStale(FaceModel.MODEL_ID, FaceModel.PIPELINE_VERSION), dao.observeFaceCount(),
    ) { v -> Counts(v[0], v[1], v[2], v[3], v[4], v[5]) }

    private class Counts(val images: Int, val analyzed: Int, val pending: Int, val unreadable: Int, val stale: Int, val faces: Int)

    val state: StateFlow<PeopleState> = combine(
        listOf(
            counts, dao.observePeople(), dao.observeHiddenPeople(), dao.observeSuggestionCount(), dao.observeReviewCount(), c.db.jobs().observe(JobNames.FACES),
            dao.observeActiveRules(), c.settings.faceEnabled, c.settings.faceIntroSeen, c.settings.faceOnlyCharging, c.settings.faceGentle,
            dao.observeIndexBytes(), modelOk, indexReadable, c.settings.backup,
        ),
    ) { v ->
        val n = v[0] as Counts
        @Suppress("UNCHECKED_CAST") val people = v[1] as List<PersonRow>
        @Suppress("UNCHECKED_CAST") val hidden = v[2] as List<PersonRow>
        val job = v[5] as JobEntity?
        val enabled = v[7] as Boolean
        val backup = v[14] as com.s3vault.app.settings.BackupSettings
        val facts = PeopleFacts(
            enabled, n.images, n.analyzed, n.pending, n.unreadable, n.stale, n.faces, people.size,
            running = job?.state == com.s3vault.app.data.local.JobState.RUNNING, modelAvailable = (v[12] as Boolean?) ?: true, indexReadable = v[13] as Boolean,
        )
        PeopleState(
            loaded = true, phase = PeoplePhases.resolve(facts), enabled = enabled, introSeen = v[8] as Boolean, images = n.images, analyzed = n.analyzed,
            pending = n.pending, unreadable = n.unreadable, stale = n.stale, faces = n.faces, people = people, hidden = hidden,
            toReview = (v[3] as Int) + (v[4] as Int), job = job, activeRules = v[6] as Int, onlyCharging = v[9] as Boolean, gentle = v[10] as Boolean,
            indexBytes = v[11] as Long, globalAutoBackup = backup.autoBackup, s3Configured = c.settings.currentS3Config() != null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PeopleState())

    /** The user read what the analysis does and chose to turn it on. Starts the first analysis at once (still waiting for the battery to be OK). */
    fun enable() {
        viewModelScope.launch {
            c.settings.setFaceIntroSeen(true)
            c.settings.setFaceEnabled(true)
            val charging = c.settings.faceOnlyCharging.first()
            c.scheduler.configureFaceSchedule(true, charging)
            c.scheduler.kickFaceIndex(onlyCharging = false)
            _message.value = PeopleMessage(PeopleMessage.Kind.ANALYSIS_STARTED)
        }
    }

    /** Stops the work and the schedule. The index stays until the user deletes it; nothing is analysed meanwhile. */
    fun disable() {
        viewModelScope.launch {
            c.settings.setFaceEnabled(false)
            c.scheduler.cancelFaceIndex(); c.scheduler.configureFaceSchedule(false, true)
            _message.value = PeopleMessage(PeopleMessage.Kind.TURNED_OFF)
        }
    }

    /** "Analizar ahora": does not wait for the charger (it still waits for the battery not to be low). */
    fun analyzeNow() {
        c.scheduler.kickFaceIndex(onlyCharging = false)
        _message.value = PeopleMessage(PeopleMessage.Kind.ANALYSIS_STARTED)
    }

    fun pause() {
        c.scheduler.cancelFaceIndex()
        _message.value = PeopleMessage(PeopleMessage.Kind.ANALYSIS_STOPPED)
    }

    fun setOnlyCharging(v: Boolean) {
        viewModelScope.launch {
            c.settings.setFaceOnlyCharging(v)
            if (c.settings.faceEnabled.first()) c.scheduler.configureFaceSchedule(true, v)
        }
    }

    fun setGentle(v: Boolean) { viewModelScope.launch { c.settings.setFaceGentle(v) } }

    /**
     * Erases the face index: vectors, boxes, groups, names, corrections and rules, and the key that sealed them; turns the analysis
     * off. Photos, backups and S3 are not touched. [reanalyze] starts again from nothing instead of leaving the feature off.
     */
    fun deleteIndex(reanalyze: Boolean = false) {
        viewModelScope.launch {
            c.scheduler.cancelFaceIndex(); c.scheduler.configureFaceSchedule(false, true)
            c.facePeople.deleteIndex()
            c.faceCrops.clear()
            indexReadable.value = true
            if (reanalyze) {
                c.settings.setFaceEnabled(true)
                c.scheduler.configureFaceSchedule(true, c.settings.faceOnlyCharging.first())
                c.scheduler.kickFaceIndex(onlyCharging = false)
                _message.value = PeopleMessage(PeopleMessage.Kind.ANALYSIS_STARTED)
            } else {
                c.settings.setFaceEnabled(false)
                _message.value = PeopleMessage(PeopleMessage.Kind.INDEX_DELETED)
            }
        }
    }

    fun unhide(personId: Long) {
        viewModelScope.launch { c.facePeople.hide(personId, false); _message.value = PeopleMessage(PeopleMessage.Kind.UNHIDDEN) }
    }
}

/** One group, as the detail screen needs it. */
data class PersonDetailState(
    val person: PersonRow? = null,
    val faces: List<FacePlace> = emptyList(),
    val photos: List<MediaItem>? = null,
    val rule: FaceBackupRuleEntity? = null,
    val reviewPhotos: List<MediaItem> = emptyList(),
    val waitingByRule: Int = 0,
    val backedUpByRule: Int = 0,
    val others: List<PersonRow> = emptyList(),
    val globalAutoBackup: Boolean = false,
    val s3Configured: Boolean = false,
    val needsCredentials: Boolean = false,
    val missing: Boolean = false,
)

/** The draft of a rule while the user decides: nothing is saved until they confirm. */
data class RuleDraft(
    val includeExisting: Boolean = false,
    val policy: RulePolicy = RulePolicy.STRICT,
    val excluded: Set<Long> = emptySet(),
    val plan: RulePlan? = null,
    val loading: Boolean = true,
    val step: Step = Step.OPTIONS,
) {
    enum class Step { OPTIONS, CONFIRM }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PersonDetailViewModel(private val c: AppContainer) : ViewModel() {
    private val dao get() = c.db.faces()
    private val id = MutableStateFlow<Long?>(null)
    fun open(personId: Long) { if (id.value != personId) { id.value = personId; clearSelection() } }
    val personId: Long? get() = id.value

    private val _message = MutableStateFlow<PeopleMessage?>(null)
    val message: StateFlow<PeopleMessage?> = _message
    fun dismissMessage() { _message.value = null }

    private val selected = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = selected
    fun toggle(mediaId: Long) = selected.update { if (mediaId in it) it - mediaId else it + mediaId }
    fun clearSelection() { selected.value = emptySet() }
    fun selectAll() { selected.value = state.value.photos.orEmpty().map { it.mediaStoreId }.toSet() }
    val uploads = c.progress.uploads

    val state: StateFlow<PersonDetailState> = id.flatMapLatest { pid ->
        if (pid == null) flowOf(PersonDetailState()) else combine(
            listOf(
                dao.observePersonRow(pid), dao.observeFacesOfPerson(pid), dao.observePhotosOfPerson(pid), dao.observeRule(pid), dao.observeReview(pid),
                dao.observeWaitingByRule(pid), dao.observeBackedUpByRule(pid), dao.observePeople(), c.settings.backup, c.settings.needsCredentials,
            ),
        ) { v ->
            @Suppress("UNCHECKED_CAST") val faces = v[1] as List<FacePlace>
            @Suppress("UNCHECKED_CAST") val photos = (v[2] as List<com.s3vault.app.data.local.MediaItemEntity>).map { it.toDomain() }
            @Suppress("UNCHECKED_CAST") val review = (v[4] as List<com.s3vault.app.data.local.MediaItemEntity>).map { it.toDomain() }
            @Suppress("UNCHECKED_CAST") val all = v[7] as List<PersonRow>
            PersonDetailState(
                person = v[0] as PersonRow?, faces = faces, photos = photos, rule = v[3] as FaceBackupRuleEntity?, reviewPhotos = review,
                waitingByRule = v[5] as Int, backedUpByRule = v[6] as Int, others = all.filter { it.id != pid },
                globalAutoBackup = (v[8] as com.s3vault.app.settings.BackupSettings).autoBackup, s3Configured = c.settings.currentS3Config() != null,
                needsCredentials = v[9] as Boolean, missing = (v[0] as PersonRow?) == null,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PersonDetailState())

    // ------------------------------------------------------------------ corrections

    fun rename(name: String) { launchOn { c.facePeople.rename(it, name) } }
    fun confirm() { launchOn { c.facePeople.confirm(it); say(PeopleMessage.Kind.CONFIRMED) } }

    /** From the rule sheet: the group is confirmed first, then the preview is made again for what a confirmed group would queue. */
    fun confirmAndPreview() { launchOn { c.facePeople.confirm(it); say(PeopleMessage.Kind.CONFIRMED); _draft.update { d -> d.copy(loading = true) }; refreshPreview() } }
    fun hide() { launchOn { c.facePeople.hide(it, true); say(PeopleMessage.Kind.HIDDEN) } }
    fun deleteGroup(done: () -> Unit) { launchOn { c.facePeople.deletePerson(it); c.faceCrops.clear(); say(PeopleMessage.Kind.GROUP_DELETED); done() } }

    fun removeSelected() = withSelection { pid, ids ->
        val n = c.facePeople.removePhotos(pid, ids); say(PeopleMessage.Kind.PHOTOS_REMOVED, n)
    }

    /** The selected photos' faces of this group move to [targetId], or to a new group when null ("separar"). */
    fun moveSelected(targetId: Long?) = withSelection { pid, ids ->
        val faceIds = ids.chunked(400).flatMap { dao.faceIdsInPhotos(pid, it) }
        val r = c.facePeople.moveFaces(faceIds, targetId)
        say(if (targetId == null) PeopleMessage.Kind.SPLIT else PeopleMessage.Kind.MOVED, r.moved, r.skippedSamePhoto)
    }

    fun mergeInto(otherId: Long) { launchOn { pid -> val n = c.facePeople.merge(otherId, pid); say(PeopleMessage.Kind.MERGED, n) } }

    /** Merges [otherId] into this group (this one stays). */
    fun absorb(otherId: Long) { launchOn { pid -> val n = c.facePeople.merge(pid, otherId); say(PeopleMessage.Kind.MERGED, n) } }

    fun backupSelected() = withSelection { _, ids ->
        val n = c.backup.enqueue(ids)
        say(if (n > 0) PeopleMessage.Kind.BACKUP_QUEUED else PeopleMessage.Kind.BACKUP_NOTHING_NEW, n)
    }

    fun excludeSelectedFromRule() = withSelection { pid, ids ->
        c.faceBackup.exclude(pid, ids); say(PeopleMessage.Kind.EXCLUDED, ids.size)
    }

    // ------------------------------------------------------------------ the rule

    private val _draft = MutableStateFlow(RuleDraft())
    val draft: StateFlow<RuleDraft> = _draft
    private var previewJob: kotlinx.coroutines.Job? = null

    fun beginRule() { _draft.value = RuleDraft(); refreshPreview() }
    fun setIncludeExisting(v: Boolean) { _draft.update { it.copy(includeExisting = v, loading = true) }; refreshPreview() }
    fun setPolicy(p: RulePolicy) { _draft.update { it.copy(policy = p, loading = true) }; refreshPreview() }
    fun toggleDraftExclusion(mediaId: Long) { _draft.update { it.copy(excluded = if (mediaId in it.excluded) it.excluded - mediaId else it.excluded + mediaId, loading = true) }; refreshPreview() }
    fun goToConfirm() { _draft.update { it.copy(step = RuleDraft.Step.CONFIRM) } }
    fun backToOptions() { _draft.update { it.copy(step = RuleDraft.Step.OPTIONS) } }

    private fun refreshPreview() {
        val pid = id.value ?: return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            val d = _draft.value
            val plan = c.faceBackup.preview(pid, d.includeExisting, d.policy, d.excluded)
            _draft.update { it.copy(plan = plan, loading = false) }
        }
    }

    /** The user confirmed: saves the rule and, if asked, starts the one-time pass. Tells how many photos were queued, with "deshacer". */
    fun confirmRule() {
        launchOn { pid ->
            val d = _draft.value
            when (val r = c.faceBackup.activate(pid, d.includeExisting, d.policy, d.excluded)) {
                is RuleResult.Applied -> say(PeopleMessage.Kind.RULE_ON, r.queued, r.review, undo = if (r.queued > 0) pid else null)
                RuleResult.NeedsS3 -> say(PeopleMessage.Kind.RULE_NEEDS_S3)
                RuleResult.Inactive -> Unit
            }
        }
    }

    fun pauseRule() { launchOn { c.faceBackup.pause(it); say(PeopleMessage.Kind.RULE_PAUSED) } }
    fun resumeRule() { launchOn { c.faceBackup.resume(it); say(PeopleMessage.Kind.RULE_RESUMED) } }
    fun removeRule() { launchOn { c.faceBackup.deactivate(it); say(PeopleMessage.Kind.RULE_OFF) } }
    fun undoRule(personId: Long) { viewModelScope.launch { val n = c.faceBackup.undo(personId); say(PeopleMessage.Kind.RULE_UNDONE, n) } }
    fun changePolicy(p: RulePolicy) { launchOn { c.faceBackup.setPolicy(it, p) } }

    fun approveReview(mediaIds: List<Long>) { launchOn { val n = c.faceBackup.approve(it, mediaIds); say(PeopleMessage.Kind.REVIEW_APPROVED, n) } }
    fun dismissReview(mediaIds: List<Long>) { launchOn { c.faceBackup.dismiss(it, mediaIds); say(PeopleMessage.Kind.REVIEW_DISMISSED, mediaIds.size) } }

    // ------------------------------------------------------------------ plumbing

    private fun say(kind: PeopleMessage.Kind, n: Int = 0, m: Int = 0, undo: Long? = null) { _message.value = PeopleMessage(kind, n, m, undo) }

    private fun launchOn(block: suspend (Long) -> Unit) {
        val pid = id.value ?: return
        viewModelScope.launch { block(pid) }
    }

    private fun withSelection(block: suspend (Long, List<Long>) -> Unit) {
        val pid = id.value ?: return
        val ids = selected.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch { block(pid, ids); clearSelection() }
    }
}

/** The review screen: suggestions, groups that may be one person, photos waiting for a decision, and faces with no group. */
data class ReviewState(
    val loaded: Boolean = false,
    val suggestions: List<SuggestionGroup> = emptyList(),
    val merges: List<MergeOffer> = emptyList(),
    val photos: List<ReviewGroup> = emptyList(),
    val unassigned: List<FacePlace> = emptyList(),
    val people: List<PersonRow> = emptyList(),
) {
    val isEmpty: Boolean get() = suggestions.isEmpty() && merges.isEmpty() && photos.isEmpty() && unassigned.isEmpty()
}

class SuggestionGroup(val person: PersonRow?, val personId: Long, val faces: List<SuggestionRow>)
class MergeOffer(val a: PersonRow, val b: PersonRow, val coverA: FacePlace?, val coverB: FacePlace?)
class ReviewGroup(val person: PersonRow?, val personId: Long, val photos: List<ReviewRow>)

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModel(private val c: AppContainer) : ViewModel() {
    private val dao get() = c.db.faces()
    private val _message = MutableStateFlow<PeopleMessage?>(null)
    val message: StateFlow<PeopleMessage?> = _message
    fun dismissMessage() { _message.value = null }

    private val merges: Flow<List<MergeOffer>> = c.db.invalidationTracker.createFlow("faces", "people", "person_merge_rejections").flatMapLatest {
        kotlinx.coroutines.flow.flow {
            val offers = withContext(Dispatchers.IO) {
                val byId = dao.people().associateBy { it.id }
                val rows = dao.people().associate { it.id to it }
                c.faceGrouping.mergeSuggestions(20).mapNotNull { m ->
                    val pa = rows[m.a] ?: return@mapNotNull null; val pb = rows[m.b] ?: return@mapNotNull null
                    val covers = dao.places(listOfNotNull(pa.coverFaceId, pb.coverFaceId)).associateBy { it.faceId }
                    MergeOffer(personRow(pa), personRow(pb), pa.coverFaceId?.let(covers::get), pb.coverFaceId?.let(covers::get)).also { byId.size }
                }
            }
            emit(offers)
        }
    }

    private fun personRow(p: com.s3vault.app.data.local.PersonEntity) = PersonRow(p.id, p.name, p.ordinal, p.state, p.coverFaceId, 0, 0, 0, null)

    val state: StateFlow<ReviewState> = combine(
        dao.observeSuggestions(), merges, dao.observeReviewItems(), dao.observeUnassigned(60), dao.observePeople(),
    ) { sug, mg, rev, un, people ->
        val byId = people.associateBy { it.id }
        ReviewState(
            loaded = true,
            suggestions = sug.groupBy { it.suggestedPersonId }.map { (pid, rows) -> SuggestionGroup(byId[pid], pid, rows) },
            merges = mg, photos = rev.groupBy { it.personId }.map { (pid, rows) -> ReviewGroup(byId[pid], pid, rows) },
            unassigned = un, people = people,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewState())

    fun acceptSuggestions(faceIds: List<Long>) { viewModelScope.launch { val n = c.facePeople.acceptSuggestions(faceIds); say(PeopleMessage.Kind.SUGGESTIONS_ACCEPTED, n) } }
    fun rejectSuggestions(faceIds: List<Long>) { viewModelScope.launch { val n = c.facePeople.rejectSuggestions(faceIds); say(PeopleMessage.Kind.SUGGESTIONS_REJECTED, n) } }
    fun merge(intoId: Long, fromId: Long) { viewModelScope.launch { val n = c.facePeople.merge(intoId, fromId); say(PeopleMessage.Kind.MERGED, n) } }
    fun keepApart(a: Long, b: Long) { viewModelScope.launch { c.facePeople.keepApart(a, b); say(PeopleMessage.Kind.KEPT_APART) } }
    fun approvePhotos(personId: Long, ids: List<Long>) { viewModelScope.launch { val n = c.faceBackup.approve(personId, ids); say(PeopleMessage.Kind.REVIEW_APPROVED, n) } }
    fun dismissPhotos(personId: Long, ids: List<Long>) { viewModelScope.launch { c.faceBackup.dismiss(personId, ids); say(PeopleMessage.Kind.REVIEW_DISMISSED, ids.size) } }
    fun assign(faceId: Long, personId: Long?) { viewModelScope.launch { val r = c.facePeople.moveFaces(listOf(faceId), personId); say(PeopleMessage.Kind.ASSIGNED, r.moved) } }

    private fun say(kind: PeopleMessage.Kind, n: Int = 0) { _message.value = PeopleMessage(kind, n) }
}
