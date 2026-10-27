package com.s3vault.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.s3vault.app.domain.faces.DecisionOutcome
import com.s3vault.app.domain.faces.FaceAssignment
import com.s3vault.app.domain.faces.PersonState
import com.s3vault.app.domain.faces.RulePolicy
import com.s3vault.app.domain.faces.RuleState
import kotlinx.coroutines.flow.Flow

/** A suggested match as the review screen needs it: which face, which photo, which group it may belong to. */
data class SuggestionRow(
    val faceId: Long,
    val mediaStoreId: Long,
    val uri: String,
    val boxLeft: Float,
    val boxTop: Float,
    val boxRight: Float,
    val boxBottom: Float,
    val suggestedPersonId: Long,
    val matchScore: Float,
)

/** What a rule needs to judge one photo of a person: how the face was matched and where the photo stands in the backup. */
data class RuleCandidateRow(
    val faceId: Long,
    val mediaStoreId: Long,
    val assignment: FaceAssignment,
    val matchScore: Float,
    val margin: Float,
    val quality: Float,
    val syncState: com.s3vault.app.domain.SyncState,
    val backupExcluded: Boolean,
    val dateAddedSec: Long,
    val sizeBytes: Long,
)

/** A photo waiting in a person's review list, with why the rule did not decide. */
data class ReviewRow(
    val personId: Long,
    val reason: com.s3vault.app.domain.faces.DecisionReason,
    @androidx.room.Embedded val media: MediaItemEntity,
)

data class FaceRef(val faceId: Long, val personId: Long)

data class PhotoPerson(val mediaStoreId: Long, val personId: Long)

@Dao
interface FaceDao {
    // ---- What still needs analysing. Newest first (ids grow with time), paged by id so a failure never makes a run loop.

    @Query(
        "SELECT m.mediaStoreId, m.uri, m.sizeBytes, m.dateModifiedSec, m.width, m.height FROM media_items m " +
            "LEFT JOIN face_photos p ON p.mediaStoreId = m.mediaStoreId AND p.sizeBytes = m.sizeBytes AND p.dateModifiedSec = m.dateModifiedSec " +
            "AND p.pipelineVersion = :version AND p.modelId = :modelId " +
            "WHERE m.present = 1 AND m.kind = 'IMAGE' AND p.mediaStoreId IS NULL AND m.mediaStoreId < :before ORDER BY m.mediaStoreId DESC LIMIT :limit",
    )
    suspend fun needAnalysis(modelId: String, version: Int, before: Long, limit: Int): List<FaceCandidate>

    @Query(
        "SELECT COUNT(*) FROM media_items m LEFT JOIN face_photos p ON p.mediaStoreId = m.mediaStoreId AND p.sizeBytes = m.sizeBytes " +
            "AND p.dateModifiedSec = m.dateModifiedSec AND p.pipelineVersion = :version AND p.modelId = :modelId " +
            "WHERE m.present = 1 AND m.kind = 'IMAGE' AND p.mediaStoreId IS NULL",
    )
    suspend fun needAnalysisCount(modelId: String, version: Int): Int

    @Query(
        "SELECT COUNT(*) FROM media_items m LEFT JOIN face_photos p ON p.mediaStoreId = m.mediaStoreId AND p.sizeBytes = m.sizeBytes " +
            "AND p.dateModifiedSec = m.dateModifiedSec AND p.pipelineVersion = :version AND p.modelId = :modelId " +
            "WHERE m.present = 1 AND m.kind = 'IMAGE' AND p.mediaStoreId IS NULL",
    )
    fun observeNeedAnalysis(modelId: String, version: Int): Flow<Int>

    @Query("SELECT COUNT(*) FROM media_items WHERE present = 1 AND kind = 'IMAGE'")
    fun observeImageCount(): Flow<Int>

    /** Photos with a valid analysis (including those with no faces at all). */
    @Query(
        "SELECT COUNT(*) FROM face_photos p JOIN media_items m ON m.mediaStoreId = p.mediaStoreId WHERE m.present = 1 AND p.sizeBytes = m.sizeBytes " +
            "AND p.dateModifiedSec = m.dateModifiedSec AND p.pipelineVersion = :version AND p.modelId = :modelId",
    )
    fun observeAnalyzed(modelId: String, version: Int): Flow<Int>

    @Query("SELECT COUNT(*) FROM face_photos WHERE status = 'UNREADABLE'")
    fun observeUnreadable(): Flow<Int>

    /** Photos analysed with an older pipeline or model: their result still shows, and they are redone in the background. */
    @Query("SELECT COUNT(*) FROM face_photos WHERE pipelineVersion != :version OR modelId != :modelId")
    fun observeStale(modelId: String, version: Int): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPhotos(rows: List<FacePhotoEntity>)

    @Query("SELECT * FROM face_photos WHERE mediaStoreId = :id")
    suspend fun photo(id: Long): FacePhotoEntity?

    // ---- Faces

    @Query("SELECT * FROM faces WHERE mediaStoreId = :mediaStoreId ORDER BY id")
    suspend fun facesOfPhoto(mediaStoreId: Long): List<FaceEntity>

    @Query("SELECT * FROM faces WHERE id IN (:ids)")
    suspend fun faces(ids: List<Long>): List<FaceEntity>

    @Query("SELECT * FROM faces WHERE id = :id")
    suspend fun face(id: Long): FaceEntity?

    @Insert
    suspend fun insertFaces(rows: List<FaceEntity>): List<Long>

    @Update
    suspend fun updateFaces(rows: List<FaceEntity>)

    @Query("DELETE FROM faces WHERE id IN (:ids)")
    suspend fun deleteFaces(ids: List<Long>)

    /** Faces in no group and with no pending suggestion: the pool the grouping forms new groups from. */
    @Query("SELECT * FROM faces WHERE assignment = 'NONE' AND personId IS NULL")
    suspend fun pool(): List<FaceEntity>

    @Query("SELECT COUNT(*) FROM faces WHERE assignment = 'NONE' AND personId IS NULL")
    fun observePoolCount(): Flow<Int>

    @Query("SELECT * FROM faces WHERE personId = :personId AND assignment IN ('AUTO', 'CONFIRMED')")
    suspend fun membersOf(personId: Long): List<FaceEntity>

    @Query("SELECT mediaStoreId FROM faces WHERE personId = :personId")
    suspend fun photosOfPerson(personId: Long): List<Long>

    /** Every face of a group, however it got there (the members that count for the centre are [membersOf]). */
    @Query("SELECT * FROM faces WHERE personId = :personId")
    suspend fun membersOfAll(personId: Long): List<FaceEntity>

    /** Which groups already have a face in these photos: two faces of one photo are never one person. */
    @Query("SELECT DISTINCT mediaStoreId, personId FROM faces WHERE mediaStoreId IN (:mediaIds) AND personId IS NOT NULL")
    suspend fun personsInPhotos(mediaIds: List<Long>): List<PhotoPerson>

    @Query("SELECT COUNT(*) FROM faces WHERE mediaStoreId = :mediaId AND personId = :personId")
    suspend fun photoHasPerson(mediaId: Long, personId: Long): Int

    /** Moves faces to another group, keeping what the user confirmed and marking the rest as automatic. */
    @Query("UPDATE faces SET personId = :personId, suggestedPersonId = NULL, assignment = CASE assignment WHEN 'CONFIRMED' THEN 'CONFIRMED' ELSE 'AUTO' END WHERE id IN (:ids)")
    suspend fun reassign(ids: List<Long>, personId: Long)

    @Query(
        "UPDATE faces SET personId = :personId, assignment = :assignment, matchScore = :score, margin = :margin, suggestedPersonId = NULL WHERE id IN (:ids)",
    )
    suspend fun assign(ids: List<Long>, personId: Long?, assignment: FaceAssignment, score: Float, margin: Float)

    @Query("UPDATE faces SET personId = NULL, assignment = 'SUGGESTED', suggestedPersonId = :personId, matchScore = :score, margin = :margin WHERE id = :faceId")
    suspend fun suggest(faceId: Long, personId: Long, score: Float, margin: Float)

    @Query("UPDATE faces SET personId = NULL, assignment = 'NONE', suggestedPersonId = NULL, matchScore = 0, margin = 0 WHERE id IN (:ids)")
    suspend fun release(ids: List<Long>)

    @Query("UPDATE faces SET assignment = 'CONFIRMED' WHERE personId = :personId AND assignment = 'AUTO'")
    suspend fun confirmMembers(personId: Long)

    @Query("UPDATE faces SET assignment = 'CONFIRMED' WHERE id IN (:ids)")
    suspend fun confirmFaces(ids: List<Long>)

    /** Faces proposed for a group that no longer exists go back to the pool. */
    @Query("UPDATE faces SET assignment = 'NONE', suggestedPersonId = NULL WHERE assignment = 'SUGGESTED' AND suggestedPersonId = :personId")
    suspend fun releaseSuggestionsFor(personId: Long)

    @Query("DELETE FROM faces WHERE mediaStoreId NOT IN (SELECT mediaStoreId FROM media_items WHERE present = 1)")
    suspend fun dropOrphanFaces(): Int

    @Query("DELETE FROM face_photos WHERE mediaStoreId NOT IN (SELECT mediaStoreId FROM media_items WHERE present = 1)")
    suspend fun dropOrphanPhotos(): Int

    /** A few sealed vectors, to check that the key still opens what is stored. */
    @Query("SELECT sealed FROM faces LIMIT 3")
    suspend fun sampleSealed(): List<ByteArray>

    /** Groups the grouping made that lost all their faces (their photos went away). A group the user named is kept. */
    @Query("DELETE FROM people WHERE state = 'PROVISIONAL' AND id NOT IN (SELECT DISTINCT personId FROM faces WHERE personId IS NOT NULL)")
    suspend fun deleteEmptyProvisionalPeople(): Int

    @Query("DELETE FROM faces")
    suspend fun deleteAllFaces()

    @Query("DELETE FROM face_photos")
    suspend fun deleteAllPhotos()

    @Query("DELETE FROM people")
    suspend fun deleteAllPeople()

    @Query("SELECT COALESCE(SUM(LENGTH(sealed) + 80), 0) FROM faces")
    fun observeIndexBytes(): Flow<Long>

    @Query("SELECT COUNT(*) FROM faces")
    fun observeFaceCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM faces")
    suspend fun faceCount(): Int

    // ---- People

    @Query("SELECT * FROM people")
    suspend fun people(): List<PersonEntity>

    @Query("SELECT * FROM people WHERE id = :id")
    suspend fun person(id: Long): PersonEntity?

    @Query("SELECT * FROM people WHERE id = :id")
    fun observePerson(id: Long): Flow<PersonEntity?>

    @Insert
    suspend fun insertPerson(p: PersonEntity): Long

    @Update
    suspend fun updatePerson(p: PersonEntity)

    @Query("UPDATE people SET name = :name, state = CASE WHEN state = 'PROVISIONAL' AND :name IS NOT NULL THEN 'CONFIRMED' ELSE state END, updatedAtMs = :nowMs WHERE id = :id")
    suspend fun rename(id: Long, name: String?, nowMs: Long)

    @Query("UPDATE people SET state = :state, updatedAtMs = :nowMs WHERE id = :id")
    suspend fun setState(id: Long, state: PersonState, nowMs: Long)

    @Query("UPDATE people SET coverFaceId = :faceId WHERE id = :id")
    suspend fun setCover(id: Long, faceId: Long?)

    @Query("DELETE FROM people WHERE id = :id")
    suspend fun deletePerson(id: Long)

    @Query("SELECT COALESCE(MAX(ordinal), 0) + 1 FROM people")
    suspend fun nextOrdinal(): Int

    @Query(
        "SELECT p.id AS id, p.name AS name, p.ordinal AS ordinal, p.state AS state, p.coverFaceId AS coverFaceId, " +
            "COUNT(DISTINCT f.mediaStoreId) AS photos, COUNT(f.id) AS faces, " +
            "COALESCE(SUM(CASE WHEN f.assignment = 'CONFIRMED' THEN 1 ELSE 0 END), 0) AS confirmedFaces, r.state AS ruleState " +
            "FROM people p JOIN faces f ON f.personId = p.id JOIN media_items m ON m.mediaStoreId = f.mediaStoreId AND m.present = 1 " +
            "LEFT JOIN face_backup_rules r ON r.personId = p.id " +
            "WHERE p.state != 'HIDDEN' GROUP BY p.id ORDER BY (p.name IS NULL), COUNT(DISTINCT f.mediaStoreId) DESC, p.id",
    )
    fun observePeople(): Flow<List<PersonRow>>

    @Query(
        "SELECT p.id AS id, p.name AS name, p.ordinal AS ordinal, p.state AS state, p.coverFaceId AS coverFaceId, " +
            "COUNT(DISTINCT f.mediaStoreId) AS photos, COUNT(f.id) AS faces, " +
            "COALESCE(SUM(CASE WHEN f.assignment = 'CONFIRMED' THEN 1 ELSE 0 END), 0) AS confirmedFaces, r.state AS ruleState " +
            "FROM people p LEFT JOIN faces f ON f.personId = p.id LEFT JOIN face_backup_rules r ON r.personId = p.id WHERE p.state = 'HIDDEN' GROUP BY p.id ORDER BY p.id",
    )
    fun observeHiddenPeople(): Flow<List<PersonRow>>

    @Query(
        "SELECT p.id AS id, p.name AS name, p.ordinal AS ordinal, p.state AS state, p.coverFaceId AS coverFaceId, " +
            "COUNT(DISTINCT f.mediaStoreId) AS photos, COUNT(f.id) AS faces, " +
            "COALESCE(SUM(CASE WHEN f.assignment = 'CONFIRMED' THEN 1 ELSE 0 END), 0) AS confirmedFaces, r.state AS ruleState " +
            "FROM people p LEFT JOIN faces f ON f.personId = p.id LEFT JOIN face_backup_rules r ON r.personId = p.id WHERE p.id = :id GROUP BY p.id",
    )
    fun observePersonRow(id: Long): Flow<PersonRow?>

    /** Where each face of a group is, for drawing covers and the face strip. */
    @Query(
        "SELECT f.id AS faceId, f.mediaStoreId AS mediaStoreId, m.uri AS uri, f.boxLeft AS boxLeft, f.boxTop AS boxTop, f.boxRight AS boxRight, f.boxBottom AS boxBottom " +
            "FROM faces f JOIN media_items m ON m.mediaStoreId = f.mediaStoreId WHERE f.id IN (:ids)",
    )
    suspend fun places(ids: List<Long>): List<FacePlace>

    @Query(
        "SELECT f.id AS faceId, f.mediaStoreId AS mediaStoreId, m.uri AS uri, f.boxLeft AS boxLeft, f.boxTop AS boxTop, f.boxRight AS boxRight, f.boxBottom AS boxBottom " +
            "FROM faces f JOIN media_items m ON m.mediaStoreId = f.mediaStoreId AND m.present = 1 WHERE f.personId = :personId " +
            "ORDER BY CASE f.assignment WHEN 'CONFIRMED' THEN 0 ELSE 1 END, f.quality DESC, f.id",
    )
    fun observeFacesOfPerson(personId: Long): Flow<List<FacePlace>>

    /** Every face of a group with the photo it is in (one row per face: a photo with two faces of the group appears twice). */
    @Query(
        "SELECT m.* FROM media_items m WHERE m.present = 1 AND m.mediaStoreId IN (SELECT mediaStoreId FROM faces WHERE personId = :personId) " +
            "ORDER BY m.dateTakenMs DESC, m.mediaStoreId DESC",
    )
    fun observePhotosOfPerson(personId: Long): Flow<List<MediaItemEntity>>

    @Query("SELECT id FROM faces WHERE personId = :personId AND mediaStoreId IN (:mediaIds)")
    suspend fun faceIdsInPhotos(personId: Long, mediaIds: List<Long>): List<Long>

    @Query(
        "SELECT f.id AS faceId, f.mediaStoreId AS mediaStoreId, m.uri AS uri, f.boxLeft AS boxLeft, f.boxTop AS boxTop, f.boxRight AS boxRight, f.boxBottom AS boxBottom " +
            "FROM faces f JOIN media_items m ON m.mediaStoreId = f.mediaStoreId AND m.present = 1 WHERE f.assignment = 'NONE' AND f.personId IS NULL " +
            "ORDER BY f.quality DESC, f.id LIMIT :limit",
    )
    fun observeUnassigned(limit: Int): Flow<List<FacePlace>>

    @Query(
        "SELECT f.id AS faceId, f.mediaStoreId AS mediaStoreId, m.uri AS uri, f.boxLeft AS boxLeft, f.boxTop AS boxTop, f.boxRight AS boxRight, f.boxBottom AS boxBottom, " +
            "f.suggestedPersonId AS suggestedPersonId, f.matchScore AS matchScore FROM faces f JOIN media_items m ON m.mediaStoreId = f.mediaStoreId AND m.present = 1 " +
            "JOIN people p ON p.id = f.suggestedPersonId WHERE f.assignment = 'SUGGESTED' ORDER BY f.suggestedPersonId, f.matchScore DESC",
    )
    fun observeSuggestions(): Flow<List<SuggestionRow>>

    @Query("SELECT COUNT(*) FROM faces WHERE assignment = 'SUGGESTED'")
    fun observeSuggestionCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM people WHERE state != 'HIDDEN'")
    fun observePeopleCount(): Flow<Int>

    // ---- Corrections

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRejections(rows: List<FaceRejectionEntity>)

    @Query("SELECT faceId, personId FROM face_rejections WHERE faceId IN (:faceIds)")
    suspend fun rejectionsOf(faceIds: List<Long>): List<FaceRef>

    @Query("DELETE FROM face_rejections WHERE faceId IN (:faceIds) AND personId = :personId")
    suspend fun clearRejections(faceIds: List<Long>, personId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMergeRejection(row: PersonMergeRejectionEntity)

    @Query("SELECT * FROM person_merge_rejections")
    suspend fun mergeRejections(): List<PersonMergeRejectionEntity>

    @Query("DELETE FROM person_merge_rejections")
    suspend fun clearMergeRejections()

    // ---- Backup rules

    @Query("SELECT * FROM face_backup_rules WHERE personId = :personId")
    suspend fun rule(personId: Long): FaceBackupRuleEntity?

    @Query("SELECT * FROM face_backup_rules WHERE personId = :personId")
    fun observeRule(personId: Long): Flow<FaceBackupRuleEntity?>

    @Query("SELECT * FROM face_backup_rules")
    suspend fun rules(): List<FaceBackupRuleEntity>

    @Query("SELECT COUNT(*) FROM face_backup_rules WHERE state = 'ACTIVE'")
    fun observeActiveRules(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(rule: FaceBackupRuleEntity)

    @Query("UPDATE face_backup_rules SET state = :state, updatedAtMs = :nowMs WHERE personId = :personId")
    suspend fun setRuleState(personId: Long, state: RuleState, nowMs: Long)

    @Query("UPDATE face_backup_rules SET existingPending = :pending, updatedAtMs = :nowMs WHERE personId = :personId")
    suspend fun setExistingPending(personId: Long, pending: Boolean, nowMs: Long)

    @Query("UPDATE face_backup_rules SET policy = :policy, updatedAtMs = :nowMs WHERE personId = :personId")
    suspend fun setRulePolicy(personId: Long, policy: RulePolicy, nowMs: Long)

    @Query("DELETE FROM face_backup_rules WHERE personId = :personId")
    suspend fun deleteRule(personId: Long)

    @Query("DELETE FROM face_backup_rules")
    suspend fun deleteAllRules()

    @Query(
        "SELECT f.id AS faceId, f.mediaStoreId AS mediaStoreId, f.assignment AS assignment, f.matchScore AS matchScore, f.margin AS margin, f.quality AS quality, " +
            "m.syncState AS syncState, m.backupExcluded AS backupExcluded, m.dateAddedSec AS dateAddedSec, m.sizeBytes AS sizeBytes " +
            "FROM faces f JOIN media_items m ON m.mediaStoreId = f.mediaStoreId WHERE f.personId = :personId AND m.present = 1 AND m.kind = 'IMAGE'",
    )
    suspend fun ruleCandidates(personId: Long): List<RuleCandidateRow>

    /** Faces the grouping only suggests for this person: never queued by a rule, but the user is told they exist. */
    @Query(
        "SELECT f.id AS faceId, f.mediaStoreId AS mediaStoreId, f.assignment AS assignment, f.matchScore AS matchScore, f.margin AS margin, f.quality AS quality, " +
            "m.syncState AS syncState, m.backupExcluded AS backupExcluded, m.dateAddedSec AS dateAddedSec, m.sizeBytes AS sizeBytes " +
            "FROM faces f JOIN media_items m ON m.mediaStoreId = f.mediaStoreId WHERE f.assignment = 'SUGGESTED' AND f.suggestedPersonId = :personId AND m.present = 1 AND m.kind = 'IMAGE'",
    )
    suspend fun suggestedCandidates(personId: Long): List<RuleCandidateRow>

    @Query("SELECT mediaStoreId FROM face_rule_exclusions WHERE personId = :personId")
    suspend fun exclusions(personId: Long): List<Long>

    @Query("SELECT mediaStoreId FROM face_rule_exclusions WHERE personId = :personId")
    fun observeExclusions(personId: Long): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExclusions(rows: List<FaceRuleExclusionEntity>)

    @Query("DELETE FROM face_rule_exclusions WHERE personId = :personId AND mediaStoreId IN (:mediaIds)")
    suspend fun removeExclusions(personId: Long, mediaIds: List<Long>)

    @Query("SELECT * FROM face_backup_decisions WHERE personId = :personId")
    suspend fun decisions(personId: Long): List<FaceBackupDecisionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDecisions(rows: List<FaceBackupDecisionEntity>)

    @Query("DELETE FROM face_backup_decisions WHERE personId = :personId AND outcome = :outcome")
    suspend fun deleteDecisions(personId: Long, outcome: DecisionOutcome)

    @Query("DELETE FROM face_backup_decisions WHERE personId = :personId AND outcome = 'REVIEW'")
    suspend fun clearReview(personId: Long)

    @Query("DELETE FROM face_backup_decisions WHERE personId = :personId AND mediaStoreId IN (:mediaIds) AND outcome = 'REVIEW'")
    suspend fun clearReviewFor(personId: Long, mediaIds: List<Long>)

    @Query(
        "SELECT m.* FROM face_backup_decisions d JOIN media_items m ON m.mediaStoreId = d.mediaStoreId WHERE d.personId = :personId AND d.outcome = 'REVIEW' " +
            "AND m.present = 1 AND m.syncState = 'LOCAL_ONLY' ORDER BY m.dateTakenMs DESC",
    )
    fun observeReview(personId: Long): Flow<List<MediaItemEntity>>

    @Query(
        "SELECT COUNT(DISTINCT d.mediaStoreId) FROM face_backup_decisions d JOIN media_items m ON m.mediaStoreId = d.mediaStoreId " +
            "WHERE d.outcome = 'REVIEW' AND m.present = 1 AND m.syncState = 'LOCAL_ONLY'",
    )
    fun observeReviewCount(): Flow<Int>

    @Query(
        "SELECT d.personId AS personId, d.reason AS reason, m.* FROM face_backup_decisions d JOIN media_items m ON m.mediaStoreId = d.mediaStoreId " +
            "WHERE d.outcome = 'REVIEW' AND m.present = 1 AND m.syncState = 'LOCAL_ONLY' ORDER BY d.personId, m.dateTakenMs DESC LIMIT 600",
    )
    fun observeReviewItems(): Flow<List<ReviewRow>>

    @Query("SELECT mediaStoreId FROM face_backup_decisions WHERE personId = :personId AND outcome = 'QUEUED'")
    suspend fun queuedByRule(personId: Long): List<Long>

    @Query("SELECT COUNT(*) FROM face_backup_decisions d JOIN media_items m ON m.mediaStoreId = d.mediaStoreId WHERE d.personId = :personId AND d.outcome = 'QUEUED' AND m.syncState IN ('QUEUED', 'UPLOADING', 'VERIFYING')")
    fun observeWaitingByRule(personId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM face_backup_decisions d JOIN media_items m ON m.mediaStoreId = d.mediaStoreId WHERE d.personId = :personId AND d.outcome = 'QUEUED' AND m.syncState = 'BACKED_UP'")
    fun observeBackedUpByRule(personId: Long): Flow<Int>
}
