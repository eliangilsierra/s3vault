package com.s3vault.app.domain.faces

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class FaceVaultTest {
    private val vault = AesGcmFaceVault(MemoryDataKeyStore())

    @Test fun `what is sealed opens again, and the sealed bytes do not contain the plaintext`() {
        val plain = ByteArray(132) { (it * 7).toByte() }
        val sealed = vault.seal(plain, "p")
        assertArrayEquals(plain, vault.open(sealed, "p"))
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains(String(plain, Charsets.ISO_8859_1)))
        assertTrue(sealed.size >= plain.size + 12 + 16)
        // Two seals of the same bytes differ (random IV): nothing can be learned from equal ciphertexts.
        assertFalse(sealed.contentEquals(vault.seal(plain, "p")))
    }

    @Test fun `a changed byte, another purpose or another key do not open`() {
        val sealed = vault.seal(ByteArray(40) { 1 }, "vector")
        val tampered = sealed.copyOf().also { it[it.size - 3] = (it[it.size - 3] + 1).toByte() }
        assertNull(vault.open(tampered, "vector"))
        assertNull(vault.open(sealed, "centre"))
        assertNull(AesGcmFaceVault(MemoryDataKeyStore()).open(sealed, "vector"))
        assertNull(vault.open(ByteArray(5), "vector"))
    }

    @Test fun `wiping the key makes everything sealed before unreadable`() {
        val sealed = vault.seal(ByteArray(10), "x")
        assertNotNull(vault.open(sealed, "x"))
        vault.wipe()
        assertNull(vault.open(sealed, "x"))
        // A new key works for new data.
        assertNotNull(vault.open(vault.seal(ByteArray(10), "x"), "x"))
    }

    @Test fun `a key that cannot be read is an unreadable index, not a crash`() {
        val broken = AesGcmFaceVault(object : DataKeyStore {
            override fun load(): ByteArray = throw IllegalStateException("gone")
            override fun destroy() = Unit
        })
        assertNull(broken.open(ByteArray(40) { 3 }.let { it[0] = 1; it }, "x"))
    }

    @Test fun `vectors survive the codec with a rounding error far below any threshold`() {
        val rnd = Random(5)
        repeat(50) {
            val v = FaceTestKit.randomUnit(rnd)
            val back = FaceVectorCodec.openVector(vault, FaceVectorCodec.sealVector(vault, v))!!
            assertTrue("cosine ${FaceMath.dot(v, back)}", FaceMath.dot(v, back) > 0.9999f)
        }
        val packed = FaceVectorCodec.pack(FaceTestKit.randomUnit(rnd))
        assertEquals(4 + FaceModel.DIMENSION, packed.size)
        assertNull(FaceVectorCodec.unpack(ByteArray(10)))
        // A vector cannot be passed off as a centre or the other way round.
        assertNull(FaceVectorCodec.openCenter(vault, FaceVectorCodec.sealVector(vault, FaceTestKit.randomUnit(rnd))))
        val c = FaceTestKit.randomUnit(rnd)
        assertArrayEquals(c, FaceVectorCodec.openCenter(vault, FaceVectorCodec.sealCenter(vault, c))!!, 0f)
    }
}
