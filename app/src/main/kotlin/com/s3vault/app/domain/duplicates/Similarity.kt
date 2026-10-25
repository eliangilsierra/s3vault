package com.s3vault.app.domain.duplicates

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Perceptual hash (pHash) of a picture: the 64 lowest-frequency DCT coefficients of a 32x32 greyscale thumbnail, each reduced to
 * one bit by comparing it with the median.
 *
 * What it is good at: the same picture after resizing, recompression, a mild brightness or contrast change, light noise, a
 * format change, a small filter. Bursts of the same scene also land close. What it cannot do: tell apart two different photos of
 * nearly the same flat scene, recognise a crop, a rotation or a heavy edit (it will simply not match), or say anything about
 * content: it is a fingerprint of the layout of light and dark, not a model that understands the photo. Matches are therefore
 * "probable", always shown for the user to decide, and never deleted automatically.
 */
object PerceptualHash {
    const val SIZE = 32
    private const val LOW = 8

    /** Below this standard deviation (0..255) the picture is flat: its hash would match every other flat picture. */
    const val MIN_DETAIL = 3.0

    private val cosines: Array<DoubleArray> = Array(LOW) { k -> DoubleArray(SIZE) { n -> cos((2 * n + 1) * k * PI / (2 * SIZE)) } }

    /** [luma] is SIZE x SIZE values in 0..255, row by row. Null when the picture has too little detail to be fingerprinted. */
    fun compute(luma: FloatArray): Long? {
        require(luma.size == SIZE * SIZE) { "expected ${SIZE * SIZE} samples" }
        var sum = 0.0
        for (v in luma) sum += v
        val mean = sum / luma.size
        var sq = 0.0
        for (v in luma) sq += (v - mean) * (v - mean)
        if (sqrt(sq / luma.size) < MIN_DETAIL) return null

        // Separable DCT: rows first (only the LOW lowest frequencies are needed), then columns.
        val rows = Array(SIZE) { DoubleArray(LOW) }
        for (y in 0 until SIZE) for (u in 0 until LOW) {
            var s = 0.0
            val c = cosines[u]
            for (x in 0 until SIZE) s += luma[y * SIZE + x] * c[x]
            rows[y][u] = s
        }
        val coeff = DoubleArray(LOW * LOW)
        for (v in 0 until LOW) for (u in 0 until LOW) {
            var s = 0.0
            val c = cosines[v]
            for (y in 0 until SIZE) s += rows[y][u] * c[y]
            coeff[v * LOW + u] = s
        }
        val sorted = coeff.drop(1).sorted() // the median ignores the DC term, which only measures overall brightness
        val median = (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        var hash = 0L
        for (i in coeff.indices) if (coeff[i] > median) hash = hash or (1L shl i)
        return hash
    }

    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** Luma of an RGB pixel (ITU-R BT.601), 0..255. */
    fun luma(r: Int, g: Int, b: Int): Float = 0.299f * r + 0.587f * g + 0.114f * b
}

/** What the similarity pass knows about one analysed picture. */
data class Fingerprint(val id: Long, val hash: Long, val width: Int, val height: Int, val takenMs: Long, val sha: String? = null)

/** Pictures judged probably the same; [distances] is each member's Hamming distance to the first one. */
data class SimilarCluster(val ids: List<Long>, val distances: Map<Long, Int>)

/**
 * Groups fingerprints without comparing every pair. Candidates come from two sources: pictures that agree on at least one of the
 * eight bytes of the hash (multi-index hashing: two hashes within a few bits always share a byte), and pictures taken close in time
 * (bursts). Candidates are then checked against the real distance and the aspect ratio, joined, and finally cut into tight groups
 * around a leader, so a long chain of "almost the same" cannot drag unrelated pictures together.
 */
object SimilarityClusterer {
    const val DEFAULT_THRESHOLD = 8
    const val MAX_GROUP = 50

    fun cluster(
        items: List<Fingerprint>,
        threshold: Int = DEFAULT_THRESHOLD,
        aspectTolerance: Double = 0.15,
        timeWindow: Int = 40,
        maxBucket: Int = 1500,
    ): List<SimilarCluster> {
        val n = items.size
        if (n < 2) return emptyList()
        val parent = IntArray(n) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }; return r }
        fun close(i: Int, j: Int): Boolean {
            val a = items[i]; val b = items[j]
            if (PerceptualHash.distance(a.hash, b.hash) > threshold) return false
            return sameAspect(a, b, aspectTolerance)
        }
        fun consider(i: Int, j: Int) { if (close(i, j)) { val ri = find(i); val rj = find(j); if (ri != rj) parent[ri] = rj } }

        // 1. Pictures that share a byte of the hash.
        for (band in 0 until 8) {
            val shift = band * 8
            val counts = IntArray(257)
            for (i in 0 until n) counts[((items[i].hash ushr shift) and 0xFF).toInt() + 1]++
            for (b in 0 until 256) counts[b + 1] += counts[b]
            val order = IntArray(n)
            val fill = counts.copyOf()
            for (i in 0 until n) order[fill[((items[i].hash ushr shift) and 0xFF).toInt()]++] = i
            for (b in 0 until 256) {
                val from = counts[b]; val to = counts[b + 1]
                if (to - from < 2 || to - from > maxBucket) continue // a bucket this crowded says little: bursts are caught by time below
                for (x in from until to) for (y in x + 1 until to) consider(order[x], order[y])
            }
        }
        // 2. Pictures taken close together.
        val byTime = (0 until n).sortedWith(compareBy({ items[it].takenMs }, { items[it].id }))
        for (p in byTime.indices) for (q in p + 1 until minOf(byTime.size, p + 1 + timeWindow)) consider(byTime[p], byTime[q])

        // 3. Tight groups: every member within the threshold of the group's leader.
        val components = HashMap<Int, MutableList<Int>>()
        for (i in 0 until n) components.getOrPut(find(i)) { ArrayList() } += i
        val out = ArrayList<SimilarCluster>()
        for (members in components.values) {
            if (members.size < 2) continue
            val ordered = members.sortedWith(compareBy({ items[it].takenMs }, { items[it].id }))
            val taken = BooleanArray(ordered.size)
            for (a in ordered.indices) {
                if (taken[a]) continue
                val leader = items[ordered[a]]
                val group = arrayListOf(ordered[a])
                val dist = linkedMapOf(leader.id to 0)
                for (b in a + 1 until ordered.size) {
                    if (taken[b] || group.size >= MAX_GROUP) continue
                    val other = items[ordered[b]]
                    val d = PerceptualHash.distance(leader.hash, other.hash)
                    if (d <= threshold && sameAspect(leader, other, aspectTolerance)) { group += ordered[b]; dist[other.id] = d; taken[b] = true }
                }
                if (group.size >= 2) { taken[a] = true; out += SimilarCluster(group.map { items[it].id }, dist) }
            }
        }
        return out.sortedWith(compareByDescending<SimilarCluster> { it.ids.size }.thenBy { it.ids.first() })
    }

    /** Resized copies keep their proportions; two pictures with clearly different ones are not the same picture. Unknown size: no objection. */
    private fun sameAspect(a: Fingerprint, b: Fingerprint, tolerance: Double): Boolean {
        if (a.width <= 0 || a.height <= 0 || b.width <= 0 || b.height <= 0) return true
        return abs(ln(a.width.toDouble() / a.height) - ln(b.width.toDouble() / b.height)) <= tolerance
    }
}

/** A picture of a group as the keeper suggestion sees it. */
data class KeepCandidate(
    val id: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val hasCloudCopy: Boolean,
    val folder: String,
    val dateAddedSec: Long,
    val takenMs: Long,
)

data class KeepSuggestion(val keepId: Long, val reason: String)

/** A suggestion, never an action: the screen shows it as the pre-selected copy to keep and the user can pick another. */
object KeeperSuggestion {
    /** Folders where copies usually end up (messaging apps, downloads) rather than where the camera wrote the original. */
    private val SECONDARY = listOf("whatsapp", "telegram", "download", "descargas", "screenshots", "signal", "messenger")

    private fun isSecondary(folder: String) = SECONDARY.any { folder.lowercase().contains(it) }

    fun exact(members: List<KeepCandidate>): KeepSuggestion {
        require(members.isNotEmpty())
        val best = members.sortedWith(
            compareByDescending<KeepCandidate> { it.hasCloudCopy }.thenBy { isSecondary(it.folder) }
                .thenBy { if (it.dateAddedSec > 0) it.dateAddedSec else Long.MAX_VALUE }.thenBy { it.id },
        ).first()
        val reason = when {
            best.hasCloudCopy && members.any { !it.hasCloudCopy } -> "Es la que ya tiene copia en S3"
            !isSecondary(best.folder) && members.any { isSecondary(it.folder) } -> "Está en ${best.folder.ifBlank { "la carpeta original" }}, no en una carpeta de descargas o mensajería"
            else -> "Es la más antigua"
        }
        return KeepSuggestion(best.id, reason)
    }

    fun similar(members: List<KeepCandidate>): KeepSuggestion {
        require(members.isNotEmpty())
        val best = members.sortedWith(
            compareByDescending<KeepCandidate> { it.width.toLong() * it.height }.thenByDescending { it.sizeBytes }
                .thenByDescending { it.hasCloudCopy }.thenBy { if (it.dateAddedSec > 0) it.dateAddedSec else Long.MAX_VALUE }.thenBy { it.id },
        ).first()
        val others = members.filter { it.id != best.id }
        val reason = when {
            others.any { it.width.toLong() * it.height < best.width.toLong() * best.height } -> "Tiene la mayor resolución"
            others.any { it.sizeBytes < best.sizeBytes } -> "Es la de mayor calidad (archivo más grande)"
            else -> "Es la más antigua"
        }
        return KeepSuggestion(best.id, reason)
    }
}
