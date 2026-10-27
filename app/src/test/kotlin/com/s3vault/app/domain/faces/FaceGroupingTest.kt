package com.s3vault.app.domain.faces

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** The grouping decisions on synthetic vectors, where who is who is known and the similarities are under control. */
class FaceGroupingTest {
    private val rnd = Random(11)
    private val grouper = FaceGrouper()
    private var nextId = 1L

    /** A person is a direction; their faces are that direction plus noise (cosine to the centre of about 0.8 to 0.9). */
    private fun person() = FaceTestKit.randomUnit(rnd)
    private fun face(dir: FloatArray, photo: Long, noise: Float = 0.5f, quality: Float = 0.8f) = FaceVec(nextId++, photo, FaceTestKit.near(dir, noise, rnd), quality)
    private fun centre(vararg faces: FaceVec) = FaceMath.mean(faces.map { it.v })!!

    @Test fun `a face very close to one group and far from the others joins it`() {
        val a = person(); val b = person()
        val ca = Center(1, centre(face(a, 1), face(a, 2), face(a, 3)), 3)
        val cb = Center(2, centre(face(b, 4), face(b, 5), face(b, 6)), 3)
        val p = grouper.place(face(a, 10, noise = 0.3f), listOf(ca, cb), emptySet())
        assertTrue(p is Placement.Auto && p.personId == 1L)
        assertTrue((p as Placement.Auto).margin >= grouper.cfg.minMargin)
    }

    @Test fun `a stranger is placed nowhere`() {
        val ca = Center(1, person(), 3)
        assertEquals(Placement.None, grouper.place(face(person(), 10), listOf(ca), emptySet()))
    }

    @Test fun `a face between two groups is not placed, it is suggested or left alone`() {
        val a = person()
        // A group mean and a second one that is almost the same direction: the lead is smaller than the margin.
        val c1 = Center(1, FaceTestKit.unit(FloatArray(FaceModel.DIMENSION) { a[it] }), 3)
        val c2 = Center(2, FaceTestKit.near(a, 0.35f, rnd), 3)
        val p = grouper.place(FaceVec(1, 1, a, 0.9f), listOf(c1, c2), emptySet())
        assertTrue("two nearly equal groups must not give an automatic placement: $p", p !is Placement.Auto)
    }

    @Test fun `a blurry or small face is at most suggested`() {
        val a = person()
        val c = Center(1, a, 5)
        val p = grouper.place(FaceVec(1, 1, a, quality = 0.1f), listOf(c), emptySet())
        assertTrue(p is Placement.Suggest)
    }

    @Test fun `groups the face was rejected from or that already hold a face of the same photo are never candidates`() {
        val a = person()
        val c = Center(1, a, 5)
        assertEquals(Placement.None, grouper.place(FaceVec(1, 1, a, 0.9f), listOf(c), blocked = setOf(1L)))
        assertTrue(grouper.place(FaceVec(1, 1, a, 0.9f), listOf(c), blocked = emptySet()) is Placement.Auto)
    }

    @Test fun `three photos of one person form a new group`() {
        val a = person()
        val pool = (1..4).map { face(a, it.toLong(), noise = 0.45f) }
        val groups = grouper.newGroups(pool, pool)
        assertEquals(1, groups.size)
        assertTrue(groups[0].size >= 3)
    }

    @Test fun `two faces are not enough, and neither are three faces from two photos`() {
        val a = person()
        val two = listOf(face(a, 1), face(a, 2))
        assertTrue(grouper.newGroups(two, two).isEmpty())
        val sameTwoPhotos = listOf(face(a, 1), face(a, 2), face(a, 2))
        assertTrue(grouper.newGroups(sameTwoPhotos, sameTwoPhotos).isEmpty())
    }

    @Test fun `many similar faces of one photo count once`() {
        val a = person()
        val burst = (1..6).map { face(a, 1, noise = 0.2f) } + listOf(face(a, 2), face(a, 3))
        val groups = grouper.newGroups(burst, burst)
        assertEquals(1, groups.size)
        assertEquals("one face per photo", groups[0].map { it.photoId }.toSet().size, groups[0].size)
    }

    @Test fun `different people do not end up together`() {
        val dirs = (1..6).map { person() }
        var photo = 0L
        val pool = dirs.flatMap { d -> (1..4).map { face(d, ++photo, noise = 0.45f) } }
        val groups = grouper.newGroups(pool, pool)
        assertTrue("one group per person at most", groups.size <= 6)
        for (g in groups) {
            val owners = g.map { f -> dirs.indices.maxByOrNull { FaceMath.dot(f.v, dirs[it]) }!! }.toSet()
            assertEquals("a group mixes people: $owners", 1, owners.size)
        }
    }

    @Test fun `a chain of look-alikes does not stretch a group across people`() {
        // A -> B -> C where each neighbour is close to the next but A and C are not: the seed only looks at its own neighbours.
        val a = person()
        val b = FaceTestKit.near(a, 1.2f, rnd) // cosine to a about 0.64
        val c = FaceTestKit.near(b, 1.2f, rnd) // about 0.64 to b, about 0.41 to a
        val pool = listOf(face(a, 1, 0.3f), face(a, 2, 0.3f), face(a, 3, 0.3f), face(b, 4, 0.3f), face(b, 5, 0.3f), face(c, 6, 0.3f), face(c, 7, 0.3f), face(c, 8, 0.3f))
        for (g in grouper.newGroups(pool, pool)) {
            val ids = g.map { it.id }.toSet()
            assertTrue("A and C together", !(ids.containsAll(listOf(1L, 2L, 3L)) && ids.containsAll(listOf(6L, 7L, 8L))))
        }
    }

    @Test fun `groups the user keeps apart, or that appear together, are not offered for merging`() {
        val a = person()
        val c1 = Center(1, a, 5); val c2 = Center(2, FaceTestKit.near(a, 0.2f, rnd), 5)
        assertEquals(1, grouper.mergeCandidates(listOf(c1, c2), emptySet()) { _, _ -> false }.size)
        assertTrue(grouper.mergeCandidates(listOf(c1, c2), setOf(1L to 2L)) { _, _ -> false }.isEmpty())
        assertTrue(grouper.mergeCandidates(listOf(c1, c2), emptySet()) { _, _ -> true }.isEmpty())
        assertTrue(grouper.mergeCandidates(listOf(c1, Center(3, person(), 2)), emptySet()) { _, _ -> false }.isEmpty())
    }

    @Test fun `the mean of nothing is nothing`() {
        assertNull(FaceMath.mean(emptyList()))
        assertNull(FaceMath.mean(listOf(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f))))
    }

    @Test fun `with many people the placement stays precise`() {
        // 40 people, 12 photos each: group the first 8 photos of each, then place the other 4. Nothing may land in a wrong group.
        val dirs = (1..40).map { person() }
        val centers = dirs.mapIndexed { i, d -> Center(i + 1L, centre(*(1..8).map { face(d, i * 100L + it, noise = 0.55f) }.toTypedArray()), 8) }
        var auto = 0; var wrong = 0
        for ((i, d) in dirs.withIndex()) for (k in 9..12) {
            val p = grouper.place(face(d, i * 100L + k, noise = 0.55f), centers, emptySet())
            if (p is Placement.Auto) { auto++; if (p.personId != i + 1L) wrong++ }
        }
        assertEquals("a face went to the wrong person", 0, wrong)
        assertTrue("most faces should be placed ($auto of 160)", auto > 100)
    }
}
