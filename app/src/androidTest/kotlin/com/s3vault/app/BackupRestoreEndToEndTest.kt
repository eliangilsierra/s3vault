package com.s3vault.app

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.s3vault.app.auth.CredentialsRepository
import com.s3vault.app.data.local.AppDatabase
import com.s3vault.app.data.local.MediaIndexer
import com.s3vault.app.data.local.MediaStoreSource
import com.s3vault.app.data.remote.S3RemoteStorage
import com.s3vault.app.domain.AuthMethod
import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
import com.s3vault.app.domain.RemoteKeys
import com.s3vault.app.domain.S3Config
import com.s3vault.app.domain.SyncState
import com.s3vault.app.security.InMemorySecretStore
import com.s3vault.app.sync.BackupEngine
import com.s3vault.app.sync.EventLog
import com.s3vault.app.sync.RemoteCatalog
import com.s3vault.app.sync.RemoteContext
import com.s3vault.app.sync.RemoteContextProvider
import com.s3vault.app.sync.RestoreEngine
import com.s3vault.app.sync.RestoreOutcome
import com.s3vault.app.sync.RunOutcome
import com.s3vault.app.sync.TransferProgress
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Real device/emulator run: MediaStore + Room + the AWS SDK for Kotlin on ART + our engines, against an
 * in-memory S3 stand-in over localhost (cleartext allowed only in the debug network config).
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreEndToEndTest {
    @get:Rule val perms: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO,
    )

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var s3: FakeS3Dispatcher
    private lateinit var storage: S3RemoteStorage
    private val creds = CredentialsRepository(InMemorySecretStore()).also {
        it.saveStatic("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
    }
    private val source by lazy { MediaStoreSource(context) }
    private val inserted = mutableListOf<Uri>()
    private val keys = RemoteKeys("e2e/")

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        s3 = FakeS3Dispatcher("test-bucket", allowedPrefix = "e2e/")
        server = MockWebServer().also { it.dispatcher = s3; it.start() }
        storage = S3RemoteStorage(
            S3Config("eu-west-1", "test-bucket", "e2e/", AuthMethod.STATIC), creds,
            endpointOverride = server.url("/").toString().trimEnd('/'),
        )
    }

    @After fun tearDown() {
        inserted.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        // also remove anything restored into Pictures/S3Vault by this test
        context.contentResolver.delete(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("e2e_%", "Pictures/S3VaultTest%"),
        )
        storage.close(); server.close(); db.close()
    }

    private fun insertImage(name: String, color: Int, size: Int = 64): Uri {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val baos = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/S3VaultTest")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { it.write(baos.toByteArray()) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        inserted += uri
        return uri
    }

    private fun engine() = BackupEngine(
        db, RemoteContextProvider { RemoteContext(storage, keys) }, creds, source, TransferProgress(), EventLog(db), RemoteCatalog(db),
    )

    private suspend fun indexAll() {
        MediaIndexer(db).reconcile(source.scan(), fullAccess = true)
    }

    private suspend fun testItems() = db.media().getEverything().filter { it.displayName.startsWith("e2e_") }

    @Test fun backupVerifyDeleteLocalThenRestore() = runBlocking {
        val uriA = insertImage("e2e_red.png", Color.RED)
        insertImage("e2e_blue.png", Color.BLUE, size = 96)

        // --- Local gallery index (MediaStore -> Room)
        indexAll()
        val items = testItems()
        assertEquals(2, items.size)
        assertTrue(items.all { it.syncState == SyncState.LOCAL_ONLY && it.contentSha256 == null })

        // --- Backup through the real SDK client
        items.forEach { db.media().enqueue(it.mediaStoreId, 1L) }
        val out = engine().run(2)
        assertTrue(out.toString(), out is RunOutcome.Completed)
        val backed = testItems()
        assertTrue(backed.all { it.syncState == SyncState.BACKED_UP })
        assertEquals(2, s3.objects.size)
        assertTrue(s3.objects.keys.all { it.startsWith("e2e/photos/") })

        // Stored bytes == the local file bytes
        val red = backed.first { it.displayName == "e2e_red.png" }
        val localBytes = context.contentResolver.openInputStream(Uri.parse(red.uri))!!.use { it.readBytes() }
        val remote = s3.objects[red.remoteKey]!!
        assertTrue(localBytes.contentEquals(remote.bytes))
        assertEquals(red.contentSha256, MessageDigest.getInstance("SHA-256").digest(localBytes).joinToString("") { "%02x".format(it) })

        // --- Second run uploads nothing (no unnecessary transfers)
        val puts = s3.methods.count { it == "PUT" }
        db.media().enqueue(red.mediaStoreId, 2L)
        engine().run(2)
        assertEquals(puts, s3.methods.count { it == "PUT" })

        // --- User deletes the photo from the device: S3 keeps it, no DELETE is ever issued
        context.contentResolver.delete(uriA, null, null)
        inserted.remove(uriA)
        indexAll()
        val afterDelete = testItems().first { it.displayName == "e2e_red.png" }
        assertEquals(SyncState.REMOTE_ONLY, afterDelete.syncState)
        assertFalse(afterDelete.present)
        assertTrue(s3.objects.containsKey(red.remoteKey))
        assertFalse(s3.methods.contains("DELETE"))

        // --- Browse the remote library and restore it
        RemoteCatalog(db).refresh(RemoteContext(storage, keys))
        val remoteRow = db.remote().get(red.remoteKey!!)!!
        assertEquals("e2e_red.png", remoteRow.displayName)
        db.remote().transition(remoteRow.key, SyncState.REMOTE_ONLY, SyncState.RESTORING, null)
        val restore = RestoreEngine(
            db, RemoteContextProvider { RemoteContext(storage, keys) }, creds, com.s3vault.app.MediaStoreRestoreSink(context),
            File(context.cacheDir, "restore-test"), TransferProgress(), EventLog(db),
        )
        assertEquals(RestoreOutcome.Done(1, 0), restore.run())
        val restoredUri = db.remote().get(remoteRow.key)!!.restoredUri
        assertNotNull(restoredUri)
        inserted += Uri.parse(restoredUri)
        val restoredBytes = context.contentResolver.openInputStream(Uri.parse(restoredUri))!!.use { it.readBytes() }
        assertTrue("restored file is byte-identical", restoredBytes.contentEquals(localBytes))

        // --- Restoring again never overwrites: MediaStore creates a distinct file
        db.remote().transition(remoteRow.key, SyncState.REMOTE_ONLY, SyncState.RESTORING, null)
        assertEquals(RestoreOutcome.Done(1, 0), restore.run())
        val second = db.remote().get(remoteRow.key)!!.restoredUri!!
        inserted += Uri.parse(second)
        assertTrue("second restore must be a different file", second != restoredUri)
        assertTrue(context.contentResolver.openInputStream(Uri.parse(restoredUri))!!.use { it.readBytes() }.contentEquals(localBytes))
    }

    @Test fun s3CallsFromTheMainThreadNeverTouchTheNetworkOnIt() = runBlocking {
        // ViewModels call the storage from Dispatchers.Main; Android throws NetworkOnMainThreadException otherwise.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            assertTrue(android.os.Looper.myLooper() === android.os.Looper.getMainLooper())
            val local = S3RemoteStorage(
                S3Config("eu-west-1", "test-bucket", "e2e/", AuthMethod.STATIC), creds,
                endpointOverride = server.url("/").toString().trimEnd('/'),
            )
            local.checkConnection()
            assertTrue(local.testPermissions().allAllowed)
            assertEquals(0, local.listPhotos().size)
            local.close() // closing sockets must not hit the main thread either
        }
    }

    @Test fun permissionsAndErrorsAreClassifiedOnTheRealStack() = runBlocking {
        storage.checkConnection() // list under prefix works
        val report = storage.testPermissions()
        assertTrue(report.checks.toString(), report.allAllowed)

        s3.denyAll = true
        val e = runCatching { storage.checkConnection() }.exceptionOrNull() as RemoteException
        assertEquals(RemoteErrorKind.ACCESS_DENIED, e.kind)
        s3.denyAll = false

        val wrongBucket = S3RemoteStorage(
            S3Config("eu-west-1", "other-bucket", "e2e/", AuthMethod.STATIC), creds,
            endpointOverride = server.url("/").toString().trimEnd('/'),
        )
        val e2 = runCatching { wrongBucket.checkConnection() }.exceptionOrNull() as RemoteException
        assertEquals(RemoteErrorKind.BUCKET_NOT_FOUND, e2.kind)
        wrongBucket.close()
    }

    private class TestClock(var now: java.time.Instant) : java.time.Clock() {
        override fun getZone() = java.time.ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): java.time.Instant = now
    }

    @Test fun expiredCredentialsStopTheTransferAndKeepTheQueue() = runBlocking {
        insertImage("e2e_green.png", Color.GREEN)
        indexAll()
        val item = testItems().single()
        db.media().enqueue(item.mediaStoreId, 1L)

        val clock = TestClock(java.time.Instant.parse("2026-10-22T12:00:00Z"))
        val temp = CredentialsRepository(InMemorySecretStore(), clock)
        temp.saveTemporary("ASIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "tokentokentokentoken", clock.now.plusSeconds(3600))
        val st = S3RemoteStorage(
            S3Config("eu-west-1", "test-bucket", "e2e/", AuthMethod.TEMPORARY), temp,
            endpointOverride = server.url("/").toString().trimEnd('/'),
        )
        fun engineWith() = BackupEngine(
            db, RemoteContextProvider { RemoteContext(st, keys) }, temp, source, TransferProgress(), EventLog(db), RemoteCatalog(db),
        )

        clock.now = clock.now.plusSeconds(7200) // the session expired
        val outcome = engineWith().run(1)
        assertTrue(outcome.toString(), outcome is RunOutcome.NeedsCredentials)
        val after = testItems().single()
        assertEquals(SyncState.QUEUED, after.syncState)
        assertEquals(0, s3.objects.size)
        assertEquals(0, server.requestCount)

        // New credentials arrive: the queued work completes
        clock.now = java.time.Instant.parse("2026-10-22T12:00:00Z")
        temp.saveTemporary("ASIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "tokentokentokentoken", clock.now.plusSeconds(3600))
        assertTrue(engineWith().run(1) is RunOutcome.Completed)
        assertEquals(SyncState.BACKED_UP, testItems().single().syncState)
        st.close()
    }
}
