package app.s3vault.sync

import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64

data class HashResult(val sha256Hex: String, val sha256Base64: String, val bytes: Long) {
    /** Identificador de versión: los primeros 128 bits del SHA-256. */
    val versionId: String get() = sha256Hex.take(32)
}

object Hashing {
    /**
     * SHA-256 en streaming (memoria constante). Es bloqueante: llamar desde un dispatcher de E/S.
     * [checkpoint] se invoca por bloque para poder cancelar (p. ej. `ensureActive()`).
     */
    fun sha256(input: InputStream, checkpoint: () -> Unit = {}): HashResult {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        input.use {
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                total += n
                checkpoint()
            }
        }
        val raw = digest.digest()
        return HashResult(raw.toHex(), Base64.getEncoder().encodeToString(raw), total)
    }

    fun hexToBase64(hex: String): String =
        Base64.getEncoder().encodeToString(ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
