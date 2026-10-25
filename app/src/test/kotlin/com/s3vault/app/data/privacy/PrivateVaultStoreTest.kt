package com.s3vault.app.data.privacy

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.s3vault.app.data.local.AppDatabase
import com.s3vault.app.domain.MediaKind
import com.s3vault.app.domain.privacy.KeyringResult
import com.s3vault.app.domain.privacy.PrivateMeta
import com.s3vault.app.domain.privacy.PrivateSession
import com.s3vault.app.domain.privacy.UnlockPrompt
import com.s3vault.app.domain.privacy.VaultAvailability
import com.s3vault.app.domain.privacy.VaultCrypto
import com.s3vault.app.domain.privacy.VaultKeyring
import com.s3vault.app.domain.privacy.VaultLockedException
import com.s3vault.app.domain.privacy.WrappedKey
import com.s3vault.app.sync.FakeReader
import com.s3vault.app.sync.RestoreSink
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A keyring in software: the wrapping key is a plain AES key, and a test can make it cancel, lose the key or have no screen lock. */
class FakeKeyring : VaultKeyring {
    var secure = true
    var cancel = false
    var lost = false
    var prompts = 0
    private var wrapKey = ByteArray(32).also { Random(1).nextBytes(it) }

    override fun isSecure() = secure

    private fun cipher(mode: Int, iv: ByteArray?): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        if (iv == null) init(mode, SecretKeySpec(wrapKey, "AES")) else init(mode, SecretKeySpec(wrapKey, "AES"), GCMParameterSpec(128, iv))
    }

    override suspend fun create(prompt: UnlockPrompt): KeyringResult {
        if (!secure) return KeyringResult.NeedsScreenLock
        prompts++
        if (cancel) return KeyringResult.Cancelled
        wrapKey = ByteArray(32).also { Random(System.nanoTime()).nextBytes(it) }
        val master = VaultCrypto.newKey()
        val c = cipher(Cipher.ENCRYPT_MODE, null)
        return KeyringResult.Key(master, WrappedKey(c.doFinal(master), c.iv))
    }

    override suspend fun unwrap(wrapped: WrappedKey, prompt: UnlockPrompt): KeyringResult {
        prompts++
        if (lost) return KeyringResult.KeyLost("clave perdida")
        if (cancel) return KeyringResult.Cancelled
        return KeyringResult.Key(cipher(Cipher.DECRYPT_MODE, wrapped.iv).doFinal(wrapped.cipherText), wrapped)
    }

    override fun deleteKey() { wrapKey = ByteArray(32) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivateVaultStoreTest {
    private lateinit var db: AppDatabase
    private lateinit var dir: File
    private lateinit var keyring: FakeKeyring
    private lateinit var session: PrivateSession
    private lateinit var store: PrivateVaultStore
    private var now = 1_000_000L
    private val prompt = UnlockPrompt("t", "s")

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        dir = File(ctx.cacheDir, "vault-test-${System.nanoTime()}")
        keyring = FakeKeyring()
        session = PrivateSession { now }
        store = PrivateVaultStore(db.privateVault(), keyring, session, dir) { now }
    }

    @After fun tearDown() { db.close(); dir.deleteRecursively() }

    private fun unlock() = runBlocking { assertEquals(PrivateVaultStore.UnlockOutcome.Unlocked, store.unlock(prompt)) }

    private fun bytes(n: Int, seed: Long = 3) = ByteArray(n).also { Random(seed).nextBytes(it) }
    private fun meta(name: String, size: Int) = PrivateMeta(name, "image/jpeg", size.toLong(), 640, 480, 5_000, "", "Camera")

    private fun add(album: Long, name: String, data: ByteArray, thumb: ByteArray? = "thumb-$name".toByteArray()) =
        runBlocking { store.add(album, { ByteArrayInputStream(data) }, thumb, meta(name, data.size)) }

    private fun files() = dir.listFiles()?.map { it.name }.orEmpty()

    // ------------------------------------------------------------------ the key

    @Test fun `creating the vault and unlocking again need the keyring each time and the key opens the same files`() {
        runBlocking {
            assertEquals(VaultAvailability.NOT_CREATED, store.availability())
            unlock()
            assertEquals(VaultAvailability.READY, store.availability())
            val album = store.createAlbum("Documentos")
            val data = bytes(5000)
            assertTrue(add(album, "a.jpg", data) is PrivateVaultStore.AddResult.Added)
            store.lock()
            assertFalse(session.unlocked.value)
            unlock() // a second prompt, the same vault
            assertEquals(2, keyring.prompts)
            val item = store.observeItems(album).first().single()
            assertArrayEquals(data, store.imageBytes(item))
        }
    }

    @Test fun `a cancelled prompt leaves everything locked and a device without screen lock is refused`() {
        runBlocking {
            keyring.cancel = true
            assertEquals(PrivateVaultStore.UnlockOutcome.Cancelled, store.unlock(prompt))
            assertFalse(session.unlocked.value)
            keyring.cancel = false; keyring.secure = false
            assertEquals(VaultAvailability.NEEDS_SCREEN_LOCK, store.availability())
            assertEquals(PrivateVaultStore.UnlockOutcome.NeedsScreenLock, store.unlock(prompt))
            assertNull("no vault row was created", db.privateVault().vault())
        }
    }

    @Test fun `a lost key is reported and the files are only removed when the user resets the vault`() {
        runBlocking {
            unlock()
            val album = store.createAlbum("X")
            add(album, "a.jpg", bytes(100))
            store.lock()
            keyring.lost = true
            val out = store.unlock(prompt)
            assertTrue(out is PrivateVaultStore.UnlockOutcome.KeyLost)
            assertEquals(VaultAvailability.KEY_LOST, store.availability())
            assertTrue("nothing is deleted by merely failing to unlock", files().isNotEmpty())
            store.resetLostVault()
            assertTrue(files().isEmpty())
            assertNull(db.privateVault().vault())
            assertEquals(0, db.privateVault().itemCount())
            keyring.lost = false
            assertEquals(VaultAvailability.NOT_CREATED, store.availability())
        }
    }

    @Test fun `a key that does not match the stored canary is treated as lost`() {
        runBlocking {
            unlock()
            store.lock()
            val row = db.privateVault().vault()!!
            // The row now belongs to another vault key: unwrapping gives a key that does not open the canary.
            db.openHelper.writableDatabase.execSQL("UPDATE private_vault SET keyCheck = x'00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff'")
            assertTrue(store.unlock(prompt) is PrivateVaultStore.UnlockOutcome.KeyLost)
            assertFalse(session.unlocked.value)
            assertNotNull(row)
        }
    }

    // ------------------------------------------------------------------ what is stored

    @Test fun `nothing readable is in the database or the files`() {
        runBlocking {
            unlock()
            val album = store.createAlbum("Pasaporte y médicos")
            val data = "FOTO-DEL-PASAPORTE-".repeat(50).toByteArray()
            add(album, "pasaporte_juan.jpg", data)
            val needles = listOf("Pasaporte y médicos", "pasaporte_juan", "FOTO-DEL-PASAPORTE", "image/jpeg", "Camera")
            // Database: every column of every private table.
            val sql = db.openHelper.readableDatabase
            val dump = StringBuilder()
            for (t in listOf("private_albums", "private_items", "private_vault")) {
                sql.query("SELECT * FROM $t").use { c ->
                    while (c.moveToNext()) for (i in 0 until c.columnCount) when (c.getType(i)) {
                        android.database.Cursor.FIELD_TYPE_BLOB -> dump.append(String(c.getBlob(i), Charsets.ISO_8859_1))
                        else -> dump.append(c.getString(i))
                    }
                }
            }
            for (n in needles) assertFalse("database leaks '$n'", dump.contains(n))
            // Files: contents and names.
            for (f in dir.listFiles()!!) {
                val text = String(f.readBytes(), Charsets.ISO_8859_1)
                for (n in needles) assertFalse("${f.name} leaks '$n'", text.contains(n))
                assertFalse("file name leaks the picture name", f.name.contains("pasaporte"))
            }
        }
    }

    @Test fun `albums and pictures are invisible while locked and come back after unlocking`() {
        runBlocking {
            unlock()
            val album = store.createAlbum("Privado")
            add(album, "a.jpg", bytes(100))
            assertEquals(listOf("Privado"), store.observeAlbums().first().map { it.name })
            store.lock()
            assertTrue(store.observeAlbums().first().isEmpty())
            assertTrue(store.observeItems(album).first().isEmpty())
            try { store.imageBytes(store.item(1) ?: error("locked")); fail() } catch (_: IllegalStateException) { }
            unlock()
            assertEquals(1, store.observeAlbums().first().single().items)
            assertEquals("a.jpg", store.observeItems(album).first().single().meta.name)
        }
    }

    @Test fun `add records the real size and hash and refuses a size that does not match what was read`() {
        runBlocking {
            unlock()
            val album = store.createAlbum("A")
            val data = bytes(70_000)
            assertTrue(add(album, "a.jpg", data) is PrivateVaultStore.AddResult.Added)
            val item = store.observeItems(album).first().single()
            assertEquals(data.size.toLong(), item.meta.sizeBytes)
            assertEquals(java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }, item.meta.sha256)
            // The index said 10 bytes; 70 000 were read: the copy is refused and leaves nothing behind.
            val before = files()
            val r = runBlocking { store.add(album, { ByteArrayInputStream(data) }, null, meta("b.jpg", 10)) }
            assertTrue(r is PrivateVaultStore.AddResult.Failed)
            assertEquals(before, files())
            assertEquals(1, db.privateVault().itemCount())
        }
    }

    @Test fun `a failing source leaves no row and no stray file`() {
        runBlocking {
            unlock()
            val album = store.createAlbum("A")
            val r = store.add(album, { object : java.io.InputStream() { override fun read(): Int = throw java.io.IOException("boom") } }, null, meta("x.jpg", 5))
            assertTrue(r is PrivateVaultStore.AddResult.Failed)
            assertTrue(files().isEmpty())
            assertEquals(0, db.privateVault().itemCount())
        }
    }

    @Test fun `a locked vault refuses to add`() {
        runBlocking {
            unlock(); val album = store.createAlbum("A"); store.lock()
            assertTrue(add(album, "a.jpg", bytes(10)) is PrivateVaultStore.AddResult.Failed)
            assertEquals(0, db.privateVault().itemCount())
        }
    }

    @Test fun `a tampered file fails to open instead of showing something else`() {
        runBlocking {
            unlock(); val album = store.createAlbum("A")
            add(album, "a.jpg", bytes(5000))
            val item = store.observeItems(album).first().single()
            val f = File(dir, "${item.fileName}.v")
            val b = f.readBytes(); b[b.size / 2] = (b[b.size / 2].toInt() xor 1).toByte(); f.writeBytes(b)
            try { store.imageBytes(item); fail() } catch (_: com.s3vault.app.domain.privacy.VaultCorruptException) { }
        }
    }

    @Test fun `removing pictures deletes rows and files, and deleting an album deletes everything in it`() {
        runBlocking {
            unlock()
            val a = store.createAlbum("A"); val b = store.createAlbum("B")
            repeat(3) { add(a, "a$it.jpg", bytes(100, it.toLong())) }
            add(b, "b.jpg", bytes(100))
            val ids = store.observeItems(a).first().map { it.id }
            store.remove(ids.take(1))
            assertEquals(2, store.observeItems(a).first().size)
            assertEquals(2 * 2 + 2, files().size) // 2 pictures + their previews in A, 1 + 1 in B
            store.deleteAlbum(a)
            assertEquals(2, files().size)
            assertEquals(1, db.privateVault().itemCount())
            assertEquals(listOf("B"), store.observeAlbums().first().map { it.name })
        }
    }

    @Test fun `moving between albums keeps the picture and renaming changes only the sealed name`() {
        runBlocking {
            unlock()
            val a = store.createAlbum("A"); val b = store.createAlbum("B")
            add(a, "x.jpg", bytes(100))
            val id = store.observeItems(a).first().single().id
            store.move(listOf(id), b)
            assertTrue(store.observeItems(a).first().isEmpty())
            assertEquals(1, store.observeItems(b).first().size)
            store.renameAlbum(b, "  Nuevo nombre  ")
            assertEquals("Nuevo nombre", store.observeAlbums().first().first { it.id == b }.name)
        }
    }

    @Test fun `stray temp files and unreferenced files are swept after the grace period only`() {
        runBlocking {
            unlock(); val album = store.createAlbum("A")
            add(album, "a.jpg", bytes(100))
            dir.mkdirs()
            File(dir, "deadbeef.v").writeBytes(byteArrayOf(1)); File(dir, "cafe.v.tmp").writeBytes(byteArrayOf(1))
            val known = files().size
            assertEquals("young files are left alone", 0, store.sweepStrays(graceMs = Long.MAX_VALUE))
            File(dir, "deadbeef.v").setLastModified(0); File(dir, "cafe.v.tmp").setLastModified(0)
            now = 100 * 60_000L
            assertEquals(2, store.sweepStrays())
            assertEquals(known - 2, files().size)
            assertEquals("the registered picture is untouched", 1, store.observeItems(album).first().size)
        }
    }

    @Test fun `empty album names are refused`() {
        runBlocking {
            unlock()
            try { store.createAlbum("   "); fail() } catch (_: IllegalArgumentException) { }
        }
    }

    // ------------------------------------------------------------------ the mover

    private fun mover(reader: FakeReader, thumbs: com.s3vault.app.domain.ThumbnailMaker? = com.s3vault.app.domain.ThumbnailMaker { _, _ -> "thumb".toByteArray() }) =
        PrivateMover(store, reader, thumbs)

    private fun media(id: Long, reader: FakeReader, data: ByteArray = bytes(3000, id), kind: MediaKind = MediaKind.IMAGE, present: Boolean = true): com.s3vault.app.data.local.MediaItemEntity {
        reader.files["content://media/$id"] = data
        return com.s3vault.app.data.local.MediaItemEntity(
            mediaStoreId = id, uri = "content://media/$id", kind = kind, displayName = "IMG_$id.jpg", mimeType = "image/jpeg", sizeBytes = data.size.toLong(),
            dateModifiedSec = 1, dateTakenMs = 5_000 + id, width = 10, height = 10, folderName = "Camera", assetId = "a$id", present = present,
        )
    }

    @Test fun `moving in copies verified pictures, reports them, skips videos, and never reads or touches anything else`() {
        runBlocking {
            unlock(); val album = store.createAlbum("A")
            val reader = FakeReader()
            val ok = (1L..5L).map { media(it, reader) }
            val video = media(9, reader, kind = MediaKind.VIDEO)
            val gone = media(10, reader, present = false)
            val progress = mutableListOf<Pair<Int, Int>>()
            val report = mover(reader).moveIn(ok + video + gone, album) { d, t -> progress += d to t }
            assertEquals((1L..5L).toList(), report.moved)
            assertEquals(listOf(9L, 10L), report.skipped.map { it.id })
            assertTrue(report.failed.isEmpty())
            assertEquals(ok.sumOf { it.sizeBytes }, report.bytes)
            assertEquals(0 to 7, progress.first()); assertEquals(7 to 7, progress.last())
            assertEquals("videos and missing files are never opened", ok.map { it.uri }, reader.opens.toList())
            // The originals are still there: the mover has no way to delete them.
            assertTrue(ok.all { reader.files.containsKey(it.uri) })
            val items = store.observeItems(album).first()
            assertEquals(5, items.size)
            for (m in ok) assertArrayEquals(reader.files[m.uri], store.imageBytes(items.first { it.meta.name == m.displayName }))
        }
    }

    @Test fun `a picture that cannot be read is reported as failed and the rest still move`() {
        runBlocking {
            unlock(); val album = store.createAlbum("A")
            val reader = FakeReader()
            val a = media(1, reader); val b = media(2, reader); val c = media(3, reader)
            reader.files.remove(b.uri)
            val r = mover(reader).moveIn(listOf(a, b, c), album)
            assertEquals(listOf(1L, 3L), r.moved)
            assertEquals(listOf(2L), r.failed.map { it.id })
            assertEquals(2, db.privateVault().itemCount())
        }
    }

    @Test fun `locking in the middle stops the batch and what was done stays valid`() {
        runBlocking {
            unlock(); val album = store.createAlbum("A")
            val reader = FakeReader()
            val items = (1L..6L).map { media(it, reader) }
            val r = mover(reader).moveIn(items, album) { done, _ -> if (done == 2) session.lock() }
            assertTrue(r.stopped)
            assertEquals(listOf(1L, 2L), r.moved)
            unlock()
            assertEquals(2, store.observeItems(album).first().size)
        }
    }

    @Test fun `a hundred and fifty pictures move in one batch with bounded files`() {
        runBlocking {
            unlock(); val album = store.createAlbum("A")
            val reader = FakeReader()
            val items = (1L..150L).map { media(it, reader, bytes(20_000, it)) }
            val r = mover(reader).moveIn(items, album)
            assertEquals(150, r.moved.size)
            assertEquals(150, db.privateVault().itemCount())
            assertEquals(300, files().size) // a picture and a preview each, no temp files
            assertTrue(files().none { it.endsWith(".tmp") })
        }
    }

    private class MemorySink : RestoreSink {
        val created = mutableListOf<Pair<String, ByteArrayOutputStream>>()
        var committed = 0; var aborted = 0
        override suspend fun create(displayName: String, mimeType: String): RestoreSink.Target {
            val out = ByteArrayOutputStream(); created += displayName to out
            return object : RestoreSink.Target {
                override val output: OutputStream = out
                override suspend fun commit(): String { committed++; return "content://restored/$displayName" }
                override suspend fun abort() { aborted++ }
            }
        }
    }

    @Test fun `moving out writes a verified copy first and removes the sealed one only after it is committed`() {
        runBlocking {
            unlock(); val album = store.createAlbum("A")
            val reader = FakeReader()
            val originals = (1L..3L).map { media(it, reader) }
            mover(reader).moveIn(originals, album)
            val items = store.observeItems(album).first()
            val sink = MemorySink()
            val r = mover(reader).moveOut(items, sink)
            assertEquals(3, r.restored.size)
            assertEquals(3, sink.committed)
            assertEquals(0, db.privateVault().itemCount())
            assertTrue(files().isEmpty())
            for ((name, out) in sink.created) assertArrayEquals(reader.files["content://media/${name.removePrefix("IMG_").removeSuffix(".jpg")}"], out.toByteArray())
        }
    }

    @Test fun `a damaged sealed file is not moved out and stays in the vault`() {
        runBlocking {
            unlock(); val album = store.createAlbum("A")
            val reader = FakeReader()
            mover(reader).moveIn(listOf(media(1, reader)), album)
            val item = store.observeItems(album).first().single()
            File(dir, "${item.fileName}.v").let { f -> val b = f.readBytes(); b[40] = (b[40].toInt() xor 1).toByte(); f.writeBytes(b) }
            val sink = MemorySink()
            val r = mover(reader).moveOut(listOf(item), sink)
            assertTrue(r.restored.isEmpty())
            assertEquals(1, r.failed.size)
            assertEquals(0, sink.committed)
            assertEquals("the broken half-copy was aborted", 1, sink.aborted)
            assertEquals("the sealed file is kept, not deleted", 1, db.privateVault().itemCount())
        }
    }

    @Test fun `without a preview maker the pictures still move and show from the full file`() {
        runBlocking {
            unlock(); val album = store.createAlbum("A")
            val reader = FakeReader()
            val r = mover(reader, thumbs = null).moveIn(listOf(media(1, reader)), album)
            assertEquals(1, r.moved.size)
            assertEquals(1, files().size)
            assertNull(store.thumbnailBytes(store.observeItems(album).first().single()))
        }
    }
}
