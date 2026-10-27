package com.s3vault.app.data.faces

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.s3vault.app.domain.faces.FaceEngine
import com.s3vault.app.domain.faces.FaceModel
import com.s3vault.app.domain.faces.Letterbox
import com.s3vault.app.domain.faces.RawFace
import com.s3vault.app.domain.faces.YuNetDecoder
import com.s3vault.app.domain.semantic.EmbeddingCodec
import java.nio.FloatBuffer

/**
 * YuNet (detector) and SFace (recogniser) through ONNX Runtime, on the CPU, in this process: no network, no Google Play services.
 * The models are given as bytes so the same class runs on the phone (read from assets) and in JVM tests (read from the
 * asset folder). Sessions are safe to call from several threads; each call builds its own tensors.
 */
class OrtFaceEngine private constructor(
    detector: Any, recognizer: Any, threadsPerSession: Int,
) : FaceEngine {
    constructor(detector: ByteArray, recognizer: ByteArray, threadsPerSession: Int = 1) : this(detector as Any, recognizer as Any, threadsPerSession)

    /** Loads the models from files: the runtime reads them itself and no copy of the 19 MB lives on the Java heap. */
    constructor(detector: java.io.File, recognizer: java.io.File, threadsPerSession: Int = 1) : this(detector.path as Any, recognizer.path as Any, threadsPerSession)

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val detect: OrtSession
    private val recognise: OrtSession
    private val blobs = ThreadLocal.withInitial { FloatArray(3 * FaceModel.DETECT_SIZE * FaceModel.DETECT_SIZE) }

    init {
        fun options() = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threadsPerSession)
            setInterOpNumThreads(1)
            setMemoryPatternOptimization(false) // lower peak memory; the first call is not noticeably slower
        }
        fun open(model: Any): OrtSession = if (model is ByteArray) env.createSession(model, options()) else env.createSession(model as String, options())
        detect = open(detector)
        recognise = try { open(recognizer) } catch (e: Exception) { detect.close(); throw e }
    }

    override fun detect(argb: IntArray, width: Int, height: Int, minScore: Float, nms: Float): List<RawFace> {
        val boxed = Letterbox.build(argb, width, height, blobs.get()!!)
        val size = FaceModel.DETECT_SIZE.toLong()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(boxed.blob), longArrayOf(1, 3, size, size)).use { input ->
            detect.run(mapOf("input" to input)).use { out ->
                fun read(name: String): FloatArray {
                    val b = (out.get(name).get() as OnnxTensor).floatBuffer
                    return FloatArray(b.remaining()).also { b.get(it) }
                }
                val strides = listOf(8, 16, 32)
                return YuNetDecoder.decode(
                    cls = strides.map { read("cls_$it") }, obj = strides.map { read("obj_$it") },
                    bbox = strides.map { read("bbox_$it") }, kps = strides.map { read("kps_$it") },
                    scale = boxed.scale, scoreThreshold = minScore, nmsThreshold = nms,
                )
            }
        }
    }

    override fun embed(aligned: FloatArray): FloatArray {
        val size = FaceModel.ALIGNED.toLong()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(aligned), longArrayOf(1, 3, size, size)).use { input ->
            recognise.run(mapOf("data" to input)).use { out ->
                val b = (out.get("fc1").get() as OnnxTensor).floatBuffer
                return EmbeddingCodec.normalize(FloatArray(b.remaining()).also { b.get(it) })
            }
        }
    }

    override fun close() {
        detect.close(); recognise.close()
    }
}
