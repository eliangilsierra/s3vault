package com.s3vault.app.data.faces

import android.content.Context
import com.s3vault.app.domain.faces.FaceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest

/**
 * The two models that ship inside the app (assets/faces), checked against the SHA-256 recorded here before they run. A file that does
 * not match (a damaged install) is not used: the feature then reports that its models are missing instead of producing garbage.
 */
object FaceModels {
    const val DETECTOR_ASSET = "faces/face_detection_yunet_2023mar.onnx"
    const val RECOGNIZER_ASSET = "faces/face_recognition_sface_2021dec_fp16w.onnx"

    /** YuNet 2023mar, byte for byte the file published in OpenCV Zoo (MIT). */
    const val DETECTOR_BYTES = 232_589L
    const val DETECTOR_SHA256 = "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4"

    /** SFace 2021dec with its weights stored as float16 (converted with a local script that is not in the repository); Apache 2.0 like the original. */
    const val RECOGNIZER_BYTES = 19_373_514L
    const val RECOGNIZER_SHA256 = "faec3ea2b4831cc0ab3cf067e9e88108ca849efb948cad591f7f1f9cc9d3bb2e"

    /**
     * The runtime loads models from files, so each asset is copied once to the app's private storage (checking its SHA-256 while it
     * is copied) and reused afterwards. Reading them into Java arrays on every run cost two 19 MB allocations and their copies.
     */
    suspend fun open(context: Context, threadsPerSession: Int): FaceEngine? = withContext(Dispatchers.IO) {
        try {
            val det = ensure(context, DETECTOR_ASSET, DETECTOR_SHA256, DETECTOR_BYTES) ?: return@withContext null
            val rec = ensure(context, RECOGNIZER_ASSET, RECOGNIZER_SHA256, RECOGNIZER_BYTES) ?: return@withContext null
            OrtFaceEngine(det, rec, threadsPerSession)
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null // the runtime could not load the model
        } catch (e: ai.onnxruntime.OrtException) {
            null
        }
    }

    /** The extracted file for [asset], or null when the asset is damaged. An existing file is trusted only if it has the right size and name (the name carries the hash). */
    @Synchronized
    private fun ensure(context: Context, asset: String, sha: String, size: Long): java.io.File? {
        val dir = java.io.File(context.noBackupFilesDir, "faces").apply { mkdirs() }
        val target = java.io.File(dir, "${sha.take(16)}-${asset.substringAfterLast('/')}")
        if (target.isFile && target.length() == size) return target
        val tmp = java.io.File(dir, target.name + ".part")
        val md = java.security.MessageDigest.getInstance("SHA-256")
        context.assets.open(asset).use { input -> tmp.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n); out.write(buf, 0, n) }
        } }
        if (md.digest().joinToString("") { "%02x".format(it) } != sha) { tmp.delete(); return null }
        dir.listFiles()?.filter { it.name.endsWith(asset.substringAfterLast('/')) && it != tmp }?.forEach { it.delete() } // an older version of the same model
        target.delete()
        return if (tmp.renameTo(target)) target else null
    }

    @Volatile private var verified: Boolean? = null

    /** True when both bundled files are intact. The answer is kept for the life of the process: the files cannot change under a running app. */
    fun available(context: Context): Boolean = verified ?: try {
        ensure(context, DETECTOR_ASSET, DETECTOR_SHA256, DETECTOR_BYTES) != null && ensure(context, RECOGNIZER_ASSET, RECOGNIZER_SHA256, RECOGNIZER_BYTES) != null
    } catch (e: IOException) {
        false
    }.also { verified = it }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
