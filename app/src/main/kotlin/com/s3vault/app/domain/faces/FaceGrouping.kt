package com.s3vault.app.domain.faces

import com.s3vault.app.domain.semantic.EmbeddingCodec

/** A face vector in memory, with just what the grouping needs to know about where it came from. */
class FaceVec(val id: Long, val photoId: Long, val v: FloatArray, val quality: Float)

/** The mean vector of a person's faces (unit length) and how many faces it was made of. */
class Center(val personId: Long, val v: FloatArray, val count: Int, val hidden: Boolean = false)

sealed interface Placement {
    /** Close enough to one group, and clear of every other: it joins it, as a probable match (never as confirmed). */
    data class Auto(val personId: Long, val score: Float, val margin: Float) : Placement

    /** Could be this person, not sure enough: waits for the user. */
    data class Suggest(val personId: Long, val score: Float, val margin: Float) : Placement

    data object None : Placement
}

/**
 * The numbers of the grouping. They are cosine similarities between SFace vectors; docs/face-grouping.md says how each was
 * chosen (measured on the LFW benchmark) and what that does and does not prove. They are not guarantees of identity.
 */
data class GroupingConfig(
    /** Closest group at or above this, and ahead of the runner-up by [minMargin]: placed automatically. */
    val assign: Float = 0.58f,
    val minMargin: Float = 0.10f,
    /** Between this and [assign] (or too close to a second group): suggested, not placed. */
    val suggest: Float = 0.52f,
    /** A face of quality under this can be suggested but never placed on its own. */
    val minAutoQuality: Float = 0.30f,
    /** Two unplaced faces this close are candidates to be the start of a new group. */
    val link: Float = 0.55f,
    /** A new group's members must stay this close to the group's mean, or they are dropped from it. */
    val cohesion: Float = 0.50f,
    /** A new group needs at least this many faces, from at least [minNewGroupPhotos] different photos. */
    val minNewGroupFaces: Int = 3,
    val minNewGroupPhotos: Int = 3,
    /** Two automatic groups whose centres are this close are merged by the grouping itself; closer than [mergeSuggest], offered to the user. */
    val mergeAuto: Float = 0.70f,
    val mergeSuggest: Float = 0.55f,
)

/** A pair of groups that may be one person. */
class MergeCandidate(val a: Long, val b: Long, val score: Float)

object FaceMath {
    fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /** The normalised mean of [vectors], or null if there are none (or they cancel out). */
    fun mean(vectors: List<FloatArray>): FloatArray? {
        if (vectors.isEmpty()) return null
        val sum = FloatArray(vectors[0].size)
        for (v in vectors) for (i in sum.indices) sum[i] += v[i]
        var n = 0.0
        for (x in sum) n += x * x
        if (n < 1e-12) return null
        return EmbeddingCodec.normalize(sum)
    }
}

class FaceGrouper(val cfg: GroupingConfig = GroupingConfig()) {
    /**
     * Where one face goes among [centers]. [blocked] are the groups it must not join: the ones the user said it is not, and the
     * ones that already have another face of the same photo (two faces in one picture are two people).
     */
    fun place(face: FaceVec, centers: List<Center>, blocked: Set<Long>): Placement {
        var best = -2f; var bestId = -1L; var second = -2f
        for (c in centers) {
            if (c.personId in blocked) continue
            val s = FaceMath.dot(face.v, c.v)
            if (s > best) { second = best; best = s; bestId = c.personId } else if (s > second) second = s
        }
        if (bestId < 0 || best < cfg.suggest) return Placement.None
        val margin = if (second < -1f) best else best - second
        return if (best >= cfg.assign && margin >= cfg.minMargin && face.quality >= cfg.minAutoQuality) {
            Placement.Auto(bestId, best, margin)
        } else {
            Placement.Suggest(bestId, best, margin)
        }
    }

    /**
     * New groups among faces that belong to none. Each [seeds] face (the ones that arrived since the last run) is tried as the
     * start of a group: its neighbours in [pool] that are close enough are gathered (at most one face per photo), then anyone who
     * is not close to the gathered mean is let go and the mean is taken again. Only the neighbours of the seed are considered, never
     * the neighbours of neighbours, so a chain of look-alikes cannot stretch a group across different people.
     * Cost: seeds x pool dot products, not pool x pool.
     */
    fun newGroups(pool: List<FaceVec>, seeds: Collection<FaceVec>): List<List<FaceVec>> {
        val taken = HashSet<Long>()
        val groups = ArrayList<List<FaceVec>>()
        for (seed in seeds.sortedByDescending { it.quality }) {
            if (seed.id in taken) continue
            // Best neighbour per photo, so a burst of one scene cannot count as many photos.
            val bestPerPhoto = HashMap<Long, Pair<FaceVec, Float>>()
            bestPerPhoto[seed.photoId] = seed to 1f
            for (g in pool) {
                if (g.id == seed.id || g.id in taken || g.photoId == seed.photoId) continue
                val s = FaceMath.dot(seed.v, g.v)
                if (s < cfg.link) continue
                val cur = bestPerPhoto[g.photoId]
                if (cur == null || s > cur.second) bestPerPhoto[g.photoId] = g to s
            }
            var members = bestPerPhoto.values.map { it.first }
            if (members.size < cfg.minNewGroupFaces) continue
            // Keep only members that stay close to the mean of the group; repeat until nobody is let go.
            while (true) {
                val m = FaceMath.mean(members.map { it.v }) ?: break
                val kept = members.filter { FaceMath.dot(m, it.v) >= cfg.cohesion }
                if (kept.size == members.size) break
                members = kept
                if (members.size < cfg.minNewGroupFaces) break
            }
            if (members.size < cfg.minNewGroupFaces) continue
            if (members.map { it.photoId }.toSet().size < cfg.minNewGroupPhotos) continue
            groups += members
            for (f in members) taken += f.id
        }
        return groups
    }

    /** Pairs of centres close enough to be one person, closest first, skipping pairs the user said are different. */
    fun mergeCandidates(centers: List<Center>, rejectedPairs: Set<Pair<Long, Long>>, sharePhoto: (Long, Long) -> Boolean): List<MergeCandidate> {
        val out = ArrayList<MergeCandidate>()
        for (i in centers.indices) for (j in i + 1 until centers.size) {
            val a = centers[i]; val b = centers[j]
            val s = FaceMath.dot(a.v, b.v)
            if (s < cfg.mergeSuggest) continue
            val key = minOf(a.personId, b.personId) to maxOf(a.personId, b.personId)
            if (key in rejectedPairs) continue
            if (sharePhoto(a.personId, b.personId)) continue // two people who appear together are not one person
            out += MergeCandidate(a.personId, b.personId, s)
        }
        return out.sortedByDescending { it.score }
    }
}
