package com.s3vault.app.domain.faces

import com.s3vault.app.domain.semantic.EmbeddingCodec
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Seals the biometric parts of the face index (the vectors and the group centres) with AES-256-GCM before they reach the database.
 *
 * What this protects: a copy of the database file (a backup tool, a forensic image of an unlocked-but-not-rooted device, a bug that
 * exposes the file) is useless without the key, which lives in the Android Keystore and never in the app's files in the clear.
 * What it does not: code running inside this app's process, or a rooted device, can ask for the key like the app does.
 * The key is not tied to the screen lock on purpose: the analysis runs in the background, when no one can unlock anything.
 */
interface FaceVault {
    /** Encrypts [plain]. [purpose] is authenticated with it, so a sealed vector cannot be passed off as a sealed centre. */
    fun seal(plain: ByteArray, purpose: String): ByteArray

    /** The plaintext, or null when the data is not ours, was altered, or the key is gone (the index must then be rebuilt). */
    fun open(sealed: ByteArray, purpose: String): ByteArray?

    /** Destroys the key. Everything sealed before becomes unreadable for good (a "crypto-erase" that works even if rows linger). */
    fun wipe()
}

/** Where the 256-bit data key is kept. The Keystore-backed one wraps it with a hardware-backed key; tests use memory. */
interface DataKeyStore {
    /** The data key, created on first use. Throws if the platform cannot provide one. */
    fun load(): ByteArray

    /** Removes the key (and whatever protects it). A later [load] creates a different one. */
    fun destroy()
}

/** True when the key can seal and open data now. A key that exists but cannot be read makes this false, with or without data stored. */
fun FaceVault.isUsable(): Boolean = try {
    open(seal(byteArrayOf(1), "probe"), "probe") != null
} catch (e: Exception) {
    false
}

class AesGcmFaceVault(private val keys: DataKeyStore, private val random: SecureRandom = SecureRandom()) : FaceVault {
    @Volatile private var cached: SecretKeySpec? = null

    private fun key(): SecretKeySpec = cached ?: synchronized(this) {
        cached ?: SecretKeySpec(keys.load(), "AES").also { cached = it }
    }

    override fun seal(plain: ByteArray, purpose: String): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        c.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        val ct = c.doFinal(plain)
        return ByteBuffer.allocate(1 + iv.size + ct.size).put(FORMAT).put(iv).put(ct).array()
    }

    override fun open(sealed: ByteArray, purpose: String): ByteArray? {
        if (sealed.size < 1 + IV_BYTES + TAG_BITS / 8 || sealed[0] != FORMAT) return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed.copyOfRange(1, 1 + IV_BYTES)))
            c.updateAAD(purpose.toByteArray(Charsets.UTF_8))
            c.doFinal(sealed, 1 + IV_BYTES, sealed.size - 1 - IV_BYTES)
        } catch (e: java.security.GeneralSecurityException) {
            null
        } catch (e: IllegalStateException) {
            null // the key itself is unavailable
        }
    }

    override fun wipe() {
        synchronized(this) { cached = null; keys.destroy() }
    }

    private companion object {
        const val FORMAT: Byte = 1
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** A key held only in memory: for tests, never for the app. */
class MemoryDataKeyStore(private var key: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }) : DataKeyStore {
    override fun load(): ByteArray = key
    override fun destroy() { key = ByteArray(32).also { SecureRandom().nextBytes(it) } }
}

/** What the vault is asked to seal: a unit vector of [FaceModel.DIMENSION] numbers, quantised to one byte each plus one scale. */
object FaceVectorCodec {
    const val VECTOR = "face-vector-v1"
    const val CENTER = "person-center-v1"

    /** 4 bytes of scale + one signed byte per number: 132 bytes in place of 512. The cosine error of the rounding is below 0.003. */
    fun pack(v: FloatArray): ByteArray {
        val q = EmbeddingCodec.quantize(v)
        return ByteBuffer.allocate(4 + q.bytes.size).putFloat(q.scale).put(q.bytes).array()
    }

    /** The vector as unit-length floats, or null when the bytes are not a vector of this model. */
    fun unpack(bytes: ByteArray): FloatArray? {
        if (bytes.size != 4 + FaceModel.DIMENSION) return null
        val b = ByteBuffer.wrap(bytes)
        val scale = b.float
        if (!(scale > 0f) || scale.isInfinite()) return null
        return EmbeddingCodec.normalize(FloatArray(FaceModel.DIMENSION) { b.get().toFloat() * scale })
    }

    fun sealVector(vault: FaceVault, v: FloatArray): ByteArray = vault.seal(pack(v), VECTOR)
    fun openVector(vault: FaceVault, sealed: ByteArray): FloatArray? = vault.open(sealed, VECTOR)?.let(::unpack)

    /** A centre keeps full precision (it is one per person and the means of many vectors): 128 floats plus the member count. */
    fun sealCenter(vault: FaceVault, v: FloatArray): ByteArray {
        val b = ByteBuffer.allocate(4 * v.size)
        for (x in v) b.putFloat(x)
        return vault.seal(b.array(), CENTER)
    }

    fun openCenter(vault: FaceVault, sealed: ByteArray): FloatArray? {
        val plain = vault.open(sealed, CENTER) ?: return null
        if (plain.size != 4 * FaceModel.DIMENSION) return null
        val b = ByteBuffer.wrap(plain)
        return FloatArray(FaceModel.DIMENSION) { b.float }
    }
}
