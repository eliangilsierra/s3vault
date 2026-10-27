package com.s3vault.app

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.s3vault.app.data.local.MediaStoreSource
import com.s3vault.app.data.local.MediaStoreThumbnailMaker
import com.s3vault.app.data.privacy.PrivateMover
import com.s3vault.app.domain.MediaKind
import com.s3vault.app.domain.privacy.UnlockPrompt
import com.s3vault.app.domain.privacy.VaultCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random

/**
 * Measurements for the private vault on the device (the numbers in docs/private-albums.md and docs/performance.md): raw
 * encryption throughput, moving a few hundred real MediaStore pictures in, decrypting grid thumbnails, and moving them back out.
 * Logged under `PrivateBench`. Needs pictures in the library (the seeded ones are enough).
 */
class PrivateVaultBenchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext
    private val container get() = (ctx.applicationContext as S3VaultApp).container
    private val vaultDir get() = File(ctx.filesDir, "vault")

    @Before fun setUp() {
        container.keyringOverride = SoftKeyring()
        runBlocking(Dispatchers.IO) {
            container.privateSession.lock()
            container.db.privateVault().let { dao -> dao.albums().forEach { dao.deleteAlbum(it.id) }; dao.deleteVault() }
            vaultDir.deleteRecursively()
        }
    }

    @After fun tearDown() {
        container.privateSession.lock()
        runBlocking(Dispatchers.IO) { vaultDir.deleteRecursively(); container.db.privateVault().let { dao -> dao.albums().forEach { dao.deleteAlbum(it.id) }; dao.deleteVault() } }
        container.keyringOverride = null
    }

    private fun log(msg: String) = Log.i("PrivateBench", msg)

    @Test fun rawEncryptionThroughput() {
        val key = VaultCrypto.newKey()
        // 16 MiB: with 32 MiB this benchmark held about 160 MB at once (input, sealed copy, a doubling output buffer and its copy) on a 192 MB
        // heap, so it only passed by a few MB and failed as soon as the app process carried anything else. Throughput does not depend on the size.
        val data = ByteArray(16 * 1024 * 1024).also { Random(1).nextBytes(it) }
        var sealed = ByteArray(0)
        val e = measureNs { sealed = ByteArrayOutputStream(data.size + 1_000_000).also { VaultCrypto.encrypt(key, ByteArrayInputStream(data), it) }.toByteArray() }
        val d = measureNs { VaultCrypto.decryptToBytes(key, ByteArrayInputStream(sealed)) }
        log("crypto 16 MiB: encrypt ${e / 1_000_000} ms (%.0f MiB/s), decrypt ${d / 1_000_000} ms (%.0f MiB/s)".format(16e9 / e * 1.0 / 1.048576, 16e9 / d / 1.048576))
        assertTrue(sealed.size > data.size)
    }

    @Test fun movingAFewHundredRealPicturesInThumbnailsAndBackOut() = runBlocking {
        // Its own pictures (real MediaStore files, a few variants of camera-sized JPEG), removed afterwards.
        BulkPerfSupport.insertPhotos("pv_", 200)
        try { bench() } finally { withIo { BulkPerfSupport.deletePhotos("pv_") } }
    }

    private suspend fun bench() {
        container.library.refresh(force = true)
        val rows = withIo { container.db.media().getEverything().filter { it.present && it.kind == MediaKind.IMAGE && it.displayName.startsWith("pv_") } }
        assertTrue("needs the pictures it inserted (found ${rows.size})", rows.size >= 50)
        val store = container.privateVault
        store.unlock(UnlockPrompt("t", "s"))
        val album = store.createAlbum("Bench")
        val mover = PrivateMover(store, MediaStoreSource(ctx), MediaStoreThumbnailMaker(ctx))
        var report: PrivateMover.Report? = null
        val moveNs = measureNs { report = mover.moveIn(rows, album) }
        val r = report!!
        log("move in: ${r.moved.size} pictures (${r.bytes / 1024} KiB total, ${r.failed.size} failed) in ${moveNs / 1_000_000} ms = ${moveNs / 1_000_000 / maxOf(1, r.moved.size)} ms each (includes read, encrypt, verify by decrypting, 720 px preview)")
        assertEquals(0, r.failed.size)
        assertEquals(rows.size, r.moved.size)

        val items = store.observeItemsOnce(album)
        // Grid thumbnails: decrypt + decode of each, cold (empty cache), then warm.
        container.privateImages.clear()
        val cold = items.map { i -> val t = System.nanoTime(); container.privateImages.thumbnail(i, 360); (System.nanoTime() - t) / 1000 }.sorted()
        val warm = items.map { i -> val t = System.nanoTime(); container.privateImages.thumbnail(i, 360); (System.nanoTime() - t) / 1000 }.sorted()
        log("thumbnails (${items.size}): cold p50=${cold[cold.size / 2] / 1000.0} ms p95=${cold[cold.size * 95 / 100] / 1000.0} ms max=${cold.last() / 1000.0} ms; warm p50=${warm[warm.size / 2]} us")
        val full = items.take(20).map { i -> val t = System.nanoTime(); container.privateImages.full(i, 2048); (System.nanoTime() - t) / 1_000_000 }.sorted()
        log("full pictures to 2048 px (20): p50=${full[full.size / 2]} ms max=${full.last()} ms")
        val disk = vaultDir.listFiles().orEmpty().sumOf { it.length() }
        log("vault on disk: ${disk / 1024} KiB for ${items.size} pictures (originals ${r.bytes / 1024} KiB)")

        val out = ArrayList<Long>()
        val sink = com.s3vault.app.MediaStoreRestoreSink(ctx)
        val outNs = measureNs { out += mover.moveOut(items.take(50), sink).restored }
        log("move out: ${out.size} pictures in ${outNs / 1_000_000} ms = ${outNs / 1_000_000 / maxOf(1, out.size)} ms each")
        // Remove what the move-out put in the gallery, so the library is as it was.
        ctx.contentResolver.delete(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("Pictures/S3Vault/%"),
        )
        assertEquals(50, out.size)
    }

    private suspend fun <T> withIo(block: suspend () -> T): T = kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

    private inline fun measureNs(block: () -> Unit): Long { val t = System.nanoTime(); block(); return System.nanoTime() - t }
}

internal suspend fun com.s3vault.app.data.privacy.PrivateVaultStore.observeItemsOnce(albumId: Long) = observeItems(albumId).first()
