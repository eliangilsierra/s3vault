package com.s3vault.app.domain.faces

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** x' = a·x − b·y + tx, y' = b·x + a·y + ty: a rotation, one uniform scale and a shift (no mirror). */
class SimilarityTransform(val a: Float, val b: Float, val tx: Float, val ty: Float)

/**
 * Pure geometry of the face pipeline, with no Android types so it runs (and is tested) on the JVM.
 *  - [fit] finds the similarity transform that best maps the five detected landmarks onto SFace's template (least squares, the
 *    closed form of Umeyama's method for two dimensions).
 *  - [align] resamples the picture through it into the 112 x 112 input SFace expects.
 */
object FaceGeometry {
    /** Null when the landmarks are degenerate (all on one point), which no real face is. */
    fun fit(src: FloatArray, dst: FloatArray = FaceModel.TEMPLATE): SimilarityTransform? {
        val n = src.size / 2
        var msx = 0.0; var msy = 0.0; var mdx = 0.0; var mdy = 0.0
        for (i in 0 until n) { msx += src[2 * i]; msy += src[2 * i + 1]; mdx += dst[2 * i]; mdy += dst[2 * i + 1] }
        msx /= n; msy /= n; mdx /= n; mdy /= n
        var num1 = 0.0; var num2 = 0.0; var den = 0.0
        for (i in 0 until n) {
            val sx = src[2 * i] - msx; val sy = src[2 * i + 1] - msy
            val dx = dst[2 * i] - mdx; val dy = dst[2 * i + 1] - mdy
            num1 += sx * dx + sy * dy
            num2 += sx * dy - sy * dx
            den += sx * sx + sy * sy
        }
        if (den < 1e-6) return null
        val a = num1 / den; val b = num2 / den
        val tx = mdx - (a * msx - b * msy)
        val ty = mdy - (b * msx + a * msy)
        return SimilarityTransform(a.toFloat(), b.toFloat(), tx.toFloat(), ty.toFloat())
    }

    /**
     * Writes the aligned face to [out] as RGB planes (channel, row, column) with values 0..255, which is what SFace reads. Pixels
     * the source does not cover are black, as in the pictures the model was trained on. [argb] is the picture (alpha ignored).
     */
    fun align(argb: IntArray, width: Int, height: Int, t: SimilarityTransform, out: FloatArray = FloatArray(3 * FaceModel.ALIGNED * FaceModel.ALIGNED)): FloatArray {
        val size = FaceModel.ALIGNED
        val plane = size * size
        val det = t.a * t.a + t.b * t.b
        val ia = t.a / det; val ib = t.b / det
        for (oy in 0 until size) for (ox in 0 until size) {
            // Where this output pixel comes from in the picture (inverse of the transform).
            val dx = ox - t.tx; val dy = oy - t.ty
            val sx = ia * dx + ib * dy
            val sy = -ib * dx + ia * dy
            val x0 = floor(sx).toInt(); val y0 = floor(sy).toInt()
            val fx = sx - x0; val fy = sy - y0
            var r = 0f; var g = 0f; var b = 0f
            for (j in 0..1) for (i in 0..1) {
                val px = x0 + i; val py = y0 + j
                if (px < 0 || py < 0 || px >= width || py >= height) continue
                val wgt = (if (i == 0) 1 - fx else fx) * (if (j == 0) 1 - fy else fy)
                val p = argb[py * width + px]
                r += ((p shr 16) and 0xFF) * wgt; g += ((p shr 8) and 0xFF) * wgt; b += (p and 0xFF) * wgt
            }
            val o = oy * size + ox
            out[o] = r; out[plane + o] = g; out[2 * plane + o] = b
        }
        return out
    }
}

/**
 * Builds YuNet's input. The model has a fixed 640 x 640 input, so the picture is reduced (never enlarged) to fit it and placed in
 * the top-left corner of a black canvas; [Letterbox.scale] converts the detections back to the picture's own pixels.
 */
object Letterbox {
    class Result(val blob: FloatArray, val scale: Float)

    /** [argb] is width x height; the result is BGR planes, raw 0..255, 640 x 640. */
    fun build(argb: IntArray, width: Int, height: Int, blob: FloatArray = FloatArray(3 * FaceModel.DETECT_SIZE * FaceModel.DETECT_SIZE)): Result {
        val size = FaceModel.DETECT_SIZE
        val scale = min(1f, min(size.toFloat() / width, size.toFloat() / height))
        val nw = max(1, Math.round(width * scale)); val nh = max(1, Math.round(height * scale))
        java.util.Arrays.fill(blob, 0f)
        val plane = size * size
        if (nw == width && nh == height) {
            for (y in 0 until height) for (x in 0 until width) {
                val p = argb[y * width + x]; val o = y * size + x
                blob[o] = (p and 0xFF).toFloat(); blob[plane + o] = ((p shr 8) and 0xFF).toFloat(); blob[2 * plane + o] = ((p shr 16) and 0xFF).toFloat()
            }
        } else {
            resizeInto(argb, width, height, nw, nh, blob, size)
        }
        return Result(blob, scale)
    }

    /**
     * Anti-aliased bilinear reduction (a triangle filter as wide as the reduction factor), separable: columns first, then rows.
     * Without the widening a 2x or 3x reduction would skip pixels and the detector would see aliasing that is not in the photo.
     */
    private fun resizeInto(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int, blob: FloatArray, canvas: Int) {
        val xs = weights(sw, dw)
        val ys = weights(sh, dh)
        // Horizontal pass: sh rows of dw pixels, three channels (B, G, R) kept apart.
        val tmp = FloatArray(dw * sh * 3)
        for (y in 0 until sh) {
            val row = y * sw
            for (x in 0 until dw) {
                val c = xs[x]
                var b = 0f; var g = 0f; var r = 0f
                for (k in c.start until c.start + c.w.size) {
                    val p = src[row + k]; val w = c.w[k - c.start]
                    b += (p and 0xFF) * w; g += ((p shr 8) and 0xFF) * w; r += ((p shr 16) and 0xFF) * w
                }
                val o = (y * dw + x) * 3
                tmp[o] = b; tmp[o + 1] = g; tmp[o + 2] = r
            }
        }
        val plane = canvas * canvas
        for (y in 0 until dh) {
            val c = ys[y]
            for (x in 0 until dw) {
                var b = 0f; var g = 0f; var r = 0f
                for (k in c.start until c.start + c.w.size) {
                    val o = (k * dw + x) * 3; val w = c.w[k - c.start]
                    b += tmp[o] * w; g += tmp[o + 1] * w; r += tmp[o + 2] * w
                }
                val o = y * canvas + x
                blob[o] = b; blob[plane + o] = g; blob[2 * plane + o] = r
            }
        }
    }

    private class Taps(val start: Int, val w: FloatArray)

    private fun weights(inSize: Int, outSize: Int): Array<Taps> {
        val scale = inSize.toFloat() / outSize
        val filterScale = max(scale, 1f)
        return Array(outSize) { i ->
            val center = (i + 0.5f) * scale
            val lo = max((center - filterScale + 0.5f).toInt(), 0)
            val hi = min((center + filterScale + 0.5f).toInt(), inSize)
            val w = FloatArray(max(hi - lo, 1))
            var sum = 0f
            for (k in w.indices) {
                val v = 1f - abs((lo + k - center + 0.5f) / filterScale)
                w[k] = if (v > 0f) v else 0f
                sum += w[k]
            }
            if (sum <= 0f) { w[0] = 1f; sum = 1f }
            for (k in w.indices) w[k] /= sum
            Taps(lo, w)
        }
    }
}

/** Decodes the twelve tensors YuNet returns into faces, in the picture's own pixel space. */
object YuNetDecoder {
    private val STRIDES = intArrayOf(8, 16, 32)

    /**
     * [cls], [obj], [bbox], [kps] hold the outputs of the three strides (8, 16, 32), each flattened. Faces come back best first,
     * after suppressing overlaps; [scale] maps the model's canvas back to the picture (see [Letterbox]).
     */
    fun decode(
        cls: List<FloatArray>, obj: List<FloatArray>, bbox: List<FloatArray>, kps: List<FloatArray>,
        scale: Float, scoreThreshold: Float, nmsThreshold: Float, canvas: Int = FaceModel.DETECT_SIZE, maxFaces: Int = 64,
    ): List<RawFace> {
        val found = ArrayList<RawFace>()
        for ((k, stride) in STRIDES.withIndex()) {
            val cols = canvas / stride
            val count = cls[k].size
            for (i in 0 until count) {
                val c = cls[k][i].coerceIn(0f, 1f); val o = obj[k][i].coerceIn(0f, 1f)
                val score = kotlin.math.sqrt(c * o)
                if (score < scoreThreshold) continue
                val row = i / cols; val col = i % cols
                val b = i * 4; val p = i * 10
                val cx = (col + bbox[k][b]) * stride; val cy = (row + bbox[k][b + 1]) * stride
                val w = kotlin.math.exp(bbox[k][b + 2]) * stride; val h = kotlin.math.exp(bbox[k][b + 3]) * stride
                val lm = FloatArray(10)
                for (n in 0 until 5) {
                    lm[2 * n] = (kps[k][p + 2 * n] + col) * stride / scale
                    lm[2 * n + 1] = (kps[k][p + 2 * n + 1] + row) * stride / scale
                }
                found += RawFace(score, (cx - w / 2) / scale, (cy - h / 2) / scale, w / scale, h / scale, lm)
            }
        }
        found.sortByDescending { it.score }
        val kept = ArrayList<RawFace>()
        for (f in found) {
            if (kept.size >= maxFaces) break
            if (kept.none { iou(it, f) > nmsThreshold }) kept += f
        }
        return kept
    }

    fun iou(a: RawFace, b: RawFace): Float {
        val ix = max(0f, min(a.x + a.w, b.x + b.w) - max(a.x, b.x))
        val iy = max(0f, min(a.y + a.h, b.y + b.h) - max(a.y, b.y))
        val inter = ix * iy
        return inter / (a.w * a.h + b.w * b.h - inter + 1e-9f)
    }
}

/** What was measured on one aligned face, and the verdict. */
class FaceQuality(
    val eyeDistance: Float, val yaw: Float, val roll: Float, val sharpness: Float, val brightness: Float,
) {
    enum class Verdict { OK, TOO_SMALL, TURNED, TILTED, BLURRY, DARK, BRIGHT }

    fun verdict(t: FaceThresholds): Verdict = when {
        eyeDistance < t.minEyeDistance -> Verdict.TOO_SMALL
        yaw > t.maxYaw -> Verdict.TURNED
        roll > t.maxRoll -> Verdict.TILTED
        brightness < t.minBrightness -> Verdict.DARK
        brightness > t.maxBrightness -> Verdict.BRIGHT
        sharpness < t.minSharpness -> Verdict.BLURRY
        else -> Verdict.OK
    }

    /**
     * 0..1, how much to trust a vector made from this face (used to order and to weigh, never to hide a decision the user made).
     * A frontal, sharp, large face is near 1; each defect lowers it.
     */
    fun score(): Float {
        val size = ((eyeDistance - 16f) / 32f).coerceIn(0f, 1f)
        val pose = (1f - yaw / 0.8f).coerceIn(0f, 1f)
        val sharp = (sharpness / 120f).coerceIn(0f, 1f)
        // The weakest point counts most: a large face seen from the side is no better than a small one seen from the front.
        return (0.6f * minOf(size, pose, sharp) + 0.4f * (size + pose + sharp) / 3f).coerceIn(0f, 1f)
    }

    companion object {
        /** [landmarks] in the picture's pixels (x0,y0..x4,y4); [aligned] is what [FaceGeometry.align] produced. */
        fun measure(landmarks: FloatArray, aligned: FloatArray): FaceQuality {
            val eye = hypot(landmarks[2] - landmarks[0], landmarks[3] - landmarks[1])
            val midX = (landmarks[0] + landmarks[2]) / 2
            val yaw = if (eye > 1e-3f) abs(landmarks[4] - midX) / eye else 9f
            val roll = abs(Math.toDegrees(atan2((landmarks[3] - landmarks[1]).toDouble(), (landmarks[2] - landmarks[0]).toDouble()))).toFloat()
            val size = FaceModel.ALIGNED
            val plane = size * size
            val gray = FloatArray(plane) { 0.299f * aligned[it] + 0.587f * aligned[plane + it] + 0.114f * aligned[2 * plane + it] }
            var sum = 0.0
            for (v in gray) sum += v
            // Variance of the 4-neighbour Laplacian over the inside of the crop.
            var n = 0; var s = 0.0; var s2 = 0.0
            for (y in 1 until size - 1) for (x in 1 until size - 1) {
                val i = y * size + x
                val l = gray[i - 1] + gray[i + 1] + gray[i - size] + gray[i + size] - 4 * gray[i]
                s += l; s2 += l.toDouble() * l; n++
            }
            val variance = (s2 / n - (s / n) * (s / n)).toFloat()
            return FaceQuality(eye, yaw, roll, variance, (sum / plane).toFloat())
        }
    }
}
