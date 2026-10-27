package com.s3vault.app

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.net.TrafficStats
import android.os.Process
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.s3vault.app.auth.CredentialsRepository
import com.s3vault.app.data.faces.AndroidFaceImageSource
import com.s3vault.app.data.faces.FaceCrops
import com.s3vault.app.data.faces.FaceModels
import com.s3vault.app.data.faces.ImageLoad
import com.s3vault.app.data.local.AppDatabase
import com.s3vault.app.data.local.FacePlace
import com.s3vault.app.data.local.MediaIndexer
import com.s3vault.app.data.local.MediaStoreSource
import com.s3vault.app.data.remote.S3RemoteStorage
import com.s3vault.app.domain.AuthMethod
import com.s3vault.app.domain.RemoteKeys
import com.s3vault.app.domain.S3Config
import com.s3vault.app.domain.SyncState
import com.s3vault.app.domain.faces.AesGcmFaceVault
import com.s3vault.app.domain.faces.FaceAnalyzer
import com.s3vault.app.domain.faces.FaceMath
import com.s3vault.app.domain.faces.FacePipeline
import com.s3vault.app.domain.faces.MemoryDataKeyStore
import com.s3vault.app.domain.faces.RulePolicy
import com.s3vault.app.domain.faces.WorkingImage
import com.s3vault.app.security.InMemorySecretStore
import com.s3vault.app.settings.AppSettings
import com.s3vault.app.sync.BackupController
import com.s3vault.app.sync.BackupEngine
import com.s3vault.app.sync.EventLog
import com.s3vault.app.sync.FaceBackupService
import com.s3vault.app.sync.FaceEngineProvider
import com.s3vault.app.sync.FaceGroupingStore
import com.s3vault.app.sync.FaceIndexLock
import com.s3vault.app.sync.FaceIndexer
import com.s3vault.app.sync.FacePeopleController
import com.s3vault.app.sync.FaceRunOutcome
import com.s3vault.app.sync.JobRecorder
import com.s3vault.app.sync.RemoteCatalog
import com.s3vault.app.sync.RemoteContext
import com.s3vault.app.sync.RemoteContextProvider
import com.s3vault.app.sync.RuleResult
import com.s3vault.app.sync.RunOutcome
import com.s3vault.app.sync.TransferProgress
import com.s3vault.app.sync.WorkScheduler
import com.s3vault.app.settings.BackupSettings
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** A scheduler that only counts: the real one would wake the app's own workers. */
private class QuietScheduler : WorkScheduler {
    var backupKicks = 0
    override fun kickBackup(wifiOnly: Boolean) { backupKicks++ }
    override fun cancelBackup() {}
    override fun configureAuto(settings: BackupSettings) {}
    override fun kickRestore(wifiOnly: Boolean) {}
    override fun cancelRestore() {}
    override fun kickCloudDates(wifiOnly: Boolean) {}
    override fun cancelCloudDates() {}
    override fun kickAudit(deep: Boolean, wifiOnly: Boolean, reuseListing: Boolean) {}
    override fun cancelAudit() {}
    override fun kickDuplicateScan(continuation: Boolean) {}
    override fun cancelDuplicateScan() {}
    override fun kickSemanticIndex(onlyCharging: Boolean, continuation: Boolean) {}
    override fun cancelSemanticIndex() {}
    override fun configureSemanticSchedule(enabled: Boolean, onlyCharging: Boolean) {}
    override fun kickFaceIndex(onlyCharging: Boolean, continuation: Boolean) {}
    override fun cancelFaceIndex() {}
    override fun configureFaceSchedule(enabled: Boolean, onlyCharging: Boolean) {}
    override fun kickModelDownload(wifiOnly: Boolean) {}
    override fun cancelModelDownload() {}
    override fun configureAuditSchedule(days: Int, wifiOnly: Boolean) {}
}

/**
 * The face pipeline on a real Android runtime: the bundled models in ONNX Runtime for Android, the platform image decoder with
 * EXIF orientation, MediaStore, Room, the real backup engine and an S3 stand-in over localhost. Public-domain photographs only
 * (see app/src/test/resources/faces/ATTRIBUTION.md); no personal photo and no real bucket are involved.
 */
@RunWith(AndroidJUnit4::class)
class FaceDeviceTest {
    @get:Rule val perms: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val inserted = mutableListOf<Uri>()
    private val files = mutableListOf<File>()
    private lateinit var analyzer: FacePipeline

    private fun photoBytes(name: String) = instrumentation.context.assets.open("faces/$name.jpg").use { it.readBytes() }

    private fun photoUri(name: String): String {
        val f = File(context.cacheDir, "face-test-$name.jpg").also { it.writeBytes(photoBytes(name)); files += it }
        return Uri.fromFile(f).toString()
    }

    private fun load(uri: String): WorkingImage = runBlocking { (AndroidFaceImageSource(context).load(uri) as ImageLoad.Ok).image }

    @Before fun setUp() {
        val engine = runBlocking { FaceModels.open(context, threadsPerSession = 1) }
        assertNotNull("the bundled models load on this device", engine)
        analyzer = FacePipeline(engine!!)
    }

    @After fun tearDown() {
        analyzer.close()
        inserted.forEach { BulkPerfSupport.delete(it) }
        files.forEach { it.delete() }
    }

    // ------------------------------------------------------------------ the models on a device

    @Test fun theModelsFindTheSamePersonAndTellPeopleApart() {
        fun vec(name: String) = analyzer.analyze(load(photoUri(name))).also { assertEquals("faces in $name", 1, it.faces.size) }.faces[0].vector
        val people = mapOf("collins" to 1..3, "ride" to 1..3, "glenn" to 1..3)
        val all = people.flatMap { (who, r) -> r.map { who to vec("${who}_$it") } }
        var worstOther = -1f
        for (i in all.indices) for (j in i + 1 until all.size) if (all[i].first != all[j].first) worstOther = maxOf(worstOther, FaceMath.dot(all[i].second, all[j].second))
        for ((who, r) in people) {
            val best = r.flatMap { a -> r.filter { it > a }.map { b -> FaceMath.dot(vec("${who}_$a"), vec("${who}_$b")) } }.max()
            assertTrue("$who: best same-person pair $best vs worst different people $worstOther", best > worstOther + 0.08f)
        }
        val crew = analyzer.analyze(load(photoUri("crew_sts93")))
        assertEquals(5, crew.faces.size + crew.skipped)
        val refs = (1..3).map { vec("collins_$it") }
        assertEquals(1, crew.faces.count { f -> refs.maxOf { FaceMath.dot(f.vector, it) } >= 0.5f })
    }

    @Test fun aPictureStoredSidewaysWithAnOrientationTagIsAnalysedUpright() {
        val upright = load(photoUri("collins_1"))
        val reference = analyzer.analyze(upright).faces.single()
        // Store the picture rotated a quarter turn the other way and tell Exif to rotate it back, as a phone does when held sideways.
        val bmp = Bitmap.createBitmap(upright.argb, upright.width, upright.height, Bitmap.Config.ARGB_8888)
        val sideways = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(-90f) }, true)
        val f = File(context.cacheDir, "face-test-sideways.jpg").also { files += it }
        f.outputStream().use { sideways.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        ExifInterface(f.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }
        val decoded = load(Uri.fromFile(f).toString())
        assertEquals("decoded upright", upright.width, decoded.width)
        val found = analyzer.analyze(decoded).faces.single()
        assertTrue("the same face in the same place: ${reference.box.toList()} vs ${found.box.toList()}", (0..3).all { kotlin.math.abs(reference.box[it] - found.box[it]) < 0.04f })
        assertTrue(FaceMath.dot(reference.vector, found.vector) > 0.9f)
        // And the crop the screens draw is the face, not a corner of the photo: the detector finds a face in it.
        val place = FacePlace(1, 1, Uri.fromFile(f).toString(), found.box[0], found.box[1], found.box[2], found.box[3])
        val crop = runBlocking { FaceCrops(context).get(place) }
        assertNotNull(crop)
        assertTrue("crop ${crop!!.width}x${crop.height}", crop.width in 100..FaceCrops.SIZE + 40 && crop.height in 100..FaceCrops.SIZE + 40)
        val pixels = IntArray(crop.width * crop.height).also { crop.getPixels(it, 0, crop.width, 0, 0, crop.width, crop.height) }
        val inCrop = analyzer.analyze(WorkingImage(pixels, crop.width, crop.height))
        assertEquals("one face in the crop", 1, inCrop.faces.size + inCrop.skipped)
    }

    @Test fun anUnreadableFileIsReportedNotThrown() {
        val bad = File(context.cacheDir, "face-test-bad.jpg").also { it.writeBytes(ByteArray(300) { 7 }); files += it }
        assertEquals(ImageLoad.Unreadable, runBlocking { AndroidFaceImageSource(context).load(Uri.fromFile(bad).toString()) })
        assertEquals(ImageLoad.Unavailable, runBlocking { AndroidFaceImageSource(context).load("file:///no/such/file.jpg") })
    }

    @Test fun theAnalysisUsesNoNetworkAndRecordsItsCost() {
        val uris = (1..3).flatMap { listOf("collins_$it", "ride_$it", "glenn_$it") } + listOf("crew_sts93", "press_four")
        val paths = uris.map { photoUri(it) }
        val uid = Process.myUid()
        val tx = TrafficStats.getUidTxBytes(uid); val rx = TrafficStats.getUidRxBytes(uid)
        assumeTrue("this device does not report per-app traffic", tx != TrafficStats.UNSUPPORTED.toLong() && rx != TrafficStats.UNSUPPORTED.toLong())
        analyzer.analyze(load(paths[0])) // warm up the runtime
        val runtime = Runtime.getRuntime()
        System.gc()
        val heapBefore = runtime.totalMemory() - runtime.freeMemory()
        val t0 = System.nanoTime()
        var faces = 0
        for (p in paths) faces += analyzer.analyze(load(p)).faces.size
        val ms = (System.nanoTime() - t0) / 1e6 / paths.size
        val heapAfter = runtime.totalMemory() - runtime.freeMemory()
        val sentBytes = TrafficStats.getUidTxBytes(uid) - tx; val receivedBytes = TrafficStats.getUidRxBytes(uid) - rx
        File(context.getExternalFilesDir(null), "face-perf.txt").writeText(
            "photos=${paths.size} faces=$faces msPerPhoto=%.1f abi=${android.os.Build.SUPPORTED_ABIS.first()} heapDeltaMB=%.1f sentBytes=$sentBytes receivedBytes=$receivedBytes\n".format(ms, (heapAfter - heapBefore) / 1048576.0),
        )
        assertEquals("the analysis sent nothing", 0L, sentBytes)
        assertEquals("the analysis received nothing", 0L, receivedBytes)
    }

    // ------------------------------------------------------------------ the whole path, with real photos and a fake S3

    @Test fun aRulePutsOnlyThatPersonsPhotosInTheExistingQueueAndTheEngineUploadsThemWhole() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val server = MockWebServer()
        val s3 = FakeS3Dispatcher("test-bucket", allowedPrefix = "e2e/")
        server.dispatcher = s3; server.start()
        val creds = CredentialsRepository(InMemorySecretStore()).also { it.saveStatic("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY") }
        val storage = S3RemoteStorage(S3Config("eu-west-1", "test-bucket", "e2e/", AuthMethod.STATIC), creds, endpointOverride = server.url("/").toString().trimEnd('/'))
        val keys = RemoteKeys("e2e/")
        try {
            val names = listOf("collins_1", "collins_2", "collins_3", "ride_1", "ride_2", "glenn_1", "glenn_2", "crew_sts93")
            names.forEach { inserted += BulkPerfSupport.insertBytes("face_e2e_$it.jpg", photoBytes(it), "Pictures/S3VaultFaceTest") }
            MediaIndexer(db).reconcile(MediaStoreSource(context).scan().filter { it.folderName.contains("S3VaultFaceTest") }, fullAccess = false)

            val vault = AesGcmFaceVault(MemoryDataKeyStore())
            val grouping = FaceGroupingStore(db, vault)
            val lock = FaceIndexLock()
            val jobs = JobRecorder(db)
            val settings = AppSettings(context)
            val scheduler = QuietScheduler()
            val backup = BackupController(db, settings, scheduler)
            val rules = FaceBackupService(db, backup, settings, grouping, lock, jobs, s3Configured = { true })
            val indexer = FaceIndexer(db, vault, FaceEngineProvider { FacePipeline(FaceModels.open(context, 1)!!) }, AndroidFaceImageSource(context), grouping, lock, jobs, { rules.evaluateAll() })

            val out = indexer.run(maxPhotos = 100) as FaceRunOutcome.Done
            assertEquals(8, out.analyzed)
            val faces = db.faces().pool() + db.faces().people().flatMap { db.faces().membersOfAll(it.id) }
            assertTrue("faces were found in the photos: ${faces.size}", faces.size >= 11)

            // The user makes a person out of the three portraits of one woman, choosing them in "faces without a group".
            val collinsMedia = db.media().getEverything().filter { it.displayName.startsWith("face_e2e_collins") }.map { it.mediaStoreId }
            assertEquals(3, collinsMedia.size)
            val collinsFaces = collinsMedia.map { db.faces().facesOfPhoto(it).single().id }
            val people = FacePeopleController(db, grouping, vault, lock)
            val moved = people.moveFaces(collinsFaces, null)
            assertEquals(3, moved.moved)
            val person = moved.targetId!!

            val preview = rules.preview(person, includeExisting = true, RulePolicy.STRICT)
            assertEquals("the three portraits would be queued (the crew photo also has her, but only as a suggestion at best)", setOf(collinsMedia.toSet()).single(), preview.willQueue.map { it.mediaStoreId }.toSet().intersect(collinsMedia.toSet()))
            val result = rules.activate(person, includeExisting = true, RulePolicy.STRICT, emptySet())
            assertTrue("$result", result is RuleResult.Applied)

            val queued = db.media().getEverything().filter { it.syncState == SyncState.QUEUED }.map { it.displayName }.toSet()
            assertTrue("only her photos are queued: $queued", queued.all { it.startsWith("face_e2e_collins") || it.startsWith("face_e2e_crew") })
            assertTrue(queued.containsAll(listOf("face_e2e_collins_1.jpg", "face_e2e_collins_2.jpg", "face_e2e_collins_3.jpg")))
            assertTrue("nobody else's photo is queued", queued.none { it.contains("ride") || it.contains("glenn") })

            // The existing engine uploads them: whole files, under the prefix, with nothing about faces.
            val engine = BackupEngine(db, RemoteContextProvider { RemoteContext(storage, keys) }, creds, MediaStoreSource(context), TransferProgress(), EventLog(db), RemoteCatalog(db))
            val outcome = engine.run(maxConcurrency = 2)
            assertTrue("$outcome", outcome is RunOutcome.Completed && outcome.uploaded == queued.size)
            assertEquals(queued.size, s3.objects.size)
            assertTrue(s3.objects.keys.all { it.startsWith("e2e/photos/") })
            val sentHashes = s3.objects.values.map { sha(it.bytes) }.toSet()
            val originals = listOf("collins_1", "collins_2", "collins_3", "crew_sts93").map { sha(photoBytes(it)) }.toSet()
            assertTrue("every object is exactly one of the original photos", originals.containsAll(sentHashes))
            assertFalse("no request carried the sealed vectors", db.faces().sampleSealed().any { sealed -> s3.objects.values.any { String(it.bytes, Charsets.ISO_8859_1).contains(String(sealed, Charsets.ISO_8859_1)) } })
            assertTrue("nothing was deleted in S3", s3.methods.none { it == "DELETE" } && s3.deleteBatches.isEmpty())

            // The rule has nothing left to do, and turning it off or deleting the person leaves the backups exactly where they are.
            assertEquals(RuleResult.Applied(0, 0), rules.evaluate(person))
            rules.deactivate(person); people.deletePerson(person)
            assertEquals(queued.size, s3.objects.size)
            assertEquals(names.size, db.media().getEverything().count { it.present })
        } finally {
            storage.close(); server.close(); db.close()
        }
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
