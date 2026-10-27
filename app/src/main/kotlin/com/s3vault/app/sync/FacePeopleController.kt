package com.s3vault.app.sync

import androidx.room.withTransaction
import com.s3vault.app.data.local.AppDatabase
import com.s3vault.app.data.local.FaceRejectionEntity
import com.s3vault.app.data.local.PersonEntity
import com.s3vault.app.data.local.PersonMergeRejectionEntity
import com.s3vault.app.domain.faces.FaceAssignment
import com.s3vault.app.domain.faces.FaceVault
import com.s3vault.app.domain.faces.PersonState
import kotlinx.coroutines.sync.withLock

/** How many of the faces the user chose could be moved; the rest would have put two faces of one photo in one group. */
class MoveResult(val moved: Int, val skippedSamePhoto: Int, val targetId: Long?)

/**
 * What the user can do to the groups. Every method changes the face index and nothing else: no photo file, no backup record and no
 * S3 object is ever touched, and the corrections are remembered (a face taken out of a group is not put back by a later analysis).
 */
class FacePeopleController(
    private val db: AppDatabase,
    private val grouping: FaceGroupingStore,
    private val vault: FaceVault,
    private val lock: FaceIndexLock,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Told which groups changed, so a backup rule can look at them again. */
    private val onChanged: suspend (Set<Long>) -> Unit = {},
) {
    private val dao get() = db.faces()

    /** A blank name goes back to the neutral label. Naming a group confirms it. */
    suspend fun rename(personId: Long, name: String?) = lock.mutex.withLock {
        dao.rename(personId, name?.trim()?.take(MAX_NAME)?.ifBlank { null }, nowMs())
    }

    /** "Estas fotos son de la misma persona": the group and every face in it count as confirmed. */
    suspend fun confirm(personId: Long) = lock.mutex.withLock {
        db.withTransaction {
            dao.setState(personId, PersonState.CONFIRMED, nowMs())
            dao.confirmMembers(personId)
        }
    }

    /** "No sugerir más": the group stays (so its faces keep joining it quietly) but is never shown, suggested or backed up. */
    suspend fun hide(personId: Long, hidden: Boolean) = lock.mutex.withLock {
        db.withTransaction {
            val p = dao.person(personId) ?: return@withTransaction
            dao.setState(personId, if (hidden) PersonState.HIDDEN else if (p.name != null) PersonState.CONFIRMED else PersonState.PROVISIONAL, nowMs())
            if (hidden) dao.deleteRule(personId)
        }
    }

    /**
     * Takes the faces of [personId] that are in [mediaIds] out of the group, for good: they are remembered as "not this person".
     * The photos stay where they are, on the device and in S3. A group left without faces disappears.
     */
    suspend fun removePhotos(personId: Long, mediaIds: List<Long>): Int {
        var n = 0
        lock.mutex.withLock {
            db.withTransaction {
                val ids = mediaIds.chunked(400).flatMap { dao.faceIdsInPhotos(personId, it) }
                if (ids.isEmpty()) return@withTransaction
                val now = nowMs()
                dao.insertRejections(ids.map { FaceRejectionEntity(it, personId, now) })
                dao.release(ids)
                n = ids.size
                settle(listOf(personId))
            }
        }
        onChanged(setOf(personId))
        return n
    }

    /**
     * Moves faces to another group, or to a new one when [targetId] is null ("separar"). What the user chooses is final for the
     * grouping: the faces become confirmed in the target and rejected for the group they came from.
     */
    suspend fun moveFaces(faceIds: List<Long>, targetId: Long?): MoveResult {
        var result = MoveResult(0, 0, targetId)
        val touched = HashSet<Long>()
        lock.mutex.withLock {
            db.withTransaction {
                val faces = faceIds.distinct().chunked(400).flatMap { dao.faces(it) }
                if (faces.isEmpty()) return@withTransaction
                val now = nowMs()
                val target = targetId ?: dao.insertPerson(PersonEntity(ordinal = dao.nextOrdinal(), state = PersonState.CONFIRMED, createdAtMs = now, updatedAtMs = now))
                var moved = 0; var skipped = 0
                val movedIds = ArrayList<Long>()
                val seenPhotos = HashSet<Long>()
                for (f in faces) {
                    if (f.personId == target) continue
                    // Two faces of one photo are two people; the same face already in the target is not a conflict with itself.
                    if (!seenPhotos.add(f.mediaStoreId) || dao.photoHasPerson(f.mediaStoreId, target) > 0) { skipped++; continue }
                    movedIds += f.id; moved++
                    f.personId?.let { touched += it; dao.insertRejections(listOf(FaceRejectionEntity(f.id, it, now))) }
                    f.suggestedPersonId?.let { touched += it }
                }
                if (movedIds.isNotEmpty()) {
                    dao.clearRejections(movedIds, target)
                    dao.assign(movedIds, target, FaceAssignment.CONFIRMED, 0f, 0f)
                    touched += target
                }
                settle(touched)
                result = MoveResult(moved, skipped, target)
                if (moved == 0 && targetId == null) dao.deletePerson(target) // nothing moved: do not leave an empty new group behind
            }
        }
        onChanged(touched)
        return result
    }

    /** Joins [fromId] into [intoId]. The faces keep their confirmed state; the rule of [fromId] goes away with its group. */
    suspend fun merge(intoId: Long, fromId: Long): Int {
        if (intoId == fromId) return 0
        var n = 0
        lock.mutex.withLock {
            db.withTransaction {
                val into = dao.person(intoId) ?: return@withTransaction
                val from = dao.person(fromId) ?: return@withTransaction
                val faces = dao.membersOfAll(fromId)
                // Faces of one photo cannot be one person: the ones that collide stay out of the merged group.
                val samePhoto = faces.filter { dao.photoHasPerson(it.mediaStoreId, intoId) > 0 }.map { it.id }.toSet()
                val ids = faces.map { it.id }.filter { it !in samePhoto }
                if (ids.isNotEmpty()) { dao.reassign(ids, intoId); dao.confirmFaces(ids) }
                n = ids.size
                dao.membersOfAll(fromId).map { it.id }.let { rest -> if (rest.isNotEmpty()) dao.release(rest) }
                dao.updatePerson(into.copy(name = into.name ?: from.name, state = if (into.state == PersonState.HIDDEN) into.state else PersonState.CONFIRMED, updatedAtMs = nowMs()))
                dao.deletePerson(fromId)
                grouping.refreshCenters(listOf(intoId))
            }
        }
        onChanged(setOf(intoId))
        return n
    }

    /** "Son personas distintas": the grouping stops offering to merge these two. */
    suspend fun keepApart(a: Long, b: Long) = lock.mutex.withLock {
        dao.insertMergeRejection(PersonMergeRejectionEntity(minOf(a, b), maxOf(a, b), nowMs()))
    }

    /** The user says yes to suggested faces: they join the group they were proposed for, as confirmed. */
    suspend fun acceptSuggestions(faceIds: List<Long>): Int {
        var n = 0
        val touched = HashSet<Long>()
        lock.mutex.withLock {
            db.withTransaction {
                val faces = faceIds.distinct().chunked(400).flatMap { dao.faces(it) }.filter { it.assignment == FaceAssignment.SUGGESTED && it.suggestedPersonId != null }
                for (f in faces) {
                    val target = f.suggestedPersonId!!
                    if (dao.photoHasPerson(f.mediaStoreId, target) > 0) { dao.release(listOf(f.id)); continue }
                    dao.assign(listOf(f.id), target, FaceAssignment.CONFIRMED, f.matchScore, f.margin)
                    touched += target; n++
                }
                settle(touched)
            }
        }
        onChanged(touched)
        return n
    }

    /** The user says no: the face is not that person, and will not be proposed for it again. */
    suspend fun rejectSuggestions(faceIds: List<Long>): Int = lock.mutex.withLock {
        db.withTransaction {
            val faces = faceIds.distinct().chunked(400).flatMap { dao.faces(it) }.filter { it.assignment == FaceAssignment.SUGGESTED && it.suggestedPersonId != null }
            val now = nowMs()
            dao.insertRejections(faces.map { FaceRejectionEntity(it.id, it.suggestedPersonId!!, now) })
            dao.release(faces.map { it.id })
            faces.size
        }
    }

    /** Deletes a group and the face data of its photos (vectors, boxes). The photos, their backups and S3 are untouched. */
    suspend fun deletePerson(personId: Long) {
        lock.mutex.withLock {
            db.withTransaction {
                dao.membersOfAll(personId).map { it.id }.chunked(400).forEach { dao.deleteFaces(it) }
                dao.releaseSuggestionsFor(personId)
                dao.deletePerson(personId)
            }
        }
    }

    /**
     * Erases the whole face index: faces, vectors, groups, names, corrections and rules, and destroys the key that sealed them.
     * Photos and backups are untouched. A rule removed here stops adding photos; what it already queued or uploaded stays.
     */
    suspend fun deleteIndex() = lock.mutex.withLock {
        db.withTransaction {
            dao.deleteAllFaces(); dao.deleteAllPhotos(); dao.deleteAllPeople()
        }
        vault.wipe()
    }

    /** After faces left or joined groups: centres and covers are recomputed, and a group left empty is removed. */
    private suspend fun settle(personIds: Collection<Long>) {
        val ids = personIds.distinct()
        for (id in ids) {
            if (dao.person(id) == null) continue
            if (dao.membersOfAll(id).isEmpty()) { dao.releaseSuggestionsFor(id); dao.deletePerson(id) } else grouping.refreshCenters(listOf(id))
        }
    }

    private companion object { const val MAX_NAME = 60 }
}
