package com.s3vault.app.domain.faces

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class FaceGeometryTest {
    private fun transformed(t: SimilarityTransform, pts: FloatArray) = FloatArray(pts.size) { i ->
        val x = pts[i - i % 2]; val y = pts[i - i % 2 + 1]
        if (i % 2 == 0) t.a * x - t.b * y + t.tx else t.b * x + t.a * y + t.ty
    }

    @Test fun `fit recovers the similarity that was applied to the template`() {
        val s = 1.7f; val th = 0.3f
        val truth = SimilarityTransform(s * cos(th), s * sin(th), 123f, -45f)
        // Landmarks as a detector would report them in the picture; the fit must map them back onto the template.
        val inverse = FaceGeometry.fit(FaceModel.TEMPLATE, transformed(truth, FaceModel.TEMPLATE))!!
        val landmarks = transformed(truth, FaceModel.TEMPLATE)
        val back = transformed(FaceGeometry.fit(landmarks)!!, landmarks)
        for (i in back.indices) assertEquals("coordinate $i", FaceModel.TEMPLATE[i], back[i], 0.01f)
        assertNotNull(inverse)
    }

    @Test fun `fit tolerates a few pixels of noise in the landmarks`() {
        val noisy = FloatArray(10) { FaceModel.TEMPLATE[it] * 2f + 300f + (if (it % 3 == 0) 1.5f else -1.2f) }
        val t = FaceGeometry.fit(noisy)!!
        assertEquals(0.5f, kotlin.math.hypot(t.a, t.b), 0.02f) // the scale is still found
    }

    @Test fun `fit rejects landmarks that are all on one point`() {
        assertNull(FaceGeometry.fit(FloatArray(10) { 5f }))
    }

    @Test fun `align puts the landmarks where the template wants them and leaves outside black`() {
        // A picture with a bright pixel under each landmark and everything else dark; after alignment the bright pixels sit on the template.
        val w = 400; val h = 400
        val px = IntArray(w * h) { 0xFF000000.toInt() }
        val lm = FloatArray(10) { FaceModel.TEMPLATE[it] * 2f + 100f }
        for (i in 0 until 5) for (dy in -3..3) for (dx in -3..3) px[(Math.round(lm[2 * i + 1]) + dy) * w + Math.round(lm[2 * i]) + dx] = 0xFFFFFFFF.toInt()
        val out = FaceGeometry.align(px, w, h, FaceGeometry.fit(lm)!!)
        val plane = FaceModel.ALIGNED * FaceModel.ALIGNED
        assertEquals(0f, out[0], 0f) // the corner of the crop is far from every landmark
        for (i in 0 until 5) {
            val x = Math.round(FaceModel.TEMPLATE[2 * i]); val y = Math.round(FaceModel.TEMPLATE[2 * i + 1])
            assertTrue("landmark $i", out[y * FaceModel.ALIGNED + x] > 200f)
        }
        // A face at the very corner of the picture: what the picture does not cover is black, never smeared edge pixels.
        val corner = FaceGeometry.align(IntArray(50 * 50) { 0xFFFFFFFF.toInt() }, 50, 50, SimilarityTransform(1f, 0f, 60f, 60f))
        assertEquals(0f, corner[0], 0f)
        assertEquals(255f, corner[80 * FaceModel.ALIGNED + 80], 0f) // inside the picture (it covers x and y from 60 to 109)
        assertEquals(0f, corner[5 * FaceModel.ALIGNED + 5], 0f)
        assertEquals(255f, corner[plane + 80 * FaceModel.ALIGNED + 80], 0f) // green plane too
    }

    @Test fun `letterbox never enlarges, reduces to fit and reports the scale`() {
        val small = Letterbox.build(IntArray(100 * 80) { 0xFF102030.toInt() }, 100, 80)
        assertEquals(1f, small.scale, 0f)
        val plane = FaceModel.DETECT_SIZE * FaceModel.DETECT_SIZE
        assertEquals(0x30.toFloat(), small.blob[0], 0f)          // blue first (BGR)
        assertEquals(0x20.toFloat(), small.blob[plane], 0f)      // green
        assertEquals(0x10.toFloat(), small.blob[2 * plane], 0f)  // red
        assertEquals(0f, small.blob[99 + FaceModel.DETECT_SIZE * 79 + 1], 0f) // beside the picture: black
        val big = Letterbox.build(IntArray(1280 * 960) { 0xFF808080.toInt() }, 1280, 960)
        assertEquals(0.5f, big.scale, 0f)
        assertEquals(128f, big.blob[10 * FaceModel.DETECT_SIZE + 10], 1f)
        assertEquals(0f, big.blob[500 * FaceModel.DETECT_SIZE + 10], 0f) // below the 640 x 480 picture
    }

    @Test fun `the decoder turns one confident cell into one face in picture pixels, and overlaps collapse`() {
        val size = FaceModel.DETECT_SIZE
        fun cells(stride: Int) = (size / stride) * (size / stride)
        val cls = listOf(8, 16, 32).map { FloatArray(cells(it)) }
        val obj = listOf(8, 16, 32).map { FloatArray(cells(it)) }
        val bbox = listOf(8, 16, 32).map { FloatArray(cells(it) * 4) }
        val kps = listOf(8, 16, 32).map { FloatArray(cells(it) * 10) }
        // Stride 16, cell (row 10, col 20): a face centred at ((20 + 0.5) * 16, (10 + 0.5) * 16) = (328, 168), 64 x 64.
        val cell = 10 * (size / 16) + 20
        cls[1][cell] = 0.9f; obj[1][cell] = 0.9f
        bbox[1][cell * 4] = 0.5f; bbox[1][cell * 4 + 1] = 0.5f
        bbox[1][cell * 4 + 2] = kotlin.math.ln(4f); bbox[1][cell * 4 + 3] = kotlin.math.ln(4f)
        for (n in 0 until 5) { kps[1][cell * 10 + 2 * n] = 0.5f; kps[1][cell * 10 + 2 * n + 1] = 0.5f }
        // A second cell right next to it with a lower score: same face, suppressed.
        val twin = cell + 1
        cls[1][twin] = 0.8f; obj[1][twin] = 0.8f
        bbox[1][twin * 4] = 0.5f; bbox[1][twin * 4 + 1] = 0.5f; bbox[1][twin * 4 + 2] = kotlin.math.ln(4f); bbox[1][twin * 4 + 3] = kotlin.math.ln(4f)
        val faces = YuNetDecoder.decode(cls, obj, bbox, kps, scale = 0.5f, scoreThreshold = 0.6f, nmsThreshold = 0.3f)
        assertEquals(1, faces.size)
        val f = faces[0]
        assertEquals(0.9f, f.score, 1e-4f)
        // Back in picture pixels: the model's canvas was a half-size copy, so everything doubles.
        assertEquals((328f - 32f) * 2, f.x, 0.01f); assertEquals((168f - 32f) * 2, f.y, 0.01f)
        assertEquals(128f, f.w, 0.01f); assertEquals(128f, f.h, 0.01f)
        assertEquals(328f * 2, f.landmarks[0], 0.01f)
        // Under the threshold nothing is a face.
        assertEquals(0, YuNetDecoder.decode(cls, obj, bbox, kps, 0.5f, 0.95f, 0.3f).size)
    }

    @Test fun `quality verdicts follow the thresholds`() {
        val t = FaceThresholds()
        fun q(eye: Float = 40f, yaw: Float = 0.1f, roll: Float = 5f, sharp: Float = 100f, bright: Float = 120f) = FaceQuality(eye, yaw, roll, sharp, bright)
        assertEquals(FaceQuality.Verdict.OK, q().verdict(t))
        assertEquals(FaceQuality.Verdict.TOO_SMALL, q(eye = 12f).verdict(t))
        assertEquals(FaceQuality.Verdict.TURNED, q(yaw = 0.7f).verdict(t))
        assertEquals(FaceQuality.Verdict.TILTED, q(roll = 80f).verdict(t))
        assertEquals(FaceQuality.Verdict.BLURRY, q(sharp = 5f).verdict(t))
        assertEquals(FaceQuality.Verdict.DARK, q(bright = 10f).verdict(t))
        assertEquals(FaceQuality.Verdict.BRIGHT, q(bright = 250f).verdict(t))
        // The score ranks: a large frontal sharp face beats a small one, a turned one and a blurry one.
        assertTrue(q(eye = 60f, yaw = 0f, sharp = 200f).score() > q(eye = 22f).score())
        assertTrue(q(yaw = 0f).score() > q(yaw = 0.5f).score())
        assertTrue(q(sharp = 200f).score() > q(sharp = 30f).score())
        assertTrue(q(eye = 60f, yaw = 0f, sharp = 200f).score() <= 1f)
    }

    @Test fun `measuring a blank crop says it is blurry and a textured one is sharp`() {
        val size = FaceModel.ALIGNED
        val lm = FloatArray(10) { FaceModel.TEMPLATE[it] }
        val blank = FaceQuality.measure(lm, FloatArray(3 * size * size) { 128f })
        assertEquals(0f, blank.sharpness, 0.001f)
        val rnd = java.util.Random(1)
        val noisy = FaceQuality.measure(lm, FloatArray(3 * size * size) { rnd.nextInt(256).toFloat() })
        assertTrue(noisy.sharpness > 1000f)
        assertEquals(35.2f, blank.eyeDistance, 0.1f)
        assertEquals(128f, blank.brightness, 0.01f)
    }
}
