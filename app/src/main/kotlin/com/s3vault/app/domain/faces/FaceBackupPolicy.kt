package com.s3vault.app.domain.faces

import com.s3vault.app.domain.SyncState

/**
 * When may a photo go into the backup queue because of a person? The default answers "only when it is very likely right", because
 * a wrong yes uploads someone else's photo to the user's bucket, while a wrong no costs one tap in the review list.
 */
data class PolicyThresholds(
    /** STRICT: an automatic match must be at least this close to the group, ... */
    val strictScore: Float = 0.60f,
    /** ... this far ahead of the next closest person, ... */
    val strictMargin: Float = 0.15f,
    /** ... and come from a face of at least this quality. */
    val strictQuality: Float = 0.45f,
    /** INCLUDE_PROBABLE relaxes the quality, not the need for a clear lead. */
    val probableQuality: Float = 0.30f,
)

/** What the rule knows about one face of the person in the photo. */
class FaceMatch(val assignment: FaceAssignment, val score: Float, val margin: Float, val quality: Float)

/** What the rule knows about the photo. */
class PhotoFacts(val syncState: SyncState, val backupExcluded: Boolean, val dateAddedSec: Long)

sealed interface RuleVerdict {
    /** Put it in the backup queue. */
    data object Queue : RuleVerdict

    /** Not decided: it waits in the review list, with the reason shown to the user. */
    data class Review(val reason: DecisionReason) : RuleVerdict

    /** Nothing to do, and nothing to ask. [why] is for the preview ("already backed up", "excluded by you"...). */
    data class Skip(val why: SkipReason) : RuleVerdict
}

enum class SkipReason { HIDDEN_PERSON, EXCLUDED_BY_USER, DISMISSED, ALREADY_HANDLED, DELETED_FROM_S3_ON_PURPOSE, OUTSIDE_SCOPE }

object FaceBackupPolicy {
    fun decide(
        /** [includeOlder] is true only for the one-time pass the user confirmed for photos that were already there. */
        includeOlder: Boolean, policy: RulePolicy, newSinceSec: Long, person: PersonState,
        photo: PhotoFacts, matches: List<FaceMatch>, excludedByUser: Boolean, dismissed: Boolean,
        t: PolicyThresholds = PolicyThresholds(),
    ): RuleVerdict {
        if (person == PersonState.HIDDEN) return RuleVerdict.Skip(SkipReason.HIDDEN_PERSON)
        if (excludedByUser) return RuleVerdict.Skip(SkipReason.EXCLUDED_BY_USER)
        if (dismissed) return RuleVerdict.Skip(SkipReason.DISMISSED)
        // Only what has never been queued. A failed upload is retried by the user (or the existing retry), not by a face rule.
        if (photo.syncState != SyncState.LOCAL_ONLY) return RuleVerdict.Skip(SkipReason.ALREADY_HANDLED)
        // The user deleted this photo's S3 copy on purpose and kept the file: a rule must not undo that.
        if (photo.backupExcluded) return RuleVerdict.Skip(SkipReason.DELETED_FROM_S3_ON_PURPOSE)
        if (!includeOlder && photo.dateAddedSec < newSinceSec) return RuleVerdict.Skip(SkipReason.OUTSIDE_SCOPE)

        var worst: DecisionReason? = null
        for (m in matches) {
            when (m.assignment) {
                // The user said this face is that person: no threshold applies.
                FaceAssignment.CONFIRMED -> return RuleVerdict.Queue
                FaceAssignment.AUTO -> {
                    val reason = autoReason(m, person, policy, t)
                    if (reason == null) return RuleVerdict.Queue
                    worst = pick(worst, reason)
                }
                // A suggestion is a question to the user, never an answer.
                FaceAssignment.SUGGESTED -> worst = pick(worst, DecisionReason.LOW_CONFIDENCE)
                FaceAssignment.NONE -> Unit
            }
        }
        return if (worst != null) RuleVerdict.Review(worst) else RuleVerdict.Skip(SkipReason.ALREADY_HANDLED)
    }

    /** Null when an automatic match is good enough under [policy]; otherwise why it is not. */
    private fun autoReason(m: FaceMatch, person: PersonState, policy: RulePolicy, t: PolicyThresholds): DecisionReason? {
        if (person == PersonState.PROVISIONAL) return DecisionReason.GROUP_NOT_CONFIRMED
        if (m.margin < t.strictMargin) return DecisionReason.AMBIGUOUS
        return when (policy) {
            RulePolicy.STRICT -> when {
                m.score < t.strictScore -> DecisionReason.LOW_CONFIDENCE
                m.quality < t.strictQuality -> DecisionReason.LOW_QUALITY
                else -> null
            }
            RulePolicy.INCLUDE_PROBABLE -> if (m.quality < t.probableQuality) DecisionReason.LOW_QUALITY else null
        }
    }

    private fun pick(a: DecisionReason?, b: DecisionReason): DecisionReason {
        // The most informative reason to show when several faces disagree: ambiguity first.
        val order = listOf(DecisionReason.AMBIGUOUS, DecisionReason.GROUP_NOT_CONFIRMED, DecisionReason.LOW_CONFIDENCE, DecisionReason.LOW_QUALITY)
        if (a == null) return b
        return if (order.indexOf(a) <= order.indexOf(b)) a else b
    }
}

/** Matches faces found again in a photo to the ones already stored, so a re-analysis keeps what the user did to them. */
object FaceReconciler {
    /** Pairs of (old index, new index) whose boxes overlap by at least [minIou], greedily from the best overlap. */
    fun match(old: List<FloatArray>, new: List<FloatArray>, minIou: Float = 0.4f): List<Pair<Int, Int>> {
        val pairs = ArrayList<Triple<Float, Int, Int>>()
        for (i in old.indices) for (j in new.indices) {
            val v = iou(old[i], new[j])
            if (v >= minIou) pairs += Triple(v, i, j)
        }
        pairs.sortByDescending { it.first }
        val usedOld = HashSet<Int>(); val usedNew = HashSet<Int>()
        val out = ArrayList<Pair<Int, Int>>()
        for ((_, i, j) in pairs) {
            if (i in usedOld || j in usedNew) continue
            usedOld += i; usedNew += j; out += i to j
        }
        return out
    }

    /** Boxes are left, top, right, bottom. */
    fun iou(a: FloatArray, b: FloatArray): Float {
        val ix = maxOf(0f, minOf(a[2], b[2]) - maxOf(a[0], b[0]))
        val iy = maxOf(0f, minOf(a[3], b[3]) - maxOf(a[1], b[1]))
        val inter = ix * iy
        val union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
        return if (union <= 0f) 0f else inter / union
    }
}
