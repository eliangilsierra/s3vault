package com.s3vault.app.sync

import androidx.room.withTransaction
import com.s3vault.app.data.local.AppDatabase
import com.s3vault.app.data.local.FaceBackupDecisionEntity
import com.s3vault.app.data.local.FaceBackupRuleEntity
import com.s3vault.app.data.local.FaceRuleExclusionEntity
import com.s3vault.app.data.local.JobNames
import com.s3vault.app.data.local.JobState
import com.s3vault.app.data.local.RuleCandidateRow
import com.s3vault.app.domain.faces.DecisionOutcome
import com.s3vault.app.domain.faces.DecisionReason
import com.s3vault.app.domain.faces.FaceAssignment
import com.s3vault.app.domain.faces.FaceBackupPolicy
import com.s3vault.app.domain.faces.FaceMatch
import com.s3vault.app.domain.faces.PersonState
import com.s3vault.app.domain.faces.PhotoFacts
import com.s3vault.app.domain.faces.PolicyThresholds
import com.s3vault.app.domain.faces.RulePolicy
import com.s3vault.app.domain.faces.RuleScope
import com.s3vault.app.domain.faces.RuleState
import com.s3vault.app.domain.faces.RuleVerdict
import com.s3vault.app.domain.faces.SkipReason
import com.s3vault.app.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PlannedPhoto(val mediaStoreId: Long, val sizeBytes: Long)

/** What a rule would do right now, without doing it. The same numbers drive the preview the user confirms and the real run. */
class RulePlan(
    val willQueue: List<PlannedPhoto>,
    /** Photos the rule would not decide on: they wait for the user, with the reason. */
    val review: Map<Long, DecisionReason>,
    val alreadyHandled: Int,
    val excluded: Int,
    val deletedFromS3OnPurpose: Int,
    val outsideScope: Int,
) {
    val bytes: Long get() = willQueue.sumOf { it.sizeBytes }
}

sealed interface RuleResult {
    data class Applied(val queued: Int, val review: Int) : RuleResult

    /** The rule is saved, but nothing is queued until Amazon S3 is configured. */
    data object NeedsS3 : RuleResult
    data object Inactive : RuleResult
}

/**
 * The automatic-backup rules of the people, and the only place that turns "this photo shows that person" into "queue this photo".
 * It does not upload anything: it asks the existing [BackupController] queue to take the photos (the same table, the same states,
 * the same worker), so idempotence, the S3 prefix, retries and the network/battery settings are the ones the app already has.
 *
 * Guarantees, each one covered by a test:
 *  - a rule only ever moves LOCAL_ONLY photos to QUEUED; a backed-up, queued, uploading or failed photo is left as it is;
 *  - a photo whose S3 copy the user deleted on purpose is never queued again;
 *  - turning a rule off or deleting a person never deletes a file or an S3 object, and leaves queued uploads alone unless the user
 *    chooses "deshacer";
 *  - existing photos are only ever included by the one-time pass the user confirmed with its count in front of them.
 */
class FaceBackupService(
    private val db: AppDatabase,
    private val backup: BackupController,
    private val settings: AppSettings,
    private val grouping: FaceGroupingStore,
    private val lock: FaceIndexLock,
    private val jobs: JobRecorder,
    private val thresholds: PolicyThresholds = PolicyThresholds(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Called after each batch of the one-time pass with how many photos were queued so far (tests use it to stop in the middle). */
    private val afterBatch: suspend (Int) -> Unit = {},
    /** False while Amazon S3 is not set up: rules are saved but queue nothing. */
    private val s3Configured: suspend () -> Boolean = { settings.currentS3Config() != null },
) {
    private val dao get() = db.faces()
    private val media get() = db.media()

    // ------------------------------------------------------------------ planning (reads only)

    suspend fun plan(personId: Long, includeOlder: Boolean, policy: RulePolicy, newSinceSec: Long, alsoExcluded: Set<Long> = emptySet()): RulePlan {
        val person = dao.person(personId) ?: return RulePlan(emptyList(), emptyMap(), 0, 0, 0, 0)
        val own = dao.ruleCandidates(personId)
        // Automatic matches are judged on how sure the grouping is of them now, not on what it knew when it placed them.
        val fresh = grouping.rescore(personId, dao.faces(own.filter { it.assignment == FaceAssignment.AUTO }.map { it.faceId }))
        val rows = own.map { r -> fresh[r.faceId]?.let { (s, m) -> r.copy(matchScore = s, margin = m) } ?: r } + dao.suggestedCandidates(personId)
        val userExcluded = dao.exclusions(personId).toHashSet() + alsoExcluded
        val dismissed = dao.decisions(personId).filter { it.outcome == DecisionOutcome.DISMISSED }.map { it.mediaStoreId }.toHashSet()
        val queue = ArrayList<PlannedPhoto>()
        val review = LinkedHashMap<Long, DecisionReason>()
        var handled = 0; var excluded = 0; var deleted = 0; var outside = 0
        for ((photoId, faces) in rows.groupBy { it.mediaStoreId }) {
            val first = faces.first()
            val verdict = FaceBackupPolicy.decide(
                includeOlder, policy, newSinceSec, person.state, PhotoFacts(first.syncState, first.backupExcluded, first.dateAddedSec),
                faces.map { FaceMatch(it.assignment, it.matchScore, it.margin, it.quality) }, photoId in userExcluded, photoId in dismissed, thresholds,
            )
            when (verdict) {
                RuleVerdict.Queue -> queue += PlannedPhoto(photoId, first.sizeBytes)
                is RuleVerdict.Review -> review[photoId] = verdict.reason
                is RuleVerdict.Skip -> when (verdict.why) {
                    SkipReason.EXCLUDED_BY_USER, SkipReason.DISMISSED, SkipReason.HIDDEN_PERSON -> excluded++
                    SkipReason.ALREADY_HANDLED -> handled++
                    SkipReason.DELETED_FROM_S3_ON_PURPOSE -> deleted++
                    SkipReason.OUTSIDE_SCOPE -> outside++
                }
            }
        }
        return RulePlan(queue, review, handled, excluded, deleted, outside)
    }

    /** The preview shown before a rule is turned on: nothing is saved or queued. */
    suspend fun preview(personId: Long, includeExisting: Boolean, policy: RulePolicy, excluded: Set<Long> = emptySet()): RulePlan =
        plan(personId, includeOlder = includeExisting, policy = policy, newSinceSec = nowMs() / 1000, alsoExcluded = excluded)

    // ------------------------------------------------------------------ turning rules on and off

    /**
     * Saves the rule and, if the user chose to include existing photos, starts the one-time pass. The group counts as reviewed:
     * the screen that calls this showed its photos and the user confirmed. Nothing is uploaded here: photos are queued, and the
     * existing backup worker decides when to send them, under the user's network and battery settings.
     */
    suspend fun activate(personId: Long, includeExisting: Boolean, policy: RulePolicy, excluded: Set<Long>): RuleResult {
        lock.mutex.withLock {
            val person = dao.person(personId) ?: return RuleResult.Inactive
            if (person.state == PersonState.HIDDEN) return RuleResult.Inactive
            val now = nowMs()
            db.withTransaction {
                if (person.state == PersonState.PROVISIONAL) dao.setState(personId, PersonState.CONFIRMED, now)
                dao.insertExclusions(excluded.map { FaceRuleExclusionEntity(personId, it, now) })
                dao.upsertRule(
                    FaceBackupRuleEntity(
                        personId, RuleState.ACTIVE, if (includeExisting) RuleScope.INCLUDE_EXISTING else RuleScope.NEW_ONLY, policy,
                        enabledAtMs = now, newSinceSec = now / 1000, existingPending = includeExisting, updatedAtMs = now,
                    ),
                )
            }
        }
        return evaluate(personId)
    }

    /** Stops the rule from adding anything. Photos already queued or backed up stay exactly as they are. */
    suspend fun deactivate(personId: Long) = lock.mutex.withLock { dao.deleteRule(personId) }

    suspend fun pause(personId: Long) = lock.mutex.withLock { dao.setRuleState(personId, RuleState.PAUSED, nowMs()) }

    /** Resuming does not reach back: photos that arrived while it was paused are left for the user to back up by hand. */
    suspend fun resume(personId: Long): RuleResult {
        lock.mutex.withLock {
            val r = dao.rule(personId) ?: return RuleResult.Inactive
            val now = nowMs()
            dao.upsertRule(r.copy(state = RuleState.ACTIVE, newSinceSec = now / 1000, updatedAtMs = now))
        }
        return evaluate(personId)
    }

    suspend fun setPolicy(personId: Long, policy: RulePolicy) {
        lock.mutex.withLock { dao.setRulePolicy(personId, policy, nowMs()) }
        evaluate(personId)
    }

    /**
     * "Deshacer": takes back the photos this rule queued that have not started uploading, returning them to "only on this device"
     * as they were, and removes the rule. Photos already uploaded or uploading are not touched, and nothing in S3 is.
     */
    suspend fun undo(personId: Long): Int = lock.mutex.withLock {
        val ids = dao.queuedByRule(personId)
        val back = db.withTransaction {
            val n = ids.chunked(400).sumOf { media.dequeueForRule(it) }
            dao.deleteDecisions(personId, DecisionOutcome.QUEUED)
            dao.deleteRule(personId)
            n
        }
        back
    }

    // ------------------------------------------------------------------ evaluation

    suspend fun evaluateAll() {
        for (r in dao.rules()) if (r.state == RuleState.ACTIVE) evaluate(r.personId)
    }

    /** Looks at one person's faces and queues what the rule allows. Idempotent: running it twice queues nothing the second time. */
    suspend fun evaluate(personId: Long): RuleResult = lock.mutex.withLock {
        val rule = dao.rule(personId) ?: return RuleResult.Inactive
        if (rule.state != RuleState.ACTIVE) return RuleResult.Inactive
        if (!s3Configured()) return RuleResult.NeedsS3
        var queued = 0
        var reviewCount = 0
        if (rule.existingPending) queued += applyOlder(rule)
        val plan = plan(personId, includeOlder = false, policy = rule.policy, newSinceSec = rule.newSinceSec)
        queued += queue(personId, plan.willQueue.map { it.mediaStoreId })
        reviewCount = recordReview(personId, plan.review)
        if (queued > 0) backup.afterEnqueue(queued)
        RuleResult.Applied(queued, reviewCount)
    }

    /** The one-time pass over photos that were already on the device, in batches the user can stop (cancel, or the rule being removed). */
    private suspend fun applyOlder(rule: FaceBackupRuleEntity): Int {
        val plan = plan(rule.personId, includeOlder = true, policy = rule.policy, newSinceSec = rule.newSinceSec)
        val ids = plan.willQueue.map { it.mediaStoreId }
        jobs.start(JobNames.FACE_RULES, ids.size)
        var done = 0
        try {
            for (chunk in ids.chunked(APPLY_BATCH)) {
                currentCoroutineContext().ensureActive()
                if (dao.rule(rule.personId)?.existingPending != true) break // cancelled from the screen between batches
                done += queue(rule.personId, chunk)
                jobs.progress(JobNames.FACE_RULES, ids.size, done, 0, done)
                afterBatch(done)
            }
            if (dao.rule(rule.personId) != null) dao.setExistingPending(rule.personId, false, nowMs())
            jobs.finish(JobNames.FACE_RULES, JobState.COMPLETED)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { jobs.finish(JobNames.FACE_RULES, JobState.CANCELLED) }
            throw e
        }
        return done
    }

    /** Stops the one-time pass of this person's rule (what was queued stays queued; "deshacer" takes it back). */
    suspend fun cancelExistingPass(personId: Long) = lock.mutex.withLock { dao.setExistingPending(personId, false, nowMs()) }

    private suspend fun queue(personId: Long, ids: List<Long>): Int {
        if (ids.isEmpty()) return 0
        val now = nowMs()
        return db.withTransaction {
            var n = 0
            for (chunk in ids.chunked(APPLY_BATCH)) {
                n += media.enqueueForRule(chunk, now)
                dao.upsertDecisions(chunk.map { FaceBackupDecisionEntity(it, personId, DecisionOutcome.QUEUED, DecisionReason.NONE, now) })
                dao.clearReviewFor(personId, chunk)
            }
            n
        }
    }

    private suspend fun recordReview(personId: Long, review: Map<Long, DecisionReason>): Int {
        val now = nowMs()
        db.withTransaction {
            dao.clearReview(personId)
            if (review.isNotEmpty()) {
                val queuedBefore = dao.decisions(personId).filter { it.outcome != DecisionOutcome.REVIEW }.map { it.mediaStoreId }.toHashSet()
                val rows = review.filterKeys { it !in queuedBefore }.map { (id, reason) -> FaceBackupDecisionEntity(id, personId, DecisionOutcome.REVIEW, reason, now) }
                dao.upsertDecisions(rows)
            }
        }
        return review.size
    }

    // ------------------------------------------------------------------ the review list

    /** The user says yes to photos in the review list: they are queued like a manual backup, and the choice is remembered. */
    suspend fun approve(personId: Long, mediaIds: List<Long>): Int {
        val n = lock.mutex.withLock {
            val now = nowMs()
            db.withTransaction {
                var q = 0
                for (chunk in mediaIds.chunked(APPLY_BATCH)) {
                    q += media.enqueueForRule(chunk, now)
                    dao.upsertDecisions(chunk.map { FaceBackupDecisionEntity(it, personId, DecisionOutcome.QUEUED, DecisionReason.USER, now) })
                }
                q
            }
        }
        if (n > 0) backup.afterEnqueue(n)
        return n
    }

    /** The user says no: the photo is not offered again for this person. */
    suspend fun dismiss(personId: Long, mediaIds: List<Long>) = lock.mutex.withLock {
        val now = nowMs()
        dao.upsertDecisions(mediaIds.map { FaceBackupDecisionEntity(it, personId, DecisionOutcome.DISMISSED, DecisionReason.USER, now) })
    }

    suspend fun exclude(personId: Long, mediaIds: List<Long>) = lock.mutex.withLock {
        val now = nowMs()
        db.withTransaction {
            dao.insertExclusions(mediaIds.map { FaceRuleExclusionEntity(personId, it, now) })
            dao.clearReviewFor(personId, mediaIds)
        }
    }

    suspend fun include(personId: Long, mediaIds: List<Long>) = lock.mutex.withLock { dao.removeExclusions(personId, mediaIds) }

    private companion object { const val APPLY_BATCH = 200 }
}
