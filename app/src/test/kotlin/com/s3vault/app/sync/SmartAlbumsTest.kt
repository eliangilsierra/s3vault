package com.s3vault.app.sync

import com.s3vault.app.data.local.IntegrityResultEntity
import com.s3vault.app.data.local.IntegrityReason
import com.s3vault.app.data.local.IntegrityVerdict
import com.s3vault.app.data.local.MediaItemEntity
import com.s3vault.app.data.local.ScannedMedia
import com.s3vault.app.data.local.SmartAlbumEntity
import com.s3vault.app.data.local.SmartAlbumSql
import com.s3vault.app.data.local.VerifyLevel
import com.s3vault.app.domain.MediaKind
import com.s3vault.app.domain.SyncState
import com.s3vault.app.domain.library.DateKeys
import com.s3vault.app.domain.library.DateRange
import com.s3vault.app.domain.library.DateSource
import com.s3vault.app.domain.smart.SmartAlbum
import com.s3vault.app.domain.smart.SmartAlbums
import com.s3vault.app.domain.smart.SmartBackup
import com.s3vault.app.domain.smart.SmartFilter
import com.s3vault.app.domain.smart.SmartFilterCodec
import com.s3vault.app.domain.space.SafetyRules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmartAlbumsTest {
    private lateinit var env: TestEnv
    private val day = 24L * 3600 * 1000
    private val now = 2_000 * day
    private val rules = SafetyRules(keepRecentDays = 0, minBackedUpDays = 0)
    private val library = LinkedHashMap<Long, ScannedMedia>()

    @Before fun setUp() { env = TestEnv(); env.now = now }
    @After fun tearDown() { env.close() }

    /** The library as MediaStore reports it (every call is a full scan). */
    private suspend fun scan(
        id: Long, name: String = "IMG_$id.jpg", folder: String = "Camera", kind: MediaKind = MediaKind.IMAGE, size: Long = 1_000, width: Int = 640, height: Int = 480,
        dayKey: Int = 20240315, favorite: Boolean = false, addedSec: Long = now / 1000 - 400 * 86_400, source: DateSource = DateSource.CAPTURE,
    ) {
        env.reader.files["content://media/$id"] = ByteArray(size.toInt())
        library[id] = ScannedMedia(id, "content://media/$id", kind, name, "image/jpeg", size, 100, 5_000, width, height, folder, source, dayKey, favorite, addedSec)
        env.indexer.reconcile(library.values.toList(), true, now)
    }

    private suspend fun ids(f: SmartFilter) = env.db.media().idList(SmartAlbumSql.ids(f, rules, now)).toSet()
    private suspend fun count(f: SmartFilter) = env.db.media().observeSmartCount(SmartAlbumSql.count(f, rules, now)).first()
    private fun album(id: String) = SmartAlbums.builtIn.first { it.id == id }

    // ------------------------------------------------------------------ definitions

    @Test fun `favorites, screenshots, videos and large files are answered from metadata`() = runBlocking {
        scan(1, favorite = true)
        scan(2, folder = "Screenshots")
        scan(3, folder = "Capturas de pantalla")
        scan(4, kind = MediaKind.VIDEO, folder = "Camera")
        scan(5, size = SmartAlbums.LARGE_BYTES)
        scan(6, size = SmartAlbums.LARGE_BYTES - 1)
        scan(7, folder = "WhatsApp Images")

        assertEquals(setOf(1L), ids(album("builtin:favorites").filter))
        assertEquals(setOf(2L, 3L), ids(album("builtin:screenshots").filter))
        assertEquals(setOf(4L), ids(album("builtin:videos").filter))
        assertEquals("the threshold is inclusive and exact", setOf(5L), ids(album("builtin:large").filter))
    }

    @Test fun `recently added looks at the date the file entered the library, not the date it was taken`() = runBlocking {
        scan(1, addedSec = now / 1000 - 5 * 86_400, dayKey = 20100101) // taken years ago, added 5 days ago
        scan(2, addedSec = now / 1000 - 90 * 86_400, dayKey = 20240301) // taken recently, added long ago
        assertEquals(setOf(1L), ids(album("builtin:recent").filter))
    }

    @Test fun `not backed up follows the sync state and changes as photos are backed up`() = runBlocking {
        scan(1); scan(2); scan(3)
        assertEquals(3, count(album("builtin:not-backed-up").filter))
        env.enqueue(1)
        assertEquals("queued is on its way, not yet 'not backed up'", 2, count(album("builtin:not-backed-up").filter))
        env.engine().run(2)
        assertEquals(setOf(2L, 3L), ids(album("builtin:not-backed-up").filter))
        env.db.media().transition(2, SyncState.LOCAL_ONLY, SyncState.QUEUED, null, false, now)
        env.db.media().transition(2, SyncState.QUEUED, SyncState.FAILED, "x", false, now)
        assertTrue("a failed upload is still not backed up", 2L in ids(album("builtin:not-backed-up").filter))
    }

    @Test fun `restored photos are the ones in the restore folder added recently`() = runBlocking {
        scan(1, folder = SmartAlbums.RESTORE_FOLDER, addedSec = now / 1000 - 3 * 86_400)
        scan(2, folder = SmartAlbums.RESTORE_FOLDER, addedSec = now / 1000 - 200 * 86_400)
        scan(3, folder = "Camera", addedSec = now / 1000 - 3 * 86_400)
        assertEquals(setOf(1L), ids(album("builtin:restored").filter))
    }

    @Test fun `missing metadata means no capture date or no size in pixels`() = runBlocking {
        scan(1, source = DateSource.FALLBACK)
        scan(2, width = 0, height = 0)
        scan(3)
        assertEquals(setOf(1L, 2L), ids(album("builtin:metadata").filter))
    }

    @Test fun `listos para liberar espacio uses the same bucket as the space screen`() = runBlocking {
        scan(1); scan(2)
        env.enqueue(1, 2); env.engine().run(2)
        val k1 = env.db.media().get(1)!!.remoteKey!!
        env.db.integrity().upsertResults(listOf(IntegrityResultEntity(k1, IntegrityVerdict.VERIFIED, VerifyLevel.CHECKSUM, IntegrityReason.NONE, now - 1000, expectedSize = 1_000)))
        // File 2 was backed up (verified by its own upload, just now); with the default window both are recent evidence.
        assertEquals(setOf(1L, 2L), ids(album("builtin:recoverable").filter))
        env.db.integrity().upsertResults(listOf(IntegrityResultEntity(k1, IntegrityVerdict.MISSING, VerifyLevel.LISTING, IntegrityReason.NOT_LISTED, now - 500, expectedSize = 1_000)))
        assertEquals("a missing object is never 'ready to free'", setOf(2L), ids(album("builtin:recoverable").filter))
    }

    // ------------------------------------------------------------------ dates

    @Test fun `a year, a month and a single day are exact ranges of the capture day`() = runBlocking {
        scan(1, dayKey = 20231231); scan(2, dayKey = 20240101); scan(3, dayKey = 20240229); scan(4, dayKey = 20240301); scan(5, dayKey = 20241231); scan(6, dayKey = 20250101)
        assertEquals(setOf(2L, 3L, 4L, 5L), ids(SmartAlbums.year(2024).filter))
        assertEquals("leap day February", setOf(3L), ids(SmartFilter(dateRange = DateRange(20240201, 20240229))))
        assertEquals("one day", setOf(1L), ids(SmartFilter(dateRange = DateRange(20231231, 20231231))))
        assertEquals("a photo without a capture date (day 0) never matches a date range", emptySet<Long>(), run { scan(7, dayKey = 0); ids(SmartFilter(dateRange = DateRange(20240101, 20241231))) } - setOf(2L, 3L, 4L, 5L))
    }

    @Test fun `the years that have photos are listed with their counts`() = runBlocking {
        scan(1, dayKey = 20230601); scan(2, dayKey = 20230602); scan(3, dayKey = 20240101); scan(4, dayKey = 0)
        val years = env.db.smartAlbums().observeYears().first()
        assertEquals(listOf(2024 to 1, 2023 to 2), years.map { it.year to it.n })
    }

    @Test fun `day keys follow the device zone the index was built in`() {
        val taken = java.time.ZonedDateTime.of(2024, 12, 31, 23, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals(20241231, DateKeys.dayKey(taken, ZoneId.of("UTC")))
        assertEquals("the same instant is the next day in Tokyo", 20250101, DateKeys.dayKey(taken, ZoneId.of("Asia/Tokyo")))
    }

    // ------------------------------------------------------------------ combinations, membership and counts

    @Test fun `filters combine with AND and a custom album is exactly what it says`() = runBlocking {
        scan(1, favorite = true, size = 30_000_000, dayKey = 20240101)
        scan(2, favorite = true, size = 10, dayKey = 20240101)
        scan(3, favorite = false, size = 30_000_000, dayKey = 20240101)
        scan(4, favorite = true, size = 30_000_000, dayKey = 20200101)
        val f = SmartFilter(favorites = true, minSizeBytes = 25_000_000, dateRange = DateRange(20240101, 20241231))
        assertEquals(setOf(1L), ids(f))
        assertEquals(1, count(f))
        assertEquals("a name filter treats % and _ literally", setOf<Long>(), ids(SmartFilter(nameContains = "%")))
    }

    @Test fun `membership follows photos added, deleted and restored without any bookkeeping`() = runBlocking {
        scan(1, favorite = true)
        val fav = album("builtin:favorites").filter
        val flow = env.db.media().observeSmartCount(SmartAlbumSql.count(fav, rules, now))
        assertEquals(1, flow.first())
        scan(2, favorite = true)
        assertEquals("a new photo enters", 2, flow.first())
        env.db.media().deleteLocalRows(listOf(1))
        assertEquals("a deleted photo leaves", 1, flow.first())
        library.remove(1)
        // A photo that left the device after a backup is not on the device any more: not in an album of what is here.
        scan(3, favorite = true)
        env.enqueue(3); env.engine().run(2)
        library.remove(3); env.indexer.reconcile(library.values.toList(), true, now)
        assertFalse(3L in ids(fav))
        // And when it is restored it is a new local file.
        scan(4, favorite = true)
        assertTrue(4L in ids(fav))
    }

    @Test fun `one scan counts every album and agrees with each album opened on its own`() = runBlocking {
        scan(1, favorite = true); scan(2, folder = "Screenshots"); scan(3, kind = MediaKind.VIDEO); scan(4, size = SmartAlbums.LARGE_BYTES)
        scan(5, source = DateSource.FALLBACK); scan(6, folder = SmartAlbums.RESTORE_FOLDER, addedSec = now / 1000 - 86_400); scan(7, addedSec = now / 1000 - 86_400)
        env.enqueue(7); env.engine().run(2)
        val albums = SmartAlbums.builtIn
        val cursor = env.db.query(SmartAlbumSql.countsOf(albums, rules, now))
        val together = cursor.use { c -> c.moveToFirst(); albums.indices.map { c.getInt(it) } }
        val alone = albums.map { count(it.filter) }
        assertEquals(alone, together)
        assertTrue("the fixture exercises most albums: $together", together.count { it > 0 } >= 7)
    }

    @Test fun `counting all albums over twenty thousand photos is one fast pass`() = runBlocking {
        val rows = (1L..20_000L).map { i ->
            MediaItemEntity(
                mediaStoreId = i, uri = "content://media/$i", kind = if (i % 9 == 0L) MediaKind.VIDEO else MediaKind.IMAGE, displayName = "IMG_$i.jpg", mimeType = "image/jpeg",
                sizeBytes = i * 1_000, dateModifiedSec = 1, dateTakenMs = 5_000, width = 4000, height = 3000, folderName = if (i % 40 == 0L) "Screenshots" else "Camera",
                assetId = "%032x".format(i), dayKey = 20200101 + (i % 1000).toInt(), isFavorite = i % 17 == 0L, dateAddedSec = now / 1000 - (i % 400) * 86_400,
            )
        }
        env.db.media().insertAll(rows)
        val albums = SmartAlbums.builtIn
        val t0 = System.nanoTime()
        val total = env.db.query(SmartAlbumSql.countsOf(albums, rules, now)).use { c -> c.moveToFirst(); albums.indices.sumOf { c.getInt(it) } }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("counted $total in $ms ms", ms < 3_000 && total > 0)
    }

    // ------------------------------------------------------------------ custom albums

    @Test fun `a filter survives its JSON form and a damaged record is an empty condition, not a crash`() {
        val f = SmartFilter(
            kinds = setOf(MediaKind.VIDEO), favorites = true, screenshots = true, minSizeBytes = 123, addedWithinDays = 7, dateRange = DateRange(20240101, 20241231),
            backup = SmartBackup.BACKED_UP, recoverable = true, restoredWithinDays = 9, missingMetadata = true, folders = setOf("Camera", "Download"), nameContains = "playa",
        )
        assertEquals(f, SmartFilterCodec.decode(SmartFilterCodec.encode(f)))
        assertEquals(SmartFilter(), SmartFilterCodec.decode("{not json"))
        assertEquals("unknown keys and values are ignored", SmartFilter(favorites = true), SmartFilterCodec.decode("""{"favorites":true,"future":1,"backup":"MARS","kinds":["HOLOGRAM"]}"""))
        assertEquals(12, f.activeCount)
    }

    @Test fun `custom albums are stored as definitions and deleting one touches no photo`() = runBlocking {
        scan(1, favorite = true)
        val id = env.db.smartAlbums().insert(SmartAlbumEntity(name = "Favoritas grandes", filterJson = SmartFilterCodec.encode(SmartFilter(favorites = true)), createdAtMs = now))
        assertEquals(listOf("Favoritas grandes"), env.db.smartAlbums().observeAll().first().map { it.name })
        env.db.smartAlbums().update(id, "Mis favoritas", SmartFilterCodec.encode(SmartFilter(favorites = true, kinds = setOf(MediaKind.IMAGE))))
        assertEquals("Mis favoritas", env.db.smartAlbums().get(id)!!.name)
        env.db.smartAlbums().delete(id)
        assertTrue(env.db.smartAlbums().observeAll().first().isEmpty())
        assertEquals(1, env.db.media().getEverything().size)
    }

    @Test fun `built-in ids are stable and unique, and year ids rebuild their album`() {
        assertEquals(SmartAlbums.builtIn.size, SmartAlbums.builtIn.map { it.id }.toSet().size)
        assertEquals(SmartAlbums.year(2023), SmartAlbums.find("year:2023", emptyList()))
        assertEquals(null, SmartAlbums.find("builtin:nope", emptyList()))
        val custom = SmartAlbum("custom:3", "X", "", com.s3vault.app.domain.smart.SmartIcon.CUSTOM, SmartFilter(favorites = true), custom = true)
        assertEquals(custom, SmartAlbums.find("custom:3", listOf(custom)))
    }
}
