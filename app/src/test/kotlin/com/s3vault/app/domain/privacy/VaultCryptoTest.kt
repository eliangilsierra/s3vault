package com.s3vault.app.domain.privacy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Random

class VaultCryptoTest {
    private val key = VaultCrypto.newKey()
    private val header = 4 + 1 + 1 + 16
    private val sealedChunk = VaultCrypto.CHUNK + 16

    private fun data(n: Int, seed: Long = 7): ByteArray = ByteArray(n).also { Random(seed).nextBytes(it) }

    private fun seal(plain: ByteArray, k: ByteArray = key): ByteArray =
        ByteArrayOutputStream().also { VaultCrypto.encrypt(k, ByteArrayInputStream(plain), it) }.toByteArray()

    private fun open(sealed: ByteArray, k: ByteArray = key): ByteArray = VaultCrypto.decryptToBytes(k, ByteArrayInputStream(sealed))

    private fun assertCorrupt(sealed: ByteArray, k: ByteArray = key) {
        try {
            open(sealed, k)
            fail("expected VaultCorruptException")
        } catch (_: VaultCorruptException) { }
    }

    @Test fun `round trips at the sizes where chunking can go wrong`() {
        val c = VaultCrypto.CHUNK
        for (n in listOf(0, 1, 15, 16, c - 1, c, c + 1, 2 * c, 2 * c + 5, 5 * c + 123)) {
            val p = data(n, n.toLong())
            assertArrayEquals("size $n", p, open(seal(p)))
        }
    }

    @Test fun `sealed size is the plaintext plus a header and one tag per chunk`() {
        val c = VaultCrypto.CHUNK
        assertEquals(header + 16, seal(ByteArray(0)).size)
        assertEquals(header + 100 + 16, seal(data(100)).size)
        assertEquals(header + sealedChunk, seal(data(c)).size)
        assertEquals(header + sealedChunk + 1 + 16, seal(data(c + 1)).size)
    }

    @Test fun `the written summary has the length and the sha-256 of the plaintext`() {
        val p = data(3 * VaultCrypto.CHUNK + 9)
        val w = VaultCrypto.encrypt(key, ByteArrayInputStream(p), ByteArrayOutputStream())
        assertEquals(p.size.toLong(), w.plainBytes)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(p).joinToString("") { "%02x".format(it) }, w.sha256Hex)
    }

    @Test fun `the plaintext does not appear in the sealed file and two seals of the same bytes differ`() {
        val p = ByteArray(4096) { 'A'.code.toByte() }
        val a = seal(p)
        val b = seal(p)
        assertFalse(a.contentEquals(b))
        val needle = ByteArray(32) { 'A'.code.toByte() }
        assertTrue((0..a.size - needle.size).none { i -> (needle.indices).all { a[i + it] == needle[it] } })
    }

    @Test fun `a wrong key fails instead of returning garbage`() {
        assertCorrupt(seal(data(1000)), VaultCrypto.newKey())
    }

    @Test fun `flipping any single byte is detected`() {
        val p = data(VaultCrypto.CHUNK + 300)
        val s = seal(p)
        // header bytes, a byte of the first chunk, its tag, the second chunk, the last tag.
        for (i in listOf(0, 3, 4, 5, 6, header - 1, header, header + 1000, header + sealedChunk - 1, header + sealedChunk, s.size - 1)) {
            val t = s.copyOf(); t[i] = (t[i].toInt() xor 1).toByte()
            assertCorrupt(t)
        }
    }

    @Test fun `a truncated file is rejected at every cut, including exactly on a chunk boundary`() {
        val s = seal(data(3 * VaultCrypto.CHUNK + 50))
        for (cut in listOf(0, 5, header - 1, header, header + 10, header + sealedChunk, header + 2 * sealedChunk, s.size - 17, s.size - 1)) {
            assertCorrupt(s.copyOf(cut))
        }
    }

    @Test fun `appended bytes are rejected`() {
        assertCorrupt(seal(data(500)) + byteArrayOf(0))
        assertCorrupt(seal(data(VaultCrypto.CHUNK)) + data(16))
    }

    @Test fun `reordered or duplicated chunks are rejected`() {
        val s = seal(data(3 * VaultCrypto.CHUNK))
        val c0 = s.copyOfRange(header, header + sealedChunk)
        val c1 = s.copyOfRange(header + sealedChunk, header + 2 * sealedChunk)
        val c2 = s.copyOfRange(header + 2 * sealedChunk, header + 3 * sealedChunk)
        val head = s.copyOf(header)
        assertCorrupt(head + c1 + c0 + c2)
        assertCorrupt(head + c0 + c0 + c2)
        assertCorrupt(head + c0 + c2)
        assertCorrupt(head + c0 + c1)
    }

    @Test fun `chunks cannot be moved between files sealed with the same key`() {
        val a = seal(data(2 * VaultCrypto.CHUNK, 1))
        val b = seal(data(2 * VaultCrypto.CHUNK, 2))
        val spliced = a.copyOf(header + sealedChunk) + b.copyOfRange(header + sealedChunk, b.size)
        assertCorrupt(spliced)
        // Swapping only the file id changes the derived key.
        val swapped = a.copyOf(); System.arraycopy(b, 6, swapped, 6, 16)
        assertCorrupt(swapped)
    }

    @Test fun `an unknown magic or version is rejected as an unknown format`() {
        val s = seal(data(10))
        assertCorrupt(s.copyOf().also { it[0] = 'X'.code.toByte() })
        assertCorrupt(s.copyOf().also { it[4] = 9 })
        assertCorrupt("not a vault file at all".toByteArray())
    }

    @Test fun `the stream reads in small pieces and byte by byte`() {
        val p = data(VaultCrypto.CHUNK + 777)
        val s = VaultCrypto.DecryptingStream(key, ByteArrayInputStream(seal(p)))
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1000)
        while (true) { val n = s.read(buf); if (n < 0) break; out.write(buf, 0, n) }
        assertArrayEquals(p, out.toByteArray())
        val one = VaultCrypto.DecryptingStream(key, ByteArrayInputStream(seal(data(40))))
        assertEquals(data(40)[0].toInt() and 0xFF, one.read())
    }

    @Test fun `a bad key size is refused`() {
        try { VaultCrypto.encrypt(ByteArray(16), ByteArrayInputStream(data(1)), ByteArrayOutputStream()); fail() } catch (_: IllegalArgumentException) { }
    }
}
