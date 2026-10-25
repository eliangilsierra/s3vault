package com.s3vault.app.domain.semantic

import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Turns pictures and text into vectors of one shared space, where a picture and a sentence that describe the same thing end up
 * close. Everything runs on the device. This interface is the extension point of the feature: another model only needs another
 * [ModelPack] (what to download, with hashes) and another implementation of this interface.
 */
interface TextImageEmbedder : AutoCloseable {
    /** Identifies the model: vectors from different models are never compared. */
    val modelId: String
    val dimension: Int

    /** Unit-length vector of a sentence. Runs off the main thread. */
    suspend fun embedText(text: String): FloatArray

    /** Unit-length vector of the picture, or null when it cannot be decoded. Runs off the main thread. */
    suspend fun embedImage(uri: String): FloatArray?
}

/** Gives the embedder once the model is on the device, and null while it is not (the feature then says so instead of guessing). */
fun interface EmbedderProvider {
    suspend fun open(): TextImageEmbedder?
}

/** One file of a model pack: where it comes from and what it must hash to. A file that does not match is never used. */
data class PackFile(val name: String, val url: String, val sizeBytes: Long, val sha256: String)

/**
 * A model the user can choose to download. Nothing here is bundled in the app: the pack is fetched only after an explicit
 * choice that shows its size and host, from a URL pinned to one revision, and every file is checked against the SHA-256 recorded
 * here before the model is allowed to run.
 */
data class ModelPack(
    val id: String,
    val title: String,
    val license: String,
    val origin: String,
    val dimension: Int,
    val files: List<PackFile>,
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }
    val host: String get() = files.first().url.substringAfter("://").substringBefore('/')
}

object ModelPacks {
    private const val REVISION = "d15189d7028b43f1d3e65039190477f6af591c2a"
    private const val BASE = "https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/$REVISION"

    /**
     * OpenAI CLIP ViT-B/32 (MIT licence), ONNX conversion with int8 weights by Xenova (Transformers.js). Chosen over the
     * alternatives because it is permissively licensed, the weights are published as plain ONNX files with stable hashes, and its
     * quality is well documented; see docs/semantic-search.md for the comparison.
     */
    val ClipB32 = ModelPack(
        id = "clip-vit-b32-int8-${REVISION.take(8)}",
        title = "CLIP ViT-B/32 (int8)",
        license = "MIT (OpenAI CLIP)",
        origin = "huggingface.co/Xenova/clip-vit-base-patch32, revisión ${REVISION.take(7)}",
        dimension = 512,
        files = listOf(
            PackFile("vision_model_quantized.onnx", "$BASE/onnx/vision_model_quantized.onnx", 89_117_001, "583fd1110a514667812fee7d684952aaf82a99b959760c8d7dca7e0ab9839299"),
            PackFile("text_model_quantized.onnx", "$BASE/onnx/text_model_quantized.onnx", 64_504_507, "73baab855d406190da9faa498cfedf65f15cf309f4cc7385b7b032e6d08e5c3a"),
            PackFile("vocab.json", "$BASE/vocab.json", 862_328, "5047b556ce86ccaf6aa22b3ffccfc52d391ea4accdab9c2f2407da5b742d4363"),
            PackFile("merges.txt", "$BASE/merges.txt", 524_619, "9fd691f7c8039210e0fced15865466c65820d09b63988b0174bfe25de299051a"),
        ),
    )

    val all = listOf(ClipB32)
}

enum class PackStatus {
    MISSING,

    /** Some files (or half-downloaded parts) are there. */
    PARTIAL,

    /** Every file has the right size but was never checked against its hash (copied by hand, say). */
    UNVERIFIED,
    READY,
}

/**
 * The pack's folder. A pack is READY only when every file has its exact size and was verified against its SHA-256; the
 * verification is remembered with each file's size and modification time, so the 150 MB are hashed once, not at every start.
 */
class ModelPackStore(private val root: File) {
    fun dir(pack: ModelPack) = File(root, pack.id)
    fun file(pack: ModelPack, f: PackFile) = File(dir(pack), f.name)
    private fun marker(pack: ModelPack) = File(dir(pack), ".verified")

    private fun signature(pack: ModelPack): String =
        pack.files.joinToString(";") { f -> file(pack, f).let { "${f.name}:${it.length()}:${it.lastModified()}" } }

    fun status(pack: ModelPack): PackStatus {
        val d = dir(pack)
        if (!d.isDirectory) return PackStatus.MISSING
        val present = pack.files.count { file(pack, it).length() == it.sizeBytes }
        return when {
            present == pack.files.size ->
                if (marker(pack).takeIf { it.isFile }?.readText() == signature(pack)) PackStatus.READY else PackStatus.UNVERIFIED
            present > 0 || (d.listFiles()?.any { it.name.endsWith(".part") } == true) -> PackStatus.PARTIAL
            else -> PackStatus.MISSING
        }
    }

    /** The first file whose bytes do not hash to the expected value, or null when all do (the pack is then marked verified). */
    fun verify(pack: ModelPack, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false }): PackFile? {
        var done = 0L
        for (f in pack.files) {
            val file = file(pack, f)
            if (!file.isFile || file.length() != f.sizeBytes) return f
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    if (cancelled()) return f
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                    done += n
                    onProgress(done, pack.totalBytes)
                }
            }
            if (md.digest().joinToString("") { "%02x".format(it) } != f.sha256) return f
        }
        marker(pack).writeText(signature(pack))
        return null
    }

    fun deleteFile(pack: ModelPack, f: PackFile) { file(pack, f).delete(); File(dir(pack), f.name + ".part").delete(); marker(pack).delete() }

    /** Removes the whole pack from the device (the generated vectors are separate and are removed by the user's choice). */
    fun delete(pack: ModelPack) { dir(pack).deleteRecursively() }

}

/** Vectors are stored as signed bytes with one scale per vector: about 0.5 KB per picture instead of 2 KB, with no visible change in ranking. */
object EmbeddingCodec {
    class Quantized(val bytes: ByteArray, val scale: Float)

    fun quantize(v: FloatArray): Quantized {
        var max = 0f
        for (x in v) max = maxOf(max, abs(x))
        val scale = if (max == 0f) 1f else max / 127f
        return Quantized(ByteArray(v.size) { i -> Math.round(v[i] / scale).coerceIn(-127, 127).toByte() }, scale)
    }

    /** The dot product of a unit-length [query] with a stored vector: their cosine similarity. */
    fun dot(query: FloatArray, bytes: ByteArray, scale: Float): Float {
        var s = 0f
        for (i in query.indices) s += query[i] * bytes[i]
        return s * scale
    }

    fun normalize(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x * x
        val norm = sqrt(n).toFloat()
        return if (norm == 0f) v else FloatArray(v.size) { v[it] / norm }
    }
}

/** A search hit: which picture and how close it is (cosine similarity, roughly 0.15 for unrelated up to 0.3 for a good match with CLIP). */
data class Scored(val id: Long, val score: Float)

/** Keeps the best [k] hits while the stored vectors stream past, so memory does not grow with the library. */
class TopK(private val k: Int) {
    private val heap = java.util.PriorityQueue<Scored>(k + 1, compareBy { it.score })
    fun offer(id: Long, score: Float) {
        if (heap.size < k) { heap.add(Scored(id, score)); return }
        val worst = heap.peek()
        if (worst != null && score > worst.score) { heap.poll(); heap.add(Scored(id, score)) }
    }
    fun best(): List<Scored> = heap.sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.id })
}
