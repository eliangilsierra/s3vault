package com.s3vault.app.sync

import androidx.room.withTransaction
import com.s3vault.app.data.local.AppDatabase
import com.s3vault.app.data.local.FaceEntity
import com.s3vault.app.data.local.PersonEntity
import com.s3vault.app.domain.faces.Center
import com.s3vault.app.domain.faces.FaceAssignment
import com.s3vault.app.domain.faces.FaceGrouper
import com.s3vault.app.domain.faces.FaceModel
import com.s3vault.app.domain.faces.FaceMath
import com.s3vault.app.domain.faces.FaceVault
import com.s3vault.app.domain.faces.FaceVec
import com.s3vault.app.domain.faces.FaceVectorCodec
import com.s3vault.app.domain.faces.MergeCandidate
import com.s3vault.app.domain.faces.PersonState
import com.s3vault.app.domain.faces.Placement
import kotlinx.coroutines.sync.Mutex

/** One lock for everything that writes the face index: analysis, grouping, the user's corrections and the backup rules. */
class FaceIndexLock { val mutex = Mutex() }

class IntegrationResult(val placed: Int, val suggested: Int, val newPeople: Int, val pooled: Int, val touched: Set<Long>)

/**
 * Applies the grouping algorithm to the stored faces. Every method here must be called with [FaceIndexLock] held (the callers do):
 * it reads a snapshot, decides in memory and writes the result in one transaction, so a correction by the user can never land
 * between the read and the write.
 *
 * What it never does: touch a face the user confirmed, merge a group the user confirmed, or put a face back in a group the user
 * rejected it from.
 */
class FaceGroupingStore(
    private val db: AppDatabase,
    private val vault: FaceVault,
    val grouper: FaceGrouper = FaceGrouper(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val dao get() = db.faces()

    /** Only vectors of the current model take part: those of an older model live in another space and mean nothing next to them. */
    private fun vec(f: FaceEntity): FaceVec? =
        if (f.modelId != FaceModel.MODEL_ID) null else FaceVectorCodec.openVector(vault, f.sealed)?.let { FaceVec(f.id, f.mediaStoreId, it, f.quality) }

    /** The centre of every group; one that is missing or unreadable is rebuilt from the group's faces. */
    private suspend fun loadCenters(people: List<PersonEntity>): MutableList<Center> {
        val out = ArrayList<Center>(people.size)
        for (p in people) {
            val v = p.centroidSealed?.let { FaceVectorCodec.openCenter(vault, it) }
            if (v != null) { out += Center(p.id, v, p.centroidCount, p.state == PersonState.HIDDEN); continue }
            val members = dao.membersOf(p.id).mapNotNull(::vec)
            FaceMath.mean(members.map { it.v })?.let { out += Center(p.id, it, members.size, p.state == PersonState.HIDDEN) }
        }
        return out
    }

    /**
     * Places the faces in [faceIds] that belong to nobody yet: into an existing group when very close to exactly one, as a
     * suggestion when close but unsure, otherwise into the pool, from which new groups are formed.
     */
    suspend fun integrate(faceIds: Collection<Long>): IntegrationResult {
        if (faceIds.isEmpty()) return IntegrationResult(0, 0, 0, 0, emptySet())
        val fresh = faceIds.distinct().chunked(400).flatMap { dao.faces(it) }.filter { it.assignment == FaceAssignment.NONE && it.personId == null }
        if (fresh.isEmpty()) return IntegrationResult(0, 0, 0, 0, emptySet())
        val people = dao.people()
        val byId = people.associateBy { it.id }
        val centers = loadCenters(people)
        val rejections = fresh.map { it.id }.chunked(400).flatMap { dao.rejectionsOf(it) }.groupBy({ it.faceId }, { it.personId })
        val inPhoto = HashMap<Long, MutableSet<Long>>()
        for (chunk in fresh.map { it.mediaStoreId }.distinct().chunked(400)) {
            for (r in dao.personsInPhotos(chunk)) inPhoto.getOrPut(r.mediaStoreId) { HashSet() } += r.personId
        }

        val vecs = fresh.mapNotNull(::vec)
        val placedTo = HashMap<Long, Placement.Auto>()
        val suggestedTo = HashMap<Long, Placement.Suggest>()
        val unplaced = ArrayList<FaceVec>()
        for (f in vecs.sortedByDescending { it.quality }) {
            val blocked = HashSet<Long>()
            rejections[f.id]?.let(blocked::addAll)
            inPhoto[f.photoId]?.let(blocked::addAll)
            when (val p = grouper.place(f, centers, blocked)) {
                is Placement.Auto -> { placedTo[f.id] = p; inPhoto.getOrPut(f.photoId) { HashSet() } += p.personId }
                is Placement.Suggest -> if (byId[p.personId]?.state == PersonState.HIDDEN) unplaced += f else suggestedTo[f.id] = p
                Placement.None -> unplaced += f
            }
        }

        // New groups from everything that is still alone: the new faces and the ones left over from earlier runs.
        val seen = vecs.map { it.id }.toHashSet()
        val leftovers = dao.pool().filter { it.id !in seen }.mapNotNull(::vec)
        val pool = unplaced + leftovers
        val newGroups = grouper.newGroups(pool, unplaced)

        var created = 0
        val touched = HashSet<Long>()
        db.withTransaction {
            for ((id, p) in placedTo) {
                dao.assign(listOf(id), p.personId, FaceAssignment.AUTO, p.score, p.margin)
                touched += p.personId
            }
            for ((id, s) in suggestedTo) dao.suggest(id, s.personId, s.score, s.margin)
            val newCenters = ArrayList<Center>()
            for (g in newGroups) {
                val now = nowMs()
                val ordinal = dao.nextOrdinal()
                val pid = dao.insertPerson(PersonEntity(ordinal = ordinal, createdAtMs = now, updatedAtMs = now))
                val c = FaceMath.mean(g.map { it.v }) ?: continue
                for (f in g) dao.assign(listOf(f.id), pid, FaceAssignment.AUTO, FaceMath.dot(c, f.v), 0f)
                touched += pid; created++
                newCenters += Center(pid, c, g.size)
            }
            // Faces still alone may belong to the groups just formed (the group mean is steadier than the seed alone).
            if (newCenters.isNotEmpty()) {
                val grouped = newGroups.flatMap { g -> g.map { it.id } }.toHashSet()
                for (f in pool) {
                    if (f.id in grouped) continue
                    val blocked = HashSet<Long>()
                    rejections[f.id]?.let(blocked::addAll)
                    val p = grouper.place(f, newCenters, blocked) as? Placement.Auto ?: continue
                    if (dao.photoHasPerson(f.photoId, p.personId) == 0) {
                        dao.assign(listOf(f.id), p.personId, FaceAssignment.AUTO, p.score, p.margin)
                        touched += p.personId
                    }
                }
            }
            refreshCenters(touched)
        }
        return IntegrationResult(placedTo.size, suggestedTo.size, created, pool.size - newGroups.sumOf { it.size }, touched)
    }

    /** Recomputes the centre, count and cover of each group from the faces it holds now. Call inside a transaction. */
    suspend fun refreshCenters(personIds: Collection<Long>) {
        for (id in personIds.distinct()) {
            val p = dao.person(id) ?: continue
            val members = dao.membersOf(id)
            val vecs = members.mapNotNull(::vec)
            val c = FaceMath.mean(vecs.map { it.v })
            val cover = members.sortedWith(compareByDescending<FaceEntity> { it.assignment == FaceAssignment.CONFIRMED }.thenByDescending { it.quality }).firstOrNull()
            dao.updatePerson(
                p.copy(
                    centroidSealed = c?.let { FaceVectorCodec.sealCenter(vault, it) }, centroidCount = vecs.size,
                    coverFaceId = if (members.any { it.id == p.coverFaceId }) p.coverFaceId else cover?.id, updatedAtMs = nowMs(),
                ),
            )
        }
    }

    /**
     * Joins automatic groups that are almost surely the same person (two bursts of one person's photos that formed apart).
     * Groups the user named, confirmed or hid are never merged here: those pairs are only offered by [mergeSuggestions].
     */
    suspend fun autoMerge(): Int {
        val people = dao.people().filter { it.state == PersonState.PROVISIONAL }
        if (people.size < 2) return 0
        val centers = loadCenters(people)
        val rejected = dao.mergeRejections().map { it.lowId to it.highId }.toSet()
        val photos = HashMap<Long, Set<Long>>()
        for (p in people) photos[p.id] = dao.photosOfPerson(p.id).toHashSet()
        val pairs = grouper.mergeCandidates(centers, rejected) { a, b -> photos[a].orEmpty().any { it in photos[b].orEmpty() } }
            .filter { it.score >= grouper.cfg.mergeAuto }
        var merged = 0
        val gone = HashSet<Long>()
        val size = people.associate { it.id to (photos[it.id]?.size ?: 0) }
        for (c in pairs) {
            if (c.a in gone || c.b in gone) continue
            val (keep, drop) = if ((size[c.a] ?: 0) >= (size[c.b] ?: 0)) c.a to c.b else c.b to c.a
            db.withTransaction {
                dao.membersOfAll(drop).map { it.id }.let { ids -> if (ids.isNotEmpty()) dao.reassign(ids, keep) }
                dao.deletePerson(drop)
                refreshCenters(listOf(keep))
            }
            gone += drop; merged++
        }
        return merged
    }

    /** Pairs of groups that may be one person and that the user has not ruled out: shown for the user to decide, never applied. */
    suspend fun mergeSuggestions(limit: Int = 30): List<MergeCandidate> {
        val people = dao.people().filter { it.state != PersonState.HIDDEN }
        if (people.size < 2) return emptyList()
        val centers = loadCenters(people)
        val rejected = dao.mergeRejections().map { it.lowId to it.highId }.toSet()
        val photos = HashMap<Long, Set<Long>>()
        for (p in people) photos[p.id] = dao.photosOfPerson(p.id).toHashSet()
        return grouper.mergeCandidates(centers, rejected) { a, b -> photos[a].orEmpty().any { it in photos[b].orEmpty() } }.take(limit)
    }

    /**
     * How sure each automatic member of [personId] is, measured now: similarity to the group's current centre and the lead over the
     * closest other person. The numbers stored when a face was placed are history (the centre has moved since, and a face that founded
     * its group never had a rival to measure against), so a rule decides on these.
     */
    suspend fun rescore(personId: Long, faces: Collection<FaceEntity>): Map<Long, Pair<Float, Float>> {
        if (faces.isEmpty()) return emptyMap()
        val centers = loadCenters(dao.people())
        val others = centers.filter { it.personId != personId }
        // A face must not vouch for itself: its similarity is measured to the group without it (leave-one-out).
        val members = dao.membersOf(personId).mapNotNull(::vec)
        val sum = FloatArray(FaceModel.DIMENSION)
        for (m in members) for (i in sum.indices) sum[i] += m.v[i]
        val inGroup = members.associateBy { it.id }
        val out = HashMap<Long, Pair<Float, Float>>()
        for (f in faces) {
            val v = (inGroup[f.id] ?: vec(f))?.v ?: continue
            val rest = if (inGroup.containsKey(f.id)) FloatArray(sum.size) { sum[it] - v[it] } else sum
            val centre = FaceMath.mean(listOf(rest)) ?: continue // all zero: the face is the whole group, so nothing vouches for it
            val score = FaceMath.dot(centre, v)
            var rival = -2f
            for (c in others) rival = maxOf(rival, FaceMath.dot(c.v, v))
            out[f.id] = score to (if (rival < -1f) score else score - rival)
        }
        return out
    }

    /** Faces the user will see in a group, unsealed on demand for the screens that need a vector (merge preview). */
    suspend fun vectorOf(faceId: Long): FloatArray? = dao.face(faceId)?.let(::vec)?.v
}
