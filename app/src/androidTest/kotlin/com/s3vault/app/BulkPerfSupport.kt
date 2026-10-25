package com.s3vault.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream

/** Real photos in the real MediaStore for the performance scenarios (the numbers must come from the real pipeline, not mocks). */
object BulkPerfSupport {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val resolver get() = instrumentation.targetContext.contentResolver

    /** A handful of distinct 1200x900 JPEGs (about the size of a downscaled camera photo); photos reuse them round-robin. */
    private val variants: List<ByteArray> by lazy { (0 until 12).map { jpeg(it * 30) } }

    private fun jpeg(hue: Int): ByteArray {
        val w = 1200; val h = 900
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.8f, 0.95f)), Color.HSVToColor(floatArrayOf((hue + 70f) % 360, 0.9f, 0.35f)), Shader.TileMode.CLAMP,
            )
        })
        val rnd = java.util.Random(hue.toLong())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(60) {
            p.color = Color.argb(160, rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
            canvas.drawCircle(rnd.nextInt(w).toFloat(), rnd.nextInt(h).toFloat(), 30f + rnd.nextInt(120), p)
        }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    /** One file with exactly these bytes in the real MediaStore (tests that need a file whose content they know). */
    fun insertBytes(name: String, bytes: ByteArray, folder: String = "Pictures/S3VaultTest"): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    fun delete(uri: Uri) { runCatching { resolver.delete(uri, null, null) } }

    /** Inserts [count] photos named `{prefix}0001.jpg`... and returns their content URIs. */
    fun insertPhotos(prefix: String, count: Int, from: Int = 1): List<Uri> = (from until from + count).map { i ->
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$prefix${"%04d".format(i)}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/S3VaultBulkPerf")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { it.write(variants[i % variants.size]) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        uri
    }

    /** One camera-sized JPEG (4000x3000, about 12 MP) for decode-cost measurements. */
    fun insertLargePhoto(name: String): Uri {
        val w = 4000; val h = 3000
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val rnd = java.util.Random(name.hashCode().toLong())
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.rgb(20, 90, 200), Color.rgb(230, 120, 30), Shader.TileMode.CLAMP)
        })
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(400) {
            p.color = Color.argb(150, rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
            canvas.drawCircle(rnd.nextInt(w).toFloat(), rnd.nextInt(h).toFloat(), 20f + rnd.nextInt(200), p)
        }
        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/S3VaultBulkPerf")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    /** Only ever touches the folder this support class writes to, whatever the prefix. */
    fun deletePhotos(prefix: String) {
        require(prefix.length >= 3 && prefix.endsWith("_")) { "Use a specific prefix such as bp_" }
        resolver.delete(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$prefix%", "Pictures/S3VaultBulkPerf%"),
        )
    }
}
