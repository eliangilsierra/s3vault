package com.s3vault.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.s3vault.app.auth.CredentialsRepository
import com.s3vault.app.data.local.AppDatabase
import com.s3vault.app.data.local.ExifThumbnailReader
import com.s3vault.app.data.local.MediaIndexer
import com.s3vault.app.data.local.MediaStoreGateway
import com.s3vault.app.data.local.MediaStoreSource
import com.s3vault.app.data.local.MediaStoreThumbnailMaker
import com.s3vault.app.data.local.PlatformMediaDelete
import com.s3vault.app.data.remote.S3RemoteStorage
import com.s3vault.app.domain.AuthMethod
import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteKeys
import com.s3vault.app.domain.S3Config
import com.s3vault.app.domain.SyncState
import com.s3vault.app.security.InMemorySecretStore
import com.s3vault.app.sync.BackupEngine
import com.s3vault.app.sync.CloudDeletionController
import com.s3vault.app.sync.DeleteEntry
import com.s3vault.app.sync.EventLog
import com.s3vault.app.sync.LocalDeletionController
import com.s3vault.app.sync.PreviewQuality
import com.s3vault.app.sync.PreviewResult
import com.s3vault.app.sync.RemoteCatalog
import com.s3vault.app.sync.RemoteContext
import com.s3vault.app.sync.RemoteContextProvider
import com.s3vault.app.sync.RemotePreviewRepository
import com.s3vault.app.sync.RunOutcome
import com.s3vault.app.sync.TransferProgress
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Previews and deletion on a real device/emulator: real MediaStore thumbnails, the AWS SDK for Kotlin on ART (including
 * the DeleteObjects wire format and Range requests) and Room, against an in-memory S3 stand-in over localhost.
 */
@RunWith(AndroidJUnit4::class)
class PreviewAndDeletionEndToEndTest {
    @get:Rule val perms: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO,
    )

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var s3: FakeS3Dispatcher
    private lateinit var storage: S3RemoteStorage
    private lateinit var cacheDir: File
    private val creds = CredentialsRepository(InMemorySecretStore()).also {
        it.saveStatic("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
    }
    private val source by lazy { MediaStoreSource(context) }
    private val inserted = mutableListOf<Uri>()
    private val keys = RemoteKeys("pv/")
    private val provider = RemoteContextProvider { RemoteContext(storage, keys) }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        s3 = FakeS3Dispatcher("test-bucket", allowedPrefix = "pv/")
        server = MockWebServer().also { it.dispatcher = s3; it.start() }
        storage = S3RemoteStorage(
            S3Config("eu-west-1", "test-bucket", "pv/", AuthMethod.STATIC), creds,
            endpointOverride = server.url("/").toString().trimEnd('/'),
        )
        cacheDir = File(context.cacheDir, "pv-previews-${System.nanoTime()}")
    }

    @After fun tearDown() {
        inserted.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        context.contentResolver.delete(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("pv_%"),
        )
        cacheDir.deleteRecursively()
        storage.close(); server.close(); db.close()
    }

    // ------------------------------------------------------------------ fixtures

    /** A large image with texture (like a real photo, unlike a flat gradient): hundreds of KB, far bigger than a preview. */
    private fun largeJpeg(w: Int = 2000, h: Int = 1500): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.RED, Color.BLUE, Shader.TileMode.CLAMP) }
            .let { Canvas(bmp).drawRect(0f, 0f, w.toFloat(), h.toFloat(), it) }
        val rnd = java.util.Random(42)
        val noise = Paint()
        val canvas = Canvas(bmp)
        repeat(w * h / 150) {
            noise.color = Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
            val x = rnd.nextInt(w).toFloat()
            val y = rnd.nextInt(h).toFloat()
            canvas.drawRect(x, y, x + 2 + rnd.nextInt(8), y + 2 + rnd.nextInt(8), noise)
        }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
    }

    /** A JPEG whose EXIF block carries a [thumb] thumbnail, built byte by byte (the platform cannot write one). */
    private fun jpegWithExifThumbnail(thumb: ByteArray, main: ByteArray): ByteArray {
        val tiff = ByteArrayOutputStream()
        fun le16(v: Int) { tiff.write(v and 0xFF); tiff.write((v shr 8) and 0xFF) }
        fun le32(v: Int) { le16(v and 0xFFFF); le16((v shr 16) and 0xFFFF) }
        fun entry(tag: Int, type: Int, value: Int) { le16(tag); le16(type); le32(1); le32(value) }
        tiff.write("II".toByteArray()); le16(0x2A); le32(8)
        le16(1); entry(0x0112, 3, 1); le32(26) // IFD0: orientation = normal, next IFD at 26
        le16(3); entry(0x0103, 3, 6); entry(0x0201, 4, 68); entry(0x0202, 4, thumb.size); le32(0) // IFD1: the thumbnail
        check(tiff.size() == 68)
        tiff.write(thumb)
        val payload = "Exif\u0000\u0000".toByteArray() + tiff.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(0xFF); out.write(0xD8) // SOI
        out.write(0xFF); out.write(0xE1); out.write((payload.size + 2) shr 8); out.write((payload.size + 2) and 0xFF)
        out.write(payload)
        out.write(main, 2, main.size - 2)
        return out.toByteArray()
    }

    private fun insertImage(name: String, bytes: ByteArray, mime: String = "image/jpeg"): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/S3VaultTest")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        inserted += uri
        return uri
    }

    private fun engine(withPreviews: Boolean) = BackupEngine(
        db, provider, creds, source, TransferProgress(), EventLog(db), RemoteCatalog(db),
        thumbnails = if (withPreviews) MediaStoreThumbnailMaker(context) else null,
    )

    private suspend fun indexAll() { MediaIndexer(db).reconcile(source.scan(), fullAccess = true) }
    private suspend fun item(name: String) = db.media().getEverything().first { it.displayName == name }

    private suspend fun backUp(name: String, withPreviews: Boolean = true) {
        indexAll()
        db.media().enqueue(item(name).mediaStoreId, 1L)
        val out = engine(withPreviews).run(1)
        assertTrue(out.toString(), out is RunOutcome.Completed)
        assertEquals(SyncState.BACKED_UP, item(name).syncState)
    }

    private fun repo() = RemotePreviewRepository(cacheDir, provider, ExifThumbnailReader())

    // ------------------------------------------------------------------ previews

    @Test fun backupUploadsASmallJpegPreviewNextToThePhoto() = runBlocking {
        val original = largeJpeg()
        insertImage("pv_big.jpg", original)

        backUp("pv_big.jpg")

        val row = item("pv_big.jpg")
        assertTrue(row.thumbnailUploaded)
        val thumb = s3.objects[keys.thumbnailKey(row.remoteVersionId!!)]
        assertNotNull("the preview object exists", thumb)
        assertEquals("image/jpeg", thumb!!.contentType)
        assertTrue("JPEG magic bytes", thumb.bytes[0] == 0xFF.toByte() && thumb.bytes[1] == 0xD8.toByte())
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(thumb.bytes, 0, thumb.bytes.size, opts)
        assertTrue("longest edge ${opts.outWidth}x${opts.outHeight} <= 720", maxOf(opts.outWidth, opts.outHeight) <= 720)
        assertTrue("and it is still a usable picture", maxOf(opts.outWidth, opts.outHeight) >= 256)
        assertTrue("preview ${thumb.bytes.size} B is far smaller than the original ${original.size} B", thumb.bytes.size * 4 < original.size)
        assertEquals("the preview is not a listed photo", 1, storage.listPhotos().size)
    }

    @Test fun anS3PhotoIsPreviewedWithoutDownloadingTheOriginal() = runBlocking {
        insertImage("pv_remote.jpg", largeJpeg())
        backUp("pv_remote.jpg")
        val originalKey = item("pv_remote.jpg").remoteKey!!
        // The photo leaves the device: from now on only S3 has it.
        context.contentResolver.delete(Uri.parse(item("pv_remote.jpg").uri), null, null)
        inserted.clear()
        indexAll()
        RemoteCatalog(db).refresh(RemoteContext(storage, keys))
        val obj = db.remote().get(originalKey)!!.toDomain(availableLocally = false)
        s3.getKeys.clear()

        val r = repo().load(obj) as PreviewResult.Ready

        assertEquals(PreviewQuality.PREVIEW, r.quality)
        assertNotNull("decodes as an image", BitmapFactory.decodeFile(r.file.path))
        assertEquals("only the preview object was fetched", listOf(keys.thumbnailKey(obj.versionId)), s3.getKeys.toList())
        assertFalse(s3.getKeys.contains(originalKey))

        val requests = s3.getKeys.size
        repo().load(obj) // a new repository over the same cache directory: served from disk
        assertEquals(requests, s3.getKeys.size)
    }

    @Test fun withoutAPreviewObjectAJpegsEmbeddedThumbnailIsReadWithOneRangedRequest() = runBlocking {
        val thumbBmp = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val thumb = ByteArrayOutputStream().also { thumbBmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        val original = jpegWithExifThumbnail(thumb, largeJpeg(1800, 1200))
        assertTrue("the original is much bigger than the range we read", original.size > RemotePreviewRepository.HEAD_BYTES * 2)
        insertImage("pv_exif.jpg", original)
        backUp("pv_exif.jpg", withPreviews = false) // an "old" backup: no preview object was ever uploaded
        val originalKey = item("pv_exif.jpg").remoteKey!!
        RemoteCatalog(db).refresh(RemoteContext(storage, keys))
        val obj = db.remote().get(originalKey)!!.toDomain(availableLocally = true)
        s3.getKeys.clear(); s3.rangeHeaders.clear()

        val r = repo().load(obj) as PreviewResult.Ready

        assertEquals(PreviewQuality.EMBEDDED, r.quality)
        val bmp = BitmapFactory.decodeFile(r.file.path)
        assertEquals("the embedded thumbnail itself", 48 to 32, bmp.width to bmp.height)
        assertEquals(listOf("bytes=0-${RemotePreviewRepository.HEAD_BYTES - 1}"), s3.rangeHeaders.toList())
        assertEquals(listOf(keys.thumbnailKey(obj.versionId), originalKey), s3.getKeys.toList())
    }

    @Test fun aPhotoWithNeitherPreviewNorEmbeddedThumbnailIsReportedAsUnavailable() = runBlocking {
        insertImage("pv_plain.jpg", largeJpeg(800, 600)) // Bitmap.compress writes no EXIF thumbnail
        backUp("pv_plain.jpg", withPreviews = false)
        RemoteCatalog(db).refresh(RemoteContext(storage, keys))
        val obj = db.remote().get(item("pv_plain.jpg").remoteKey!!)!!.toDomain(true)

        assertEquals(PreviewResult.Unavailable, repo().load(obj))
        val requests = s3.getKeys.size
        assertEquals(PreviewResult.Unavailable, repo().load(obj))
        assertEquals("the miss is remembered", requests, s3.getKeys.size)
    }

    @Test fun backfillCreatesThePreviewsOfOlderBackupsFromTheDeviceCopy() = runBlocking {
        insertImage("pv_old.jpg", largeJpeg(1200, 900))
        backUp("pv_old.jpg", withPreviews = false)
        val v = item("pv_old.jpg").remoteVersionId!!
        assertFalse(s3.objects.containsKey(keys.thumbnailKey(v)))
        assertEquals(1, db.media().thumbnailBacklog(10).size)

        assertEquals(1, engine(withPreviews = true).backfillPreviews())

        assertTrue(s3.objects.containsKey(keys.thumbnailKey(v)))
        assertEquals(0, db.media().thumbnailBacklog(10).size)
    }

    // ------------------------------------------------------------------ deletion

    @Test fun deletingFromS3RemovesTheObjectAndItsPreviewButKeepsTheLocalFile() = runBlocking {
        insertImage("pv_keep.jpg", largeJpeg(1000, 800))
        backUp("pv_keep.jpg")
        val key = item("pv_keep.jpg").remoteKey!!
        val v = item("pv_keep.jpg").remoteVersionId!!
        RemoteCatalog(db).refresh(RemoteContext(storage, keys))
        assertTrue(s3.objects.containsKey(key) && s3.objects.containsKey(keys.thumbnailKey(v)))

        val r = CloudDeletionController(db, provider).delete(listOf(key))

        assertEquals(listOf(key), r.deleted)
        assertTrue(r.failed.isEmpty())
        assertTrue("both objects are gone from the bucket", s3.objects.isEmpty())
        assertEquals("one request for the photo, one for the shared preview", 2, s3.deleteBatches.size)
        val row = item("pv_keep.jpg")
        assertEquals(SyncState.LOCAL_ONLY, row.syncState)
        assertTrue(row.backupExcluded)
        assertNull(row.remoteKey)
        assertTrue("the file is still on the device", MediaStoreGateway(context).exists(row.uri))
        assertNull(db.remote().get(key))
    }

    @Test fun anIamPolicyWithoutDeletePermissionIsReportedAndNothingChanges() = runBlocking {
        insertImage("pv_denied.jpg", largeJpeg(600, 400))
        backUp("pv_denied.jpg")
        val key = item("pv_denied.jpg").remoteKey!!
        RemoteCatalog(db).refresh(RemoteContext(storage, keys))
        s3.allowDelete = false

        val r = CloudDeletionController(db, provider).delete(listOf(key))

        assertTrue(r.deleted.isEmpty())
        assertEquals(RemoteErrorKind.ACCESS_DENIED, r.failed[key])
        assertTrue(s3.objects.containsKey(key))
        assertEquals(SyncState.BACKED_UP, item("pv_denied.jpg").syncState)
        assertNotNull(db.remote().get(key))
    }

    @Test fun aFileDeletedOnTheDeviceIsSettledFromMediaStoreAndS3IsUntouched() = runBlocking {
        val uri = insertImage("pv_local.jpg", largeJpeg(640, 480))
        backUp("pv_local.jpg")
        val row = item("pv_local.jpg")
        val gateway = MediaStoreGateway(context)
        assertTrue(gateway.exists(row.uri))
        val objectsBefore = s3.objects.keys.toSet()

        // The app created this file, so Android lets it delete it without asking; for other apps' files the same call
        // is routed through the system dialog (createDeleteRequest / RecoverableSecurityException).
        assertEquals(PlatformMediaDelete.Direct.Deleted, PlatformMediaDelete.deleteDirect(context.contentResolver, uri))
        inserted.clear()
        assertFalse(gateway.exists(row.uri))

        val r = LocalDeletionController(db, gateway).settle(
            listOf(DeleteEntry(row.displayName, row.mediaStoreId, row.uri, row.remoteKey, hasLocalCopy = true)),
        )

        assertEquals(1, r.deleted); assertEquals(1, r.keptInCloud); assertEquals(0, r.lost); assertEquals(0, r.remaining)
        val after = item("pv_local.jpg")
        assertEquals(SyncState.REMOTE_ONLY, after.syncState)
        assertFalse(after.present)
        assertEquals(objectsBefore, s3.objects.keys.toSet())
        assertTrue("no delete request was ever sent for a local deletion", s3.deleteBatches.isEmpty())
    }

    @Test fun theNewS3OperationsAreSafeToCallFromTheMainThread() = runBlocking {
        // ViewModels call storage from Dispatchers.Main; Android throws NetworkOnMainThreadException otherwise.
        val version = "9".repeat(64)
        val photoKey = keys.photoKey("a".repeat(32), version, "m.jpg")
        s3.objects[photoKey] = FakeS3Dispatcher.Obj(ByteArray(10), "", "image/jpeg", emptyMap())
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            assertTrue(android.os.Looper.myLooper() === android.os.Looper.getMainLooper())
            val local = S3RemoteStorage(
                S3Config("eu-west-1", "test-bucket", "pv/", AuthMethod.STATIC), creds,
                endpointOverride = server.url("/").toString().trimEnd('/'),
            )
            local.putThumbnail(keys.thumbnailKey(version), ByteArray(20) { 1 })
            assertEquals(20, local.readBytes(keys.thumbnailKey(version))!!.size)
            assertEquals(4, local.readBytes(photoKey, maxBytes = 4)!!.size)
            assertEquals(listOf(photoKey), local.delete(listOf(photoKey)).deleted)
            local.close()
        }
        assertFalse(s3.objects.containsKey(photoKey))
    }

    @Test fun androidOffersABatchDeleteRequestForSeveralFiles() {
        if (!PlatformMediaDelete.supportsBatchRequest) return
        val a = insertImage("pv_a.jpg", largeJpeg(100, 100))
        val b = insertImage("pv_b.jpg", largeJpeg(100, 100))
        assertNotNull(PlatformMediaDelete.batchRequest(context, listOf(a, b)))
        // Creating the request deletes nothing: only the system dialog's confirmation does.
        assertTrue(MediaStoreGateway(context).let { g -> runBlocking { g.exists(a.toString()) && g.exists(b.toString()) } })
    }
}
