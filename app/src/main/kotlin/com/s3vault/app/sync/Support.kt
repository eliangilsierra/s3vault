package com.s3vault.app.sync

import com.s3vault.app.domain.LocalFileReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64

data class ContentHash(val hexLower: String, val base64: String, val byteCount: Long)

/** Streams SHA-256 on the IO dispatcher. Never loads the file into memory. */
object Hasher {
    suspend fun sha256(open: () -> InputStream): ContentHash = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        open().use { input ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                total += n
            }
        }
        val digest = md.digest()
        ContentHash(digest.toHex(), Base64.getEncoder().encodeToString(digest), total)
    }

    suspend fun sha256(reader: LocalFileReader, uri: String): ContentHash = sha256 { reader.open(uri) }

    fun hexToBase64(hex: String): String =
        Base64.getEncoder().encodeToString(ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
}

internal fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        sb.append("0123456789abcdef"[(b.toInt() shr 4) and 0xF]).append("0123456789abcdef"[b.toInt() and 0xF])
    }
    return sb.toString()
}

data class Progress(val doneBytes: Long, val totalBytes: Long) {
    val fraction: Float get() = if (totalBytes <= 0) 0f else (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

/** In-memory, per-file progress for the UI. Purely cosmetic: the durable state is always in Room. */
class TransferProgress {
    private val _uploads = MutableStateFlow<Map<Long, Progress>>(emptyMap())
    val uploads: StateFlow<Map<Long, Progress>> = _uploads.asStateFlow()

    private val _downloads = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val downloads: StateFlow<Map<String, Progress>> = _downloads.asStateFlow()

    private val lastEmit = java.util.concurrent.ConcurrentHashMap<Any, Long>()

    fun upload(id: Long, done: Long, total: Long) {
        if (!throttle(id, done, total)) return
        _uploads.value = _uploads.value + (id to Progress(done, total))
    }

    fun uploadDone(id: Long) {
        lastEmit.remove(id)
        _uploads.value = _uploads.value - id
    }

    fun download(key: String, done: Long, total: Long) {
        if (!throttle(key, done, total)) return
        _downloads.value = _downloads.value + (key to Progress(done, total))
    }

    fun downloadDone(key: String) {
        lastEmit.remove(key)
        _downloads.value = _downloads.value - key
    }

    private fun throttle(id: Any, done: Long, total: Long): Boolean {
        val now = System.nanoTime() / 1_000_000
        val last = lastEmit[id] ?: 0L
        if (done >= total || now - last >= 150) {
            lastEmit[id] = now
            return true
        }
        return false
    }
}
