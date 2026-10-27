package com.s3vault.app.domain.faces

import com.s3vault.app.data.faces.OrtFaceEngine
import java.io.File
import java.util.Random

/** What the face tests share: the real models (read from the app's assets), the public-domain photographs and synthetic vectors. */
object FaceTestKit {
    private fun assetDir(): File = listOf("src/main/assets/faces", "app/src/main/assets/faces").map(::File).first { it.isDirectory }
    private fun photoDir(): File = listOf("src/test/resources/faces", "app/src/test/resources/faces").map(::File).first { it.isDirectory }

    fun engine(threads: Int = 1): OrtFaceEngine = OrtFaceEngine(
        File(assetDir(), "face_detection_yunet_2023mar.onnx").readBytes(),
        File(assetDir(), "face_recognition_sface_2021dec_fp16w.onnx").readBytes(), threads,
    )

    fun assetBytes(name: String): ByteArray = File(assetDir(), name).readBytes()

    /**
     * Decodes a JPEG with the JDK's own decoder. The unit-test compile classpath is Android's, which has no javax.imageio, so it is
     * reached by reflection; the JVM that runs the tests has it.
     */
    fun photo(name: String): WorkingImage {
        val imageIo = Class.forName("javax.imageio.ImageIO")
        val img = imageIo.getMethod("read", File::class.java).invoke(null, File(photoDir(), "$name.jpg")) ?: error("cannot read $name")
        val cls = Class.forName("java.awt.image.BufferedImage")
        val w = cls.getMethod("getWidth").invoke(img) as Int
        val h = cls.getMethod("getHeight").invoke(img) as Int
        val px = IntArray(w * h)
        cls.getMethod("getRGB", Int::class.java, Int::class.java, Int::class.java, Int::class.java, IntArray::class.java, Int::class.java, Int::class.java)
            .invoke(img, 0, 0, w, h, px, 0, w)
        return WorkingImage(px, w, h)
    }

    /** A unit vector near [center]: [noise] controls how far, deterministic for a seed. */
    fun near(center: FloatArray, noise: Float, rnd: Random): FloatArray {
        val v = FloatArray(center.size) { center[it] + noise * rnd.nextGaussian().toFloat() / kotlin.math.sqrt(center.size.toFloat()) }
        return unit(v)
    }

    /** A unit vector whose cosine to [dir] is exactly [cosine]: the similarity of a test face is chosen, not hoped for. */
    fun at(dir: FloatArray, cosine: Float, rnd: Random): FloatArray {
        val w = randomUnit(rnd, dir.size)
        val d = FaceMath.dot(w, dir)
        val ortho = unit(FloatArray(dir.size) { w[it] - d * dir[it] })
        val sin = kotlin.math.sqrt(1f - cosine * cosine)
        return unit(FloatArray(dir.size) { cosine * dir[it] + sin * ortho[it] })
    }

    fun randomUnit(rnd: Random, dim: Int = FaceModel.DIMENSION): FloatArray = unit(FloatArray(dim) { rnd.nextGaussian().toFloat() })

    fun unit(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x * x
        val s = kotlin.math.sqrt(n).toFloat()
        return FloatArray(v.size) { v[it] / s }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float = FaceMath.dot(a, b)
}
