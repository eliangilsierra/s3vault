package com.s3vault.app.domain.faces

import com.s3vault.app.data.faces.FaceModels
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.Random

/**
 * The real detector and recogniser on real photographs (public domain, see src/test/resources/faces/ATTRIBUTION.md), on the JVM.
 * These assert what the pipeline is for: faces are found, the same person looks alike, different people do not, and several
 * people in one photo are told apart. They do not measure accuracy: docs/face-grouping.md does that with a benchmark.
 */
class FaceModelRealTest {
    companion object {
        private lateinit var pipeline: FacePipeline
        private val faces = HashMap<String, PhotoAnalysis>()

        @BeforeClass @JvmStatic fun open() { pipeline = FacePipeline(FaceTestKit.engine()) }
        @AfterClass @JvmStatic fun close() { pipeline.close() }

        fun analysis(name: String): PhotoAnalysis = faces.getOrPut(name) { pipeline.analyze(FaceTestKit.photo(name)) }
        fun vec(name: String): FloatArray { val a = analysis(name); assertEquals("faces in $name", 1, a.faces.size); return a.faces[0].vector }
        val people = mapOf("collins" to (1..3), "ride" to (1..3), "glenn" to (1..3))
    }

    @Test fun `the bundled models are exactly the ones whose hashes are pinned`() {
        assertEquals(FaceModels.DETECTOR_SHA256, FaceModels.sha256(FaceTestKit.assetBytes("face_detection_yunet_2023mar.onnx")))
        assertEquals(FaceModels.RECOGNIZER_SHA256, FaceModels.sha256(FaceTestKit.assetBytes("face_recognition_sface_2021dec_fp16w.onnx")))
    }

    @Test fun `a portrait gives one face with a box inside the picture and a unit vector`() {
        for ((who, range) in people) for (i in range) {
            val a = analysis("${who}_$i")
            assertEquals("$who$i", 1, a.faces.size)
            val f = a.faces[0]
            assertTrue(f.box[0] in 0f..1f && f.box[2] in 0f..1f && f.box[0] < f.box[2] && f.box[1] < f.box[3])
            assertEquals(FaceModel.DIMENSION, f.vector.size)
            assertEquals(1f, FaceMath.dot(f.vector, f.vector), 1e-4f)
            assertTrue("detection score ${f.detScore}", f.detScore >= 0.7f)
            assertTrue("quality ${f.quality}", f.quality in 0f..1f)
        }
    }

    @Test fun `photos of one person look alike and photos of different people do not`() {
        val all = people.flatMap { (who, range) -> range.map { who to vec("${who}_$it") } }
        var worstOtherPerson = -1f
        for (i in all.indices) for (j in i + 1 until all.size) {
            val s = FaceTestKit.cosine(all[i].second, all[j].second)
            if (all[i].first != all[j].first) worstOtherPerson = maxOf(worstOtherPerson, s)
        }
        // Every person has at least one pair of photos clearly above any pair of two different people.
        for ((who, range) in people) {
            val best = range.flatMap { a -> range.filter { it > a }.map { b -> FaceTestKit.cosine(vec("${who}_$a"), vec("${who}_$b")) } }.max()
            assertTrue("$who best same-person pair $best vs worst different-person pair $worstOtherPerson", best > worstOtherPerson + 0.08f)
        }
        assertTrue("different people reach $worstOtherPerson", worstOtherPerson < 0.45f)
    }

    @Test fun `in a group photo each face is its own vector and only the right one matches`() {
        val crew = analysis("crew_sts93")
        // Five people; one has the eyes 19 px apart in this 960 px copy, under the 20 px minimum, so it is counted but not described.
        assertEquals("five people in the STS-93 crew photo", 5, crew.faces.size + crew.skipped)
        assertTrue(crew.faces.size >= 4)
        val refs = (1..3).map { vec("collins_$it") }
        val matches = crew.faces.map { f -> refs.maxOf { FaceTestKit.cosine(f.vector, it) } }
        assertEquals("exactly one face is the person of the portraits: $matches", 1, matches.count { it >= 0.5f })
        // Faces of one photo are never one another.
        for (i in crew.faces.indices) for (j in i + 1 until crew.faces.size) {
            assertTrue(FaceTestKit.cosine(crew.faces[i].vector, crew.faces[j].vector) < 0.5f)
        }
        val press = analysis("press_four")
        assertEquals("four people in the press photo, one too small to compare", 4, press.faces.size + press.skipped)
        assertTrue(press.faces.size >= 3)
        assertEquals(1, press.faces.count { f -> refs.maxOf { FaceTestKit.cosine(f.vector, it) } >= 0.45f })
    }

    @Test fun `a picture with no face yields nothing and does not fail`() {
        val w = 640; val h = 480
        val rnd = Random(3)
        val flat = WorkingImage(IntArray(w * h) { 0xFF808080.toInt() }, w, h)
        val noise = WorkingImage(IntArray(w * h) { (0xFF shl 24) or rnd.nextInt(0xFFFFFF) }, w, h)
        val gradient = WorkingImage(IntArray(w * h) { val x = it % w; (0xFF shl 24) or ((x * 255 / w) shl 16) or ((it / w * 255 / h) shl 8) }, w, h)
        for (img in listOf(flat, noise, gradient)) {
            val a = pipeline.analyze(img)
            assertEquals(0, a.faces.size)
        }
    }

    @Test fun `the same photo twice gives the same vector, and a smaller copy still matches`() {
        val img = FaceTestKit.photo("glenn_2")
        val a = pipeline.analyze(img).faces.single().vector
        val b = pipeline.analyze(img).faces.single().vector
        assertTrue(FaceTestKit.cosine(a, b) > 0.9999f)
        // Half the resolution (what a thumbnail or a re-saved copy looks like): same person, clearly.
        val w2 = img.width / 2; val h2 = img.height / 2
        val small = WorkingImage(IntArray(w2 * h2) { img.argb[(it / w2 * 2) * img.width + (it % w2) * 2] }, w2, h2)
        val c = pipeline.analyze(small).faces.single().vector
        assertTrue("half-size copy ${FaceTestKit.cosine(a, c)}", FaceTestKit.cosine(a, c) > 0.8f)
    }

    @Test fun `a face that is too small is counted and not compared`() {
        val img = FaceTestKit.photo("glenn_2")
        // Shrink the picture until the eyes are a few pixels apart: the detector may still see a face, the pipeline must not describe it.
        val w2 = img.width / 12; val h2 = img.height / 12
        val tiny = WorkingImage(IntArray(w2 * h2) { img.argb[(it / w2 * 12) * img.width + (it % w2) * 12] }, w2, h2)
        val a = pipeline.analyze(tiny)
        assertEquals(0, a.faces.size)
    }

    @Test fun `a working picture larger than the detector canvas is reduced and the box stays in picture fractions`() {
        val img = FaceTestKit.photo("collins_1")
        val big = 2
        val w2 = img.width * big; val h2 = img.height * big
        val up = WorkingImage(IntArray(w2 * h2) { img.argb[(it / w2 / big) * img.width + (it % w2) / big] }, w2, h2)
        val a = pipeline.analyze(up).faces.single()
        val b = pipeline.analyze(img).faces.single()
        assertTrue("box left ${a.box[0]} vs ${b.box[0]}", kotlin.math.abs(a.box[0] - b.box[0]) < 0.03f && kotlin.math.abs(a.box[3] - b.box[3]) < 0.03f)
        assertTrue(FaceTestKit.cosine(a.vector, b.vector) > 0.9f)
    }
}
