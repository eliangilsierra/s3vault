package com.s3vault.app.domain.privacy

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The file is damaged, was cut short, was rearranged, or was not written with this key. Nothing it held is returned. */
class VaultCorruptException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The format of an encrypted file of the private vault: authenticated encryption (AES-256-GCM) in chunks of 64 KiB, so a photo is
 * encrypted and decrypted as a stream with bounded memory and any change to the file is detected.
 *
 * ```
 * header : "S3VP" | version(1) | chunk size as a power of two (1) | file id (16 random bytes)
 * chunks : ciphertext(<= 64 KiB) + 16-byte GCM tag, one after the other
 * ```
 *  - Each file gets its own key, derived from the vault key and the file id with HMAC-SHA-256, so the vault key never encrypts
 *    anything directly and two files never share a key.
 *  - The nonce of a chunk is its index, so a key is never used with the same nonce twice.
 *  - The authenticated data of a chunk is the header, its index and whether it is the last one: chunks cannot be reordered,
 *    swapped between files, duplicated or dropped, and a truncated file fails instead of decrypting to a shorter photo.
 *
 * This protects the content of the file at rest against anyone who can read the app's storage but does not hold the vault key
 * (which only exists unwrapped while the vault is unlocked). It does not protect against a rooted or otherwise compromised
 * device, or against malware running while the vault is unlocked.
 */
object VaultCrypto {
    private const val MAGIC = "S3VP"
    private const val VERSION = 1
    private const val CHUNK_LOG2 = 16
    const val CHUNK = 1 shl CHUNK_LOG2
    private const val TAG_BYTES = 16
    private const val ID_BYTES = 16
    private const val HEADER = 4 + 1 + 1 + ID_BYTES
    private val random = SecureRandom()

    const val KEY_BYTES = 32

    fun newKey(): ByteArray = ByteArray(KEY_BYTES).also { random.nextBytes(it) }

    private fun fileKey(vaultKey: ByteArray, fileId: ByteArray): SecretKeySpec {
        require(vaultKey.size == KEY_BYTES) { "bad key size" }
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(vaultKey, "HmacSHA256")) }
        mac.update("s3vault.vault.file.v1".toByteArray())
        return SecretKeySpec(mac.doFinal(fileId), "AES")
    }

    private fun aad(header: ByteArray, index: Long, last: Boolean): ByteArray =
        ByteBuffer.allocate(header.size + 8 + 1).put(header).putLong(index).put(if (last) 1 else 0).array()

    private fun nonce(index: Long): ByteArray = ByteBuffer.allocate(12).putLong(index).putInt(0).array()

    private fun cipher(mode: Int, key: SecretKeySpec, header: ByteArray, index: Long, last: Boolean): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BYTES * 8, nonce(index)))
            updateAAD(aad(header, index, last))
        }

    /** What was written: how many plaintext bytes, and their SHA-256 (the vault checks the copy against the original with it). */
    class Written(val plainBytes: Long, val sha256Hex: String)

    fun encrypt(vaultKey: ByteArray, input: InputStream, output: OutputStream): Written {
        val id = ByteArray(ID_BYTES).also { random.nextBytes(it) }
        val header = ByteBuffer.allocate(HEADER).put(MAGIC.toByteArray()).put(VERSION.toByte()).put(CHUNK_LOG2.toByte()).put(id).array()
        output.write(header)
        val key = fileKey(vaultKey, id)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        var index = 0L
        var current = ByteArray(CHUNK)
        var next = ByteArray(CHUNK)
        var currentLen = readFully(input, current)
        while (true) {
            // Look one chunk ahead: the last chunk is the one after which there is nothing more.
            val nextLen = if (currentLen == CHUNK) readFully(input, next) else 0
            val last = nextLen <= 0
            digest.update(current, 0, currentLen)
            total += currentLen
            val c = cipher(Cipher.ENCRYPT_MODE, key, header, index, last)
            output.write(c.doFinal(current, 0, currentLen))
            if (last) break
            index++
            val t = current; current = next; next = t
            currentLen = nextLen
        }
        output.flush()
        return Written(total, digest.digest().joinToString("") { "%02x".format(it) })
    }

    /** Streams the plaintext of [input] to [output]. Throws [VaultCorruptException] on any mismatch, after possibly having written earlier chunks. */
    fun decrypt(vaultKey: ByteArray, input: InputStream, output: OutputStream) {
        val s = DecryptingStream(vaultKey, input)
        val buf = ByteArray(CHUNK)
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
        }
    }

    fun decryptToBytes(vaultKey: ByteArray, input: InputStream): ByteArray = ByteArrayOutputStream().also { decrypt(vaultKey, input, it) }.toByteArray()

    /** A stream of plaintext over an encrypted file, one chunk in memory at a time. A failure surfaces as an IOException on read. */
    class DecryptingStream(private val vaultKey: ByteArray, private val source: InputStream) : InputStream() {
        private var key: SecretKeySpec? = null
        private var header: ByteArray = ByteArray(0)
        private var index = 0L
        private var plain: ByteArray = ByteArray(0)
        private var pos = 0
        private var finished = false
        private var carry = ByteArray(0)

        private fun start() {
            val h = ByteArray(HEADER)
            if (readFully(source, h) != HEADER) throw VaultCorruptException("encabezado incompleto")
            if (String(h, 0, 4) != MAGIC || h[4].toInt() != VERSION || h[5].toInt() != CHUNK_LOG2) throw VaultCorruptException("formato no reconocido")
            header = h
            key = fileKey(vaultKey, h.copyOfRange(6, HEADER))
        }

        private fun fill(): Boolean {
            if (finished) return false
            if (key == null) start()
            // One byte more than a chunk tells whether this chunk is the last one.
            val want = CHUNK + TAG_BYTES + 1
            val buf = ByteArray(want)
            System.arraycopy(carry, 0, buf, 0, carry.size)
            val got = carry.size + readFully(source, buf, carry.size)
            val last = got < want
            val len = if (last) got else CHUNK + TAG_BYTES
            if (len < TAG_BYTES) throw VaultCorruptException("fragmento incompleto")
            carry = if (last) ByteArray(0) else buf.copyOfRange(len, got)
            try {
                plain = cipher(Cipher.DECRYPT_MODE, key!!, header, index, last).doFinal(buf, 0, len)
            } catch (e: GeneralSecurityException) {
                throw VaultCorruptException("el archivo no coincide con su clave o fue modificado", e)
            }
            index++
            pos = 0
            if (last) finished = true
            return true
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= plain.size) { if (!fill()) return -1 }
            val n = minOf(len, plain.size - pos)
            System.arraycopy(plain, pos, b, off, n)
            pos += n
            return n
        }

        override fun close() { source.close() }
    }

    private fun readFully(input: InputStream, buf: ByteArray, from: Int = 0): Int {
        var n = from
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return n - from
    }
}
