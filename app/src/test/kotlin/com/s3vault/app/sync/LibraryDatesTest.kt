package com.s3vault.app.sync

import com.s3vault.app.data.local.RemoteObjectEntity
import com.s3vault.app.data.local.ScannedMedia
import com.s3vault.app.domain.MediaKind
import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
import com.s3vault.app.domain.SyncState
import com.s3vault.app.domain.library.DateKeys
import com.s3vault.app.domain.library.DateResolver
import com.s3vault.app.domain.library.DateSource
import com.s3vault.app.settings.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

private val UTC: ZoneId = ZoneId.of("UTC")
private val NOW = Instant.parse("2026-10-23T12:00:00Z").toEpochMilli()
private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

class DateResolverTest {
    @Test fun `local date prefers a believable capture date`() {
        val r = DateResolver.local(ms("2024-03-12T10:00:00Z"), 1, 1, "IMG_20200101.jpg", NOW, UTC)
        assertEquals(DateSource.CAPTURE, r.source)
        assertEquals(ms("2024-03-12T10:00:00Z"), r.ms)
    }

    @Test fun `a zero or absurd capture date falls back to the file name`() {
        val r = DateResolver.local(0, 1_700_000_000, 1_700_000_000, "IMG_20240312_101530.jpg", NOW, UTC)
        assertEquals(DateSource.FILENAME, r.source)
        assertEquals(ms("2024-03-12T10:15:30Z"), r.ms)
        assertEquals(DateSource.FILENAME, DateResolver.local(ms("1970-01-02T00:00:00Z"), 1, 1, "Screenshot_2024-03-12-10-15-30.png", NOW, UTC).source)
        assertEquals("a capture date in the far future is a broken clock", DateSource.FILENAME, DateResolver.local(NOW + 400L * 86_400_000, 1, 1, "IMG_20240312_101530.jpg", NOW, UTC).source)
    }

    @Test fun `without a capture date or a dated name the file dates are used and flagged`() {
        val modified = ms("2025-05-05T05:05:05Z") / 1000
        val r = DateResolver.local(0, modified, 1, "download (3).jpg", NOW, UTC)
        assertEquals(DateSource.FALLBACK, r.source)
        assertEquals(modified * 1000, r.ms)
    }

    @Test fun `an implausible modified date uses the added date, and nothing believable still yields a date`() {
        val added = ms("2025-06-06T06:06:06Z") / 1000
        assertEquals(added * 1000, DateResolver.local(0, 0, added, "x.jpg", NOW, UTC).ms)
        val nothing = DateResolver.local(0, 0, 0, "x.jpg", NOW, UTC)
        assertEquals(DateSource.FALLBACK, nothing.source)
        assertEquals("the clock is the last resort", NOW, nothing.ms)
    }

    @Test fun `remote objects never use the upload time as a capture date`() {
        val upload = ms("2026-10-01T10:00:00Z")
        val none = DateResolver.remote(null, "photo.jpg", upload, NOW, UTC)
        assertEquals(DateSource.FALLBACK, none.source)
        assertEquals(0, DateResolver.remoteDayKey(none, UTC))
        val named = DateResolver.remote(null, "IMG_20240312_101530.jpg", upload, NOW, UTC)
        assertEquals(DateSource.FILENAME, named.source)
        assertEquals(20240312, DateResolver.remoteDayKey(named, UTC))
        val meta = DateResolver.remote(ms("2023-01-01T00:00:00Z"), "IMG_20240312_101530.jpg", upload, NOW, UTC)
        assertEquals("metadata wins over the name", DateSource.CAPTURE, meta.source)
        assertEquals(DateSource.FILENAME, DateResolver.remote(0L, "IMG_20240312.jpg", upload, NOW, UTC).source)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryDatesTest {
    private lateinit var env: TestEnv
    private val zone = UTC

    @Before fun setUp() { env = TestEnv(); env.now = NOW }
    @After fun tearDown() { env.close() }

    private fun catalog(onListed: suspend (ListingResult) -> Unit = {}, last: suspend () -> ListingResult? = { null }) =
        RemoteCatalog(env.db, nowMs = { NOW }, zone = { zone }, onListed = onListed, lastCompleteListing = last)

    private fun put(n: Int, name: String, uploadedIso: String = "2026-10-01T10:00:00Z", metadata: Map<String, String>? = null): String {
        val key = env.keys.photoKey("%032x".format(n), "%064x".format(n), name)
        env.remote.objects[key] = FakeRemote.Obj("bytes-$n".toByteArray(), "", "etag$n")
        env.remote.uploadTimes[key] = ms(uploadedIso)
        metadata?.let { env.remote.metadata[key] = it }
        return key
    }

    // ------------------------------------------------------------------ catalog

    @Test fun `the listing is streamed page by page and every object gets its date and kind`() = runBlocking {
        val withName = put(1, "IMG_20240312_101530.jpg")
        val plain = put(2, "photo.jpg", "2026-10-02T23:30:00Z")
        val video = put(3, "VID_20240401_090000.mp4")
        put(4, "a.png"); put(5, "b.bin")
        env.remote.listPageSize = 2

        val n = catalog().refresh(env.ctx)

        assertEquals(5, n)
        assertEquals("5 objects, 2 per page", 3, env.remote.pagesDelivered)
        val dao = env.db.remote()
        dao.get(withName)!!.let {
            assertEquals(DateSource.FILENAME, it.dateSource); assertEquals(20240312, it.dayKey)
            assertEquals(ms("2024-03-12T10:15:30Z"), it.effectiveDateMs); assertEquals(MediaKind.IMAGE, it.mediaKind)
        }
        dao.get(plain)!!.let {
            assertEquals("upload time is not a capture date", DateSource.FALLBACK, it.dateSource); assertEquals(0, it.dayKey)
            assertEquals(20261002, it.uploadDayKey); assertEquals(ms("2026-10-02T23:30:00Z"), it.lastModifiedMs)
        }
        assertEquals(MediaKind.VIDEO, dao.get(video)!!.mediaKind)
        assertNull("unknown extension", dao.getAll().first { it.displayName == "b.bin" }.mediaKind)
    }

    @Test fun `a failure in the middle keeps the pages already stored, prunes nothing and says the list is incomplete`() = runBlocking {
        val stale = env.keys.photoKey("f".repeat(32), "f".repeat(64), "deleted_elsewhere.jpg")
        env.db.remote().upsertAll(listOf(RemoteObjectEntity(stale, "f".repeat(32), "f".repeat(64), "deleted_elsewhere.jpg", 1, 1)))
        (1..5).forEach { put(it, "p$it.jpg") }
        env.remote.listPageSize = 2
        env.remote.failListingAfterPages = 1
        val results = mutableListOf<ListingResult>()

        assertThrows(RemoteException::class.java) { runBlocking { catalog({ results += it }).refresh(env.ctx) } }

        assertEquals("first page stored, plus the stale row (no pruning)", 3, env.db.remote().getAll().size)
        assertNotNull(env.db.remote().get(stale))
        assertEquals(listOf(false), results.map { it.complete })

        env.remote.failListingAfterPages = null
        catalog({ results += it }).refresh(env.ctx)
        assertNull("a complete pass prunes what is gone", env.db.remote().get(stale))
        assertEquals(5, env.db.remote().getAll().size)
        assertEquals(true, results.last().complete); assertEquals(5, results.last().count)
    }

    @Test fun `a file with a capture date on this device gives its date to the object without any request`() = runBlocking {
        val key = put(1, "photo.jpg")
        env.index(env.scanned(1, name = "photo.jpg", bytes = "x".toByteArray()))
        env.db.media().update(
            env.db.media().get(1)!!.copy(remoteKey = key, syncState = SyncState.BACKED_UP, dateTakenMs = ms("2023-08-09T17:00:00Z"), dayKey = 20230809, dateSource = DateSource.CAPTURE),
        )

        catalog().refresh(env.ctx)

        env.db.remote().get(key)!!.let {
            assertEquals(DateSource.CAPTURE, it.dateSource); assertEquals(20230809, it.dayKey); assertEquals(ms("2023-08-09T17:00:00Z"), it.effectiveDateMs)
        }
        assertEquals("no HEAD was needed", 0, env.remote.headCalls)
    }

    @Test fun `a local file with only a file date does not pretend to be a capture date`() = runBlocking {
        val key = put(1, "photo.jpg")
        env.index(env.scanned(1, name = "photo.jpg", bytes = "x".toByteArray()))
        env.db.media().update(env.db.media().get(1)!!.copy(remoteKey = key, syncState = SyncState.BACKED_UP, dateSource = DateSource.FALLBACK))
        catalog().refresh(env.ctx)
        assertEquals(DateSource.FALLBACK, env.db.remote().get(key)!!.dateSource)
    }

    @Test fun `a capture date that was learned is never downgraded by a later listing`() = runBlocking {
        val key = put(1, "photo.jpg")
        catalog().refresh(env.ctx)
        catalog().recordCaptureDate(key, ms("2022-02-02T02:00:00Z"))
        assertEquals(DateSource.CAPTURE, env.db.remote().get(key)!!.dateSource)

        catalog().refresh(env.ctx)

        env.db.remote().get(key)!!.let { assertEquals(DateSource.CAPTURE, it.dateSource); assertEquals(20220202, it.dayKey) }
    }

    @Test fun `an implausible capture date from metadata is ignored but the question is not asked again`() = runBlocking {
        val key = put(1, "photo.jpg")
        catalog().refresh(env.ctx)
        catalog().recordCaptureDate(key, 5L) // 1970
        env.db.remote().get(key)!!.let { assertEquals(DateSource.FALLBACK, it.dateSource); assertTrue(it.metaChecked) }
    }

    @Test fun `an uploaded photo shows up in the library at once, with its capture date, without listing the bucket again`() = runBlocking {
        env.index(env.scanned(1))
        env.db.media().update(env.db.media().get(1)!!.copy(dateTakenMs = ms("2024-03-12T10:00:00Z"), dayKey = 20240312, dateSource = DateSource.CAPTURE))
        env.enqueue(1)
        val cat = catalog()
        val engine = BackupEngine(env.db, env.provider, env.creds, env.reader, env.progress, env.events, cat, nowMs = { env.now })

        engine.run(1)

        val row = env.db.media().get(1)!!
        val remote = env.db.remote().get(row.remoteKey!!)!!
        assertEquals(DateSource.CAPTURE, remote.dateSource)
        assertEquals(20240312, remote.dayKey)
        assertEquals("one listing for the whole run, none after the upload", 1, env.remote.listCalls)
    }

    @Test fun `a backup run skips the listing when the cached one is only minutes old`() = runBlocking {
        env.index(env.scanned(1)); env.enqueue(1)
        val fresh = catalog(last = { ListingResult(NOW - 60_000, complete = true, count = 0) })
        BackupEngine(env.db, env.provider, env.creds, env.reader, env.progress, env.events, fresh, nowMs = { env.now }).run(1)
        assertEquals(0, env.remote.listCalls)

        env.index(env.scanned(2)); env.enqueue(2)
        val stale = catalog(last = { ListingResult(NOW - 3_600_000, complete = true, count = 0) })
        BackupEngine(env.db, env.provider, env.creds, env.reader, env.progress, env.events, stale, nowMs = { env.now }).run(1)
        assertEquals(1, env.remote.listCalls)

        env.index(env.scanned(3)); env.enqueue(3)
        val incomplete = catalog(last = { ListingResult(NOW - 60_000, complete = false, count = 0) })
        BackupEngine(env.db, env.provider, env.creds, env.reader, env.progress, env.events, incomplete, nowMs = { env.now }).run(1)
        assertEquals("an incomplete listing is never treated as fresh", 2, env.remote.listCalls)
    }

    // ------------------------------------------------------------------ completing dates (opt-in HEAD pass)

    private fun engine(concurrency: Int = 4) = CloudDatesEngine(env.db, env.provider, env.creds, catalog(), concurrency)

    @Test fun `the dates pass reads each object once and never again`() = runBlocking {
        val a = put(1, "a.jpg", metadata = mapOf("captured-at-ms" to ms("2021-05-05T05:00:00Z").toString()))
        val b = put(2, "b.jpg") // uploaded by another tool: no such metadata
        val c = put(3, "IMG_20240312_101530.jpg") // the name already gives a date, but metadata is better
            .also { env.remote.metadata[it] = mapOf("captured-at-ms" to ms("2024-03-12T10:15:31Z").toString()) }
        catalog().refresh(env.ctx)
        assertEquals(3, env.db.remote().pendingMeta(10).size)

        val out = engine().run() as DatesOutcome.Done

        assertEquals(2, out.found); assertEquals(1, out.withoutDate)
        assertEquals(3, env.remote.headCalls)
        env.db.remote().get(a)!!.let { assertEquals(DateSource.CAPTURE, it.dateSource); assertEquals(20210505, it.dayKey) }
        env.db.remote().get(b)!!.let { assertEquals(DateSource.FALLBACK, it.dateSource); assertTrue(it.metaChecked) }
        assertEquals(ms("2024-03-12T10:15:31Z"), env.db.remote().get(c)!!.effectiveDateMs)

        assertEquals(DatesOutcome.Done(0, 0), engine().run())
        assertEquals("a second run asks for nothing", 3, env.remote.headCalls)
    }

    @Test fun `objects that already have a capture date are not requested`() = runBlocking {
        put(1, "a.jpg"); env.remote.listPageSize = 10
        catalog().refresh(env.ctx)
        val key = env.db.remote().allKeys().single()
        catalog().recordCaptureDate(key, ms("2020-01-01T00:00:00Z"))
        assertEquals(0, env.db.remote().pendingMeta(10).size)
        assertEquals(DatesOutcome.Done(0, 0), engine().run())
        assertEquals(0, env.remote.headCalls)
    }

    @Test fun `a transient failure leaves the objects pending for a retry`() = runBlocking {
        put(1, "a.jpg"); put(2, "b.jpg")
        catalog().refresh(env.ctx)
        env.remote.headFailures += RemoteException(RemoteErrorKind.NETWORK)

        val out = engine(concurrency = 1).run()

        assertEquals(DatesOutcome.RetryLater(RemoteErrorKind.NETWORK), out)
        assertTrue("the one that failed is still pending", env.db.remote().pendingMeta(10).isNotEmpty())
        assertTrue(engine().run() is DatesOutcome.Done)
        assertEquals(0, env.db.remote().pendingMeta(10).size)
    }

    @Test fun `without credentials nothing is requested`() = runBlocking {
        put(1, "a.jpg"); catalog().refresh(env.ctx)
        env.creds.failure = RemoteException(RemoteErrorKind.CREDENTIALS_EXPIRED)
        assertEquals(DatesOutcome.NeedsCredentials(RemoteErrorKind.CREDENTIALS_EXPIRED), engine().run())
        assertEquals(0, env.remote.headCalls)
    }

    @Test fun `a permission error is reported as blocked, not retried forever`() = runBlocking {
        put(1, "a.jpg"); catalog().refresh(env.ctx)
        env.remote.headFailures += RemoteException(RemoteErrorKind.ACCESS_DENIED)
        assertEquals(DatesOutcome.Blocked(RemoteErrorKind.ACCESS_DENIED), engine().run())
    }

    @Test fun `an object that vanished is marked as checked`() = runBlocking {
        val key = put(1, "gone.jpg"); catalog().refresh(env.ctx)
        env.remote.objects.remove(key)
        assertEquals(DatesOutcome.Done(0, 1), engine().run())
        assertEquals(0, env.db.remote().pendingMeta(10).size)
    }

    @Test fun `the controller schedules and cancels the pass`() = runBlocking {
        val scheduler = FakeScheduler()
        val lib = RemoteLibraryController(env.db, env.provider, env.catalog, scheduler, AppSettings(env.context))
        lib.completeCaptureDates()
        assertEquals(1, scheduler.datesKicks)
        lib.cancelCaptureDates()
        assertEquals(1, scheduler.datesCancels)
    }

    // ------------------------------------------------------------------ the local index

    private fun scan(id: Long, name: String, iso: String, source: DateSource = DateSource.CAPTURE, zone: ZoneId = UTC): ScannedMedia {
        val t = ms(iso)
        return ScannedMedia(id, "content://media/$id", MediaKind.IMAGE, name, "image/jpeg", 10, 1, t, 1, 1, "Camera", source, DateKeys.dayKey(t, zone))
    }

    @Test fun `the index stores the day key and the date source and refreshes them on rescan`() = runBlocking {
        env.indexer.reconcile(listOf(scan(1, "a.jpg", "2024-03-12T10:00:00Z", DateSource.FALLBACK)), true, NOW)
        env.db.media().get(1)!!.let { assertEquals(20240312, it.dayKey); assertEquals(DateSource.FALLBACK, it.dateSource) }

        // The photo gains a real capture date (e.g. MediaStore finished reading its metadata).
        env.indexer.reconcile(listOf(scan(1, "a.jpg", "2024-03-10T08:00:00Z", DateSource.CAPTURE)), true, NOW)

        env.db.media().get(1)!!.let { assertEquals(20240310, it.dayKey); assertEquals(DateSource.CAPTURE, it.dateSource); assertEquals(ms("2024-03-10T08:00:00Z"), it.dateTakenMs) }
    }

    // ------------------------------------------------------------------ time zone change

    @Test fun `day keys are rewritten once when the device time zone changes`() = runBlocking {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val settings = AppSettings(env.context)
        val late = "2024-12-31T23:30:00Z"
        env.indexer.reconcile(listOf(scan(1, "a.jpg", late)), true, NOW)
        val capture = put(1, "a.jpg")
        catalog().refresh(env.ctx)
        catalog().recordCaptureDate(capture, ms(late))
        var current = UTC

        val maintainer = DayKeyMaintainer(env.db, settings) { current }
        assertTrue("first run records the zone", maintainer.ensureCurrentZone())
        assertFalse("same zone: nothing to do", maintainer.ensureCurrentZone())
        assertEquals(20241231, env.db.media().get(1)!!.dayKey)

        current = tokyo
        assertTrue(maintainer.ensureCurrentZone())
        assertEquals("the same instant is already the next day in Tokyo", 20250101, env.db.media().get(1)!!.dayKey)
        assertEquals(20250101, env.db.remote().get(capture)!!.dayKey)
        assertEquals("the stored instant never changes", ms(late), env.db.media().get(1)!!.dateTakenMs)
        assertFalse(maintainer.ensureCurrentZone())
    }

    @Test fun `objects without a capture date keep day key zero when the zone changes`() = runBlocking {
        val key = put(1, "photo.jpg")
        catalog().refresh(env.ctx)
        var current = UTC
        val maintainer = DayKeyMaintainer(env.db, AppSettings(env.context)) { current }
        maintainer.ensureCurrentZone()
        current = ZoneId.of("Asia/Tokyo")
        maintainer.ensureCurrentZone()
        env.db.remote().get(key)!!.let { assertEquals(0, it.dayKey); assertEquals(20261001, it.uploadDayKey) }
    }

}
