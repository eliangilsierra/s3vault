package com.s3vault.app.data.local

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.s3vault.app.domain.semantic.ClipTokenizer
import com.s3vault.app.domain.semantic.EmbedderProvider
import com.s3vault.app.domain.semantic.EmbeddingCodec
import com.s3vault.app.domain.semantic.ModelPack
import com.s3vault.app.domain.semantic.ModelPackStore
import com.s3vault.app.domain.semantic.PackStatus
import com.s3vault.app.domain.semantic.TextImageEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * CLIP ViT-B/32 through ONNX Runtime, entirely on the device. Sessions are created when first needed and closed with the embedder,
 * so the roughly 150 MB of weights are in memory only while indexing or while the search screen is open. Inference is serialised
 * (one picture or sentence at a time, two threads inside ORT) to keep CPU and memory bounded; callers run it on a background
 * dispatcher and it never touches the main thread.
 */
class OrtClipEmbedder(
    private val context: Context,
    private val dir: File,
    private val tokenizer: ClipTokenizer,
    override val modelId: String,
    override val dimension: Int,
) : TextImageEmbedder {
    private val env: OrtEnvironment get() = OrtEnvironment.getEnvironment()
    private val lock = Mutex()
    private var vision: OrtSession? = null
    private var text: OrtSession? = null

    private fun options() = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(2)
        setInterOpNumThreads(1)
        setMemoryPatternOptimization(false) // lower peak memory; the first run is not faster than the next to a user
    }

    private fun visionSession() = vision ?: env.createSession(File(dir, "vision_model_quantized.onnx").path, options()).also { vision = it }
    private fun textSession() = text ?: env.createSession(File(dir, "text_model_quantized.onnx").path, options()).also { text = it }

    override suspend fun embedText(text: String): FloatArray = lock.withLock {
        withContext(Dispatchers.Default) {
            val ids = tokenizer.encode(text)
            OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { input ->
                textSession().run(mapOf("input_ids" to input)).use { out -> read(out.get("text_embeds").get().value) }
            }
        }
    }

    override suspend fun embedImage(uri: String): FloatArray? = lock.withLock {
        withContext(Dispatchers.Default) {
            val pixels = preprocess(uri) ?: return@withContext null
            OnnxTensor.createTensor(env, FloatBuffer.wrap(pixels), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { input ->
                visionSession().run(mapOf("pixel_values" to input)).use { out -> read(out.get("image_embeds").get().value) }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun read(value: Any): FloatArray = EmbeddingCodec.normalize((value as Array<FloatArray>)[0])

    /**
     * CLIP's own preprocessing: shorter side to 224 px, centre crop 224x224, RGB scaled to 0..1 and normalised with CLIP's mean
     * and standard deviation, channels first. The picture is decoded at about thumbnail size (never the 12 MP original) with the
     * platform decoder, which also applies the EXIF orientation.
     */
    private fun preprocess(uri: String): FloatArray? {
        var decoded: Bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, Uri.parse(uri))) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val shortest = minOf(info.size.width, info.size.height)
                // Decode at 2x to 4x the target so the reduction below can average pixels instead of skipping them (that is what keeps
                // the vector close to the one the model was validated with).
                decoder.setTargetSampleSize((shortest / (SIZE * 2)).coerceAtLeast(1))
            }
        } catch (e: IOException) {
            return null
        } catch (e: SecurityException) {
            return null
        } catch (e: RuntimeException) {
            return null
        }
        // Halve while the picture is at least twice the target: each halving is an exact 2x2 average, a good anti-aliasing filter.
        while (minOf(decoded.width, decoded.height) >= SIZE * 2) {
            val half = Bitmap.createScaledBitmap(decoded, decoded.width / 2, decoded.height / 2, true)
            decoded.recycle()
            decoded = half
        }
        val w = decoded.width; val h = decoded.height
        val scale = SIZE.toFloat() / minOf(w, h)
        val nw = maxOf(SIZE, Math.round(w * scale)); val nh = maxOf(SIZE, Math.round(h * scale))
        val scaled = if (nw == w && nh == h) decoded else Bitmap.createScaledBitmap(decoded, nw, nh, true)
        val left = (nw - SIZE) / 2; val top = (nh - SIZE) / 2
        val px = IntArray(SIZE * SIZE)
        scaled.getPixels(px, 0, SIZE, left, top, SIZE, SIZE)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        val out = FloatArray(3 * SIZE * SIZE)
        val plane = SIZE * SIZE
        for (i in 0 until plane) {
            val p = px[i]
            out[i] = (((p shr 16) and 0xFF) / 255f - MEAN[0]) / STD[0]
            out[plane + i] = (((p shr 8) and 0xFF) / 255f - MEAN[1]) / STD[1]
            out[2 * plane + i] = ((p and 0xFF) / 255f - MEAN[2]) / STD[2]
        }
        return out
    }

    override fun close() {
        vision?.close(); text?.close(); vision = null; text = null
    }

    private companion object {
        const val SIZE = 224
        val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
    }
}

/** Opens the embedder only when the pack is on the device and verified; otherwise there is no model and the feature says so. */
class ClipEmbedderProvider(private val context: Context, private val store: ModelPackStore, private val pack: ModelPack) : EmbedderProvider {
    override suspend fun open(): TextImageEmbedder? = withContext(Dispatchers.IO) {
        if (store.status(pack) != PackStatus.READY) return@withContext null
        val dir = store.dir(pack)
        val tokenizer = try {
            ClipTokenizer.parse(File(dir, "vocab.json").readText(), File(dir, "merges.txt").readText())
        } catch (e: IOException) {
            return@withContext null
        }
        OrtClipEmbedder(context, dir, tokenizer, pack.id, pack.dimension)
    }
}
