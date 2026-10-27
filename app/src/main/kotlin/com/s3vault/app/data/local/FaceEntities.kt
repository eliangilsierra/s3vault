package com.s3vault.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.s3vault.app.domain.faces.DecisionOutcome
import com.s3vault.app.domain.faces.DecisionReason
import com.s3vault.app.domain.faces.FaceAssignment
import com.s3vault.app.domain.faces.FacePhotoStatus
import com.s3vault.app.domain.faces.PersonState
import com.s3vault.app.domain.faces.RulePolicy
import com.s3vault.app.domain.faces.RuleScope
import com.s3vault.app.domain.faces.RuleState

/*
 * The face index. Everything here is local, sensitive (a face vector identifies a person) and rebuildable from the photos, so the
 * tables keep only what the features need:
 *  - no face crops are stored (the app cuts them from the original when it draws one),
 *  - faces too small, blurry or turned to be told apart are counted, not stored,
 *  - the vectors and the group centres are sealed with AES-GCM (see FaceVault); the rest are plain numbers and labels.
 * Deleting any of these rows never touches a photo, a backup record or an S3 object.
 */

/**
 * The analysis state of one photo. It is valid only while the file has the size and modification time it had when it was
 * analysed and the pipeline is the one that is current, so an unchanged photo is analysed once in its life.
 */
@Entity(tableName = "face_photos", indices = [Index("status")])
class FacePhotoEntity(
    @PrimaryKey val mediaStoreId: Long,
    val sizeBytes: Long,
    val dateModifiedSec: Long,
    val pipelineVersion: Int,
    val modelId: String,
    val status: FacePhotoStatus,
    /** Faces stored for this photo (the ones good enough to compare). */
    val faces: Int,
    /** Faces seen but not stored: too small, blurry, dark or turned. Only a count. */
    val skipped: Int,
    val analyzedAtMs: Long,
)

/**
 * One face in one photo. [sealed] is the quantised 128-number vector, encrypted; [boxLeft]..[boxBottom] is the box in fractions of the
 * photo as it is displayed (orientation applied), so it still fits if the app later reads the photo at another size.
 */
@Entity(
    tableName = "faces",
    foreignKeys = [ForeignKey(entity = PersonEntity::class, parentColumns = ["id"], childColumns = ["personId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("mediaStoreId"), Index("personId"), Index("suggestedPersonId"), Index("assignment")],
)
class FaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaStoreId: Long,
    val boxLeft: Float,
    val boxTop: Float,
    val boxRight: Float,
    val boxBottom: Float,
    val detScore: Float,
    /** 0..1: how much to trust the vector (size, pose, sharpness). */
    val quality: Float,
    val modelId: String,
    val sealed: ByteArray,
    val personId: Long? = null,
    val assignment: FaceAssignment = FaceAssignment.NONE,
    /** Similarity to the group centre when the grouping placed or proposed it; 0 for the user's own choices. */
    val matchScore: Float = 0f,
    /** How far the best group was ahead of the runner-up at that moment. */
    val margin: Float = 0f,
    val suggestedPersonId: Long? = null,
    val createdAtMs: Long,
)

/**
 * A group of faces believed to be one person. [name] is the user's own label and is null until they write one (the screens then
 * say "Persona [ordinal]"): the app never infers a name.
 */
@Entity(tableName = "people", indices = [Index("state")])
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String? = null,
    /** Number for the neutral label ("Persona 3"). Never reused. */
    val ordinal: Int,
    val state: PersonState = PersonState.PROVISIONAL,
    /** Sealed mean of the vectors of the members that count (unit length, 128 floats); null while it cannot be computed. */
    val centroidSealed: ByteArray? = null,
    val centroidCount: Int = 0,
    val coverFaceId: Long? = null,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

/** "This face is not that person": remembered, so a later analysis never puts it back there. */
@Entity(
    tableName = "face_rejections",
    primaryKeys = ["faceId", "personId"],
    foreignKeys = [
        ForeignKey(entity = FaceEntity::class, parentColumns = ["id"], childColumns = ["faceId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PersonEntity::class, parentColumns = ["id"], childColumns = ["personId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("personId")],
)
class FaceRejectionEntity(val faceId: Long, val personId: Long, val createdAtMs: Long)

/** "These two groups are different people": the grouping stops proposing to merge them. [lowId] < [highId]. */
@Entity(
    tableName = "person_merge_rejections",
    primaryKeys = ["lowId", "highId"],
    foreignKeys = [
        ForeignKey(entity = PersonEntity::class, parentColumns = ["id"], childColumns = ["lowId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PersonEntity::class, parentColumns = ["id"], childColumns = ["highId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("highId")],
)
class PersonMergeRejectionEntity(val lowId: Long, val highId: Long, val createdAtMs: Long)

/** The automatic-backup rule of one person. Absent row = no rule. Removing it never removes a backup. */
@Entity(
    tableName = "face_backup_rules",
    foreignKeys = [ForeignKey(entity = PersonEntity::class, parentColumns = ["id"], childColumns = ["personId"], onDelete = ForeignKey.CASCADE)],
)
data class FaceBackupRuleEntity(
    @PrimaryKey val personId: Long,
    val state: RuleState,
    val scope: RuleScope,
    val policy: RulePolicy = RulePolicy.STRICT,
    val enabledAtMs: Long,
    /**
     * Photos that entered MediaStore at or after this moment (date added, seconds) are "new": only those are ever queued by the
     * rule on its own. Older photos that match are handled once, at activation, if the user chose [RuleScope.INCLUDE_EXISTING].
     */
    val newSinceSec: Long,
    /** True from the moment the user confirms "include existing photos" until that one-time pass has finished (it survives a restart). */
    val existingPending: Boolean = false,
    val updatedAtMs: Long,
)

/** A photo the user took out of one person's rule. */
@Entity(
    tableName = "face_rule_exclusions",
    primaryKeys = ["personId", "mediaStoreId"],
    foreignKeys = [ForeignKey(entity = PersonEntity::class, parentColumns = ["id"], childColumns = ["personId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("mediaStoreId")],
)
class FaceRuleExclusionEntity(val personId: Long, val mediaStoreId: Long, val createdAtMs: Long)

/** What a rule decided about one photo and why: the review list, the "added by this rule" undo, and protection against repeating work. */
@Entity(
    tableName = "face_backup_decisions",
    primaryKeys = ["mediaStoreId", "personId"],
    foreignKeys = [ForeignKey(entity = PersonEntity::class, parentColumns = ["id"], childColumns = ["personId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("personId", "outcome"), Index("outcome")],
)
class FaceBackupDecisionEntity(
    val mediaStoreId: Long,
    val personId: Long,
    val outcome: DecisionOutcome,
    val reason: DecisionReason,
    val decidedAtMs: Long,
)

/** What an analysis run needs to read a photo (see [ScanCandidate]; this one carries the dimensions to skip tiny pictures). */
data class FaceCandidate(val mediaStoreId: Long, val uri: String, val sizeBytes: Long, val dateModifiedSec: Long, val width: Int, val height: Int)

/** A person as the grid shows it. */
data class PersonRow(
    val id: Long,
    val name: String?,
    val ordinal: Int,
    val state: PersonState,
    val coverFaceId: Long?,
    val photos: Int,
    val faces: Int,
    val confirmedFaces: Int,
    val ruleState: RuleState?,
)

/** One face with the photo it is in, for covers and for the "faces of this group" views. */
data class FacePlace(val faceId: Long, val mediaStoreId: Long, val uri: String, val boxLeft: Float, val boxTop: Float, val boxRight: Float, val boxBottom: Float)

data class FaceTotals(val photos: Int, val faces: Int, val people: Int, val unassigned: Int, val suggestions: Int, val unreadable: Int)
