package com.s3vault.app.domain.faces

import com.s3vault.app.domain.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rule that decides whether a photo of a person goes into the backup queue. Precision first: when in doubt, ask. */
class FaceBackupPolicyTest {
    private val now = 1_000_000L

    private fun decide(
        match: List<FaceMatch>, state: SyncState = SyncState.LOCAL_ONLY, excluded: Boolean = false, added: Long = now + 10,
        person: PersonState = PersonState.CONFIRMED, policy: RulePolicy = RulePolicy.STRICT, older: Boolean = false,
        userExcluded: Boolean = false, dismissed: Boolean = false,
    ) = FaceBackupPolicy.decide(older, policy, now, person, PhotoFacts(state, excluded, added), match, userExcluded, dismissed)

    private fun auto(score: Float = 0.8f, margin: Float = 0.4f, quality: Float = 0.8f) = FaceMatch(FaceAssignment.AUTO, score, margin, quality)

    @Test fun `a confirmed face is queued whatever its numbers`() {
        assertEquals(RuleVerdict.Queue, decide(listOf(FaceMatch(FaceAssignment.CONFIRMED, 0f, 0f, 0.1f))))
    }

    @Test fun `a sure automatic match is queued, an unsure one waits for the user`() {
        assertEquals(RuleVerdict.Queue, decide(listOf(auto())))
        assertEquals(RuleVerdict.Review(DecisionReason.LOW_CONFIDENCE), decide(listOf(auto(score = 0.59f))))
        assertEquals(RuleVerdict.Review(DecisionReason.AMBIGUOUS), decide(listOf(auto(margin = 0.05f))))
        assertEquals(RuleVerdict.Review(DecisionReason.LOW_QUALITY), decide(listOf(auto(quality = 0.2f))))
    }

    @Test fun `a suggestion is never an answer`() {
        assertEquals(RuleVerdict.Review(DecisionReason.LOW_CONFIDENCE), decide(listOf(FaceMatch(FaceAssignment.SUGGESTED, 0.55f, 0.2f, 0.9f)), policy = RulePolicy.INCLUDE_PROBABLE))
    }

    @Test fun `a group nobody has reviewed asks first, even for a perfect match`() {
        assertEquals(RuleVerdict.Review(DecisionReason.GROUP_NOT_CONFIRMED), decide(listOf(auto()), person = PersonState.PROVISIONAL))
    }

    @Test fun `probable matches go through only when the user chose to accept them, and still need a clear lead`() {
        val lowScore = listOf(auto(score = 0.5f, margin = 0.3f, quality = 0.5f))
        assertTrue(decide(lowScore) is RuleVerdict.Review)
        assertEquals(RuleVerdict.Queue, decide(lowScore, policy = RulePolicy.INCLUDE_PROBABLE))
        assertEquals(RuleVerdict.Review(DecisionReason.AMBIGUOUS), decide(listOf(auto(margin = 0.05f)), policy = RulePolicy.INCLUDE_PROBABLE))
    }

    @Test fun `a photo with several faces of the person is queued if any one is sure, and reviewed otherwise`() {
        assertEquals(RuleVerdict.Queue, decide(listOf(auto(score = 0.4f), auto())))
        assertEquals(RuleVerdict.Review(DecisionReason.AMBIGUOUS), decide(listOf(auto(score = 0.4f), auto(margin = 0.01f))))
    }

    @Test fun `nothing is queued that is already queued, backed up, uploading or failed`() {
        for (s in SyncState.entries.filter { it != SyncState.LOCAL_ONLY }) {
            assertEquals("state $s", RuleVerdict.Skip(SkipReason.ALREADY_HANDLED), decide(listOf(auto()), state = s))
        }
    }

    @Test fun `a photo whose S3 copy the user deleted on purpose is left alone`() {
        assertEquals(RuleVerdict.Skip(SkipReason.DELETED_FROM_S3_ON_PURPOSE), decide(listOf(auto()), excluded = true))
    }

    @Test fun `photos the user excluded or dismissed are skipped, and a hidden person has no rule`() {
        assertEquals(RuleVerdict.Skip(SkipReason.EXCLUDED_BY_USER), decide(listOf(auto()), userExcluded = true))
        assertEquals(RuleVerdict.Skip(SkipReason.DISMISSED), decide(listOf(auto()), dismissed = true))
        assertEquals(RuleVerdict.Skip(SkipReason.HIDDEN_PERSON), decide(listOf(auto()), person = PersonState.HIDDEN))
    }

    @Test fun `old photos are outside the rule unless the user confirmed the one-time pass`() {
        assertEquals(RuleVerdict.Skip(SkipReason.OUTSIDE_SCOPE), decide(listOf(auto()), added = now - 5))
        assertEquals(RuleVerdict.Queue, decide(listOf(auto()), added = now - 5, older = true))
        assertEquals(RuleVerdict.Queue, decide(listOf(auto()), added = now))
    }

    @Test fun `a photo with no usable face of the person is skipped`() {
        assertEquals(RuleVerdict.Skip(SkipReason.ALREADY_HANDLED), decide(emptyList()))
    }

    @Test fun `faces found again in a photo keep the identity of the old ones`() {
        val old = listOf(floatArrayOf(0.1f, 0.1f, 0.3f, 0.4f), floatArrayOf(0.6f, 0.2f, 0.8f, 0.5f))
        val new = listOf(floatArrayOf(0.61f, 0.21f, 0.81f, 0.52f), floatArrayOf(0.11f, 0.1f, 0.29f, 0.41f), floatArrayOf(0.4f, 0.7f, 0.5f, 0.8f))
        val pairs = FaceReconciler.match(old, new).toSet()
        assertEquals(setOf(0 to 1, 1 to 0), pairs)
        assertTrue(FaceReconciler.match(old, listOf(floatArrayOf(0.9f, 0.9f, 1f, 1f))).isEmpty())
    }
}
