package com.s3vault.app.domain.duplicates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos

/** Synthetic pictures: smooth random scenes that can be "resized", brightened or noised like real copies of a photo. */
object Synth {
    private class Wave(val fx: Double, val fy: Double, val phase: Double, val amp: Double)

    private fun waves(seed: Int): List<Wave> {
        val r = Random(seed.toLong() * 7919 + 17)
        return List(7) { Wave(r.nextDouble() * 3 + 0.3, r.nextDouble() * 3 + 0.3, r.nextDouble() * 2 * PI, 0.4 + r.nextDouble()) }
    }

    /** A scene sampled at [resolution]² and averaged down to 32x32, which is what a resized copy looks like to the hash. */
    fun scene(seed: Int, resolution: Int = 32, brightness: Float = 0f, contrast: Float = 1f, noise: Float = 0f, noiseSeed: Int = 1): FloatArray {
        val w = waves(seed)
        val f = DoubleArray(resolution * resolution) { i ->
            val x = (i % resolution + 0.5) / resolution
            val y = (i / resolution + 0.5) / resolution
            w.sumOf { it.amp * cos(2 * PI * (it.fx * x + it.fy * y) + it.phase) }
        }
        val k = resolution / PerceptualHash.SIZE
        val rnd = Random(noiseSeed.toLong())
        return FloatArray(PerceptualHash.SIZE * PerceptualHash.SIZE) { i ->
            val bx = i % PerceptualHash.SIZE; val by = i / PerceptualHash.SIZE
            var s = 0.0
            for (dy in 0 until k) for (dx in 0 until k) s += f[(by * k + dy) * resolution + bx * k + dx]
            val base = 128.0 + 35.0 * s / (k * k)
            ((base - 128) * contrast + 128 + brightness + rnd.nextGaussian() * noise).coerceIn(0.0, 255.0).toFloat()
        }
    }

    fun flat(value: Float = 100f) = FloatArray(PerceptualHash.SIZE * PerceptualHash.SIZE) { value }
}

class PerceptualHashTest {
    private fun h(luma: FloatArray) = PerceptualHash.compute(luma)!!

    @Test fun `the same picture always gives the same hash`() {
        assertEquals(h(Synth.scene(1)), h(Synth.scene(1)))
    }

    @Test fun `a resized copy lands very close`() {
        val d = PerceptualHash.distance(h(Synth.scene(3, resolution = 32)), h(Synth.scene(3, resolution = 256)))
        assertTrue("resize distance $d", d <= 6)
    }

    @Test fun `brightness, contrast and light noise barely move the hash`() {
        val base = h(Synth.scene(5))
        assertTrue(PerceptualHash.distance(base, h(Synth.scene(5, brightness = 25f))) <= 6)
        assertTrue(PerceptualHash.distance(base, h(Synth.scene(5, contrast = 1.25f))) <= 6)
        assertTrue(PerceptualHash.distance(base, h(Synth.scene(5, noise = 4f))) <= 8)
    }

    @Test fun `different pictures are far apart on average`() {
        val hashes = (1..40).map { h(Synth.scene(it * 13)) }
        var total = 0; var pairs = 0; var close = 0
        for (i in hashes.indices) for (j in i + 1 until hashes.size) {
            val d = PerceptualHash.distance(hashes[i], hashes[j]); total += d; pairs++
            if (d <= 8) close++
        }
        assertTrue("mean ${total.toDouble() / pairs}", total.toDouble() / pairs > 20)
        assertTrue("$close of $pairs unrelated pairs fall under the threshold", close.toDouble() / pairs < 0.02)
    }

    @Test fun `a flat picture has no hash instead of one that matches every other flat picture`() {
        assertNull(PerceptualHash.compute(Synth.flat(0f)))
        assertNull(PerceptualHash.compute(Synth.flat(255f)))
        assertNotNull(PerceptualHash.compute(Synth.scene(2)))
    }

    @Test fun `the input must be the agreed size`() {
        val failed = runCatching { PerceptualHash.compute(FloatArray(10)) }.isFailure
        assertTrue(failed)
    }
}

class SimilarityClustererTest {
    private fun fp(id: Long, seed: Int, w: Int = 4000, h: Int = 3000, taken: Long = id * 1_000_000, resolution: Int = 32, sha: String? = null, brightness: Float = 0f) =
        Fingerprint(id, PerceptualHash.compute(Synth.scene(seed, resolution, brightness))!!, w, h, taken, sha)

    @Test fun `copies of one picture form a group and unrelated pictures stay out`() {
        val items = listOf(fp(1, 10), fp(2, 10, resolution = 128, w = 1600, h = 1200), fp(3, 10, brightness = 20f), fp(4, 99), fp(5, 123))
        val groups = SimilarityClusterer.cluster(items)
        assertEquals(1, groups.size)
        assertEquals(setOf(1L, 2L, 3L), groups.single().ids.toSet())
    }

    @Test fun `a different aspect ratio is not the same picture`() {
        val groups = SimilarityClusterer.cluster(listOf(fp(1, 10, w = 4000, h = 3000), fp(2, 10, w = 3000, h = 4000)))
        assertTrue("a portrait and a landscape with the same fingerprint are not offered as copies", groups.isEmpty())
    }

    @Test fun `unknown dimensions are not held against a match`() {
        assertEquals(1, SimilarityClusterer.cluster(listOf(fp(1, 10, w = 0, h = 0), fp(2, 10))).size)
    }

    @Test fun `a chain of almost-the-same cannot join distant pictures`() {
        // Hashes that differ by 6 bits from their neighbour: the ends are 24 bits apart.
        fun withBits(id: Long, flips: List<Int>) = Fingerprint(id, flips.fold(0L) { acc, b -> acc xor (1L shl b) }, 100, 100, id * 10)
        val items = listOf(
            withBits(1, emptyList()), withBits(2, listOf(0, 1, 2, 3, 4, 5)),
            withBits(3, listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)), withBits(4, listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17)),
        )
        val groups = SimilarityClusterer.cluster(items, threshold = 8)
        assertTrue("no group may contain two members more than 8 bits apart", groups.all { g ->
            val hashes = g.ids.map { id -> items.first { it.id == id }.hash }
            hashes.all { a -> hashes.all { b -> PerceptualHash.distance(a, b) <= 16 } } && g.ids.size <= 2
        })
        assertFalse("the two ends are never together", groups.any { 1L in it.ids && 4L in it.ids })
    }

    @Test fun `distances to the first member are reported`() {
        val g = SimilarityClusterer.cluster(listOf(fp(1, 10), fp(2, 10, brightness = 30f))).single()
        assertEquals(0, g.distances[g.ids.first()])
        assertTrue(g.distances.getValue(g.ids[1]) in 0..8)
    }

    @Test fun `bursts taken seconds apart are compared even when their hashes share no byte`() {
        // Same scene, hashes forced to differ in every byte but only by 8 bits in total.
        val a = Fingerprint(1, 0L, 100, 100, 1_000)
        val flips = (0 until 8).fold(0L) { acc, byte -> acc or (1L shl (byte * 8)) }
        val b = Fingerprint(2, flips, 100, 100, 2_000)
        assertEquals(1, SimilarityClusterer.cluster(listOf(a, b), threshold = 8).size)
    }

    @Test fun `a large library is processed in a few seconds and finds the planted copies`() {
        val rnd = Random(42)
        val noise = (0 until 30_000).map { Fingerprint(it.toLong() + 1000, rnd.nextLong(), 4000, 3000, rnd.nextInt(1_000_000).toLong() * 1000) }
        val planted = (0 until 50).flatMap { k ->
            val base = rnd.nextLong()
            listOf(Fingerprint(k * 2L, base, 4000, 3000, rnd.nextInt(1_000_000).toLong() * 1000), Fingerprint(k * 2L + 1, base xor (1L shl rnd.nextInt(64)), 4000, 3000, rnd.nextInt(1_000_000).toLong() * 1000))
        }
        val t0 = System.nanoTime()
        val groups = SimilarityClusterer.cluster(noise + planted)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val found = groups.count { g -> g.ids.size == 2 && g.ids.all { it < 100 } }
        assertTrue("found $found of 50 planted pairs in $ms ms", found >= 49)
        assertTrue("30 000 pictures took $ms ms", ms < 8_000)
        assertTrue("random hashes should rarely group: ${groups.size} groups", groups.size < 120)
    }

    @Test fun `fewer than two pictures give nothing`() {
        assertTrue(SimilarityClusterer.cluster(emptyList()).isEmpty())
        assertTrue(SimilarityClusterer.cluster(listOf(fp(1, 1))).isEmpty())
    }
}

class KeeperSuggestionTest {
    private fun c(id: Long, w: Int = 4000, h: Int = 3000, size: Long = 1000, cloud: Boolean = false, folder: String = "Camera", added: Long = id) =
        KeepCandidate(id, w, h, size, cloud, folder, added, added * 1000)

    @Test fun `identical copies prefer the backed-up one, then the one outside a messaging folder, then the oldest`() {
        assertEquals(2L, KeeperSuggestion.exact(listOf(c(1), c(2, cloud = true))).keepId)
        assertEquals(2L, KeeperSuggestion.exact(listOf(c(1, folder = "WhatsApp Images"), c(2))).keepId)
        assertEquals(1L, KeeperSuggestion.exact(listOf(c(2), c(1))).keepId)
        assertTrue(KeeperSuggestion.exact(listOf(c(1), c(2, cloud = true))).reason.contains("S3"))
    }

    @Test fun `similar pictures keep the sharper one`() {
        val s = KeeperSuggestion.similar(listOf(c(1, 1600, 1200, 300), c(2, 4000, 3000, 4000), c(3, 4000, 3000, 2500)))
        assertEquals(2L, s.keepId)
        assertEquals("Tiene la mayor resolución", s.reason)
        assertEquals("same pixels: the bigger file", 3L, KeeperSuggestion.similar(listOf(c(3, 4000, 3000, 2500), c(4, 4000, 3000, 2000))).keepId)
    }
}
