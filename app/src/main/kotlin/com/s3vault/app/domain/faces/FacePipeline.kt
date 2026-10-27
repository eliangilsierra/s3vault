package com.s3vault.app.domain.faces

/** The two neural networks behind the pipeline. The implementation (ONNX Runtime) is in the data layer; tests can fake it. */
interface FaceEngine : AutoCloseable {
    /** Faces of a picture ([argb], [width] x [height]), best first, in the picture's own pixels. */
    fun detect(argb: IntArray, width: Int, height: Int, minScore: Float, nms: Float): List<RawFace>

    /** Unit-length vector of [FaceModel.DIMENSION] numbers for a face aligned by [FaceGeometry.align]. */
    fun embed(aligned: FloatArray): FloatArray
}

/** A picture decoded for analysis: [argb] is width x height, upright (EXIF orientation already applied). */
class WorkingImage(val argb: IntArray, val width: Int, val height: Int)

/** A face good enough to be compared with others. [box] is in fractions of the picture (left, top, right, bottom). */
class AnalyzedFace(val box: FloatArray, val detScore: Float, val quality: Float, val vector: FloatArray)

class PhotoAnalysis(val faces: List<AnalyzedFace>, val skipped: Int)

/** What the index asks of the pipeline: picture in, comparable faces out. The real implementation is [FacePipeline]; tests use scripted ones. */
interface FaceAnalyzer : AutoCloseable {
    fun analyze(image: WorkingImage): PhotoAnalysis
}

/**
 * Picture in, faces out: detect, align each face to the template, measure it, and describe only the ones that are fit to be
 * compared. A face the numbers say is too small, blurry, dark or turned is counted and dropped: comparing it would produce a
 * vector that looks like many people, which is how a grouping gets polluted.
 */
class FacePipeline(private val engine: FaceEngine, private val thresholds: FaceThresholds = FaceThresholds()) : FaceAnalyzer {
    override fun analyze(image: WorkingImage): PhotoAnalysis = analyze(image, MAX_FACES_PER_PHOTO)

    fun analyze(image: WorkingImage, maxFaces: Int): PhotoAnalysis {
        val raw = engine.detect(image.argb, image.width, image.height, thresholds.detection, thresholds.nms)
        if (raw.isEmpty()) return PhotoAnalysis(emptyList(), 0)
        val out = ArrayList<AnalyzedFace>()
        var skipped = 0
        val aligned = FloatArray(3 * FaceModel.ALIGNED * FaceModel.ALIGNED)
        // Largest faces first: in a crowd the ones that matter are the near ones, and the cap bounds the work.
        for (f in raw.sortedByDescending { it.w * it.h }) {
            if (out.size >= maxFaces) { skipped++; continue }
            val t = FaceGeometry.fit(f.landmarks)
            if (t == null) { skipped++; continue }
            FaceGeometry.align(image.argb, image.width, image.height, t, aligned)
            val q = FaceQuality.measure(f.landmarks, aligned)
            if (q.verdict(thresholds) != FaceQuality.Verdict.OK) { skipped++; continue }
            val v = engine.embed(aligned)
            out += AnalyzedFace(boxOf(f, image), f.score, q.score(), v)
        }
        return PhotoAnalysis(out, skipped)
    }

    override fun close() = engine.close()

    private fun boxOf(f: RawFace, image: WorkingImage): FloatArray {
        val l = (f.x / image.width).coerceIn(0f, 1f); val t = (f.y / image.height).coerceIn(0f, 1f)
        val r = ((f.x + f.w) / image.width).coerceIn(0f, 1f); val b = ((f.y + f.h) / image.height).coerceIn(0f, 1f)
        return floatArrayOf(l, t, r, b)
    }

    companion object { const val MAX_FACES_PER_PHOTO = 24 }
}
