package com.s3vault.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.s3vault.app.data.local.AppDatabase
import com.s3vault.app.data.local.CloudLibrarySql
import com.s3vault.app.data.local.DayCount
import com.s3vault.app.data.local.LocalLibrarySql
import com.s3vault.app.data.local.MediaItemEntity
import com.s3vault.app.data.local.RemoteObjectEntity
import com.s3vault.app.domain.MediaKind
import com.s3vault.app.domain.SyncState
import com.s3vault.app.domain.library.BackupFilter
import com.s3vault.app.domain.library.CloudAvailability
import com.s3vault.app.domain.library.DateKeys
import com.s3vault.app.domain.library.DateRange
import com.s3vault.app.domain.library.DateSource
import com.s3vault.app.domain.library.GroupBy
import com.s3vault.app.domain.library.LibraryFilters
import com.s3vault.app.domain.library.LibraryQuery
import com.s3vault.app.domain.library.SortField
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val UTC: ZoneId = ZoneId.of("UTC")
private val TODAY: LocalDate = LocalDate.of(2026, 10, 23)

private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalLibrarySqlTest {
    private lateinit var db: AppDatabase
    private val dao get() = db.media()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    private fun item(
        id: Long, name: String, iso: String, kind: MediaKind = MediaKind.IMAGE, size: Long = 1_000, folder: String = "Camera",
        state: SyncState = SyncState.LOCAL_ONLY, source: DateSource = DateSource.CAPTURE, present: Boolean = true, modifiedSec: Long = 0,
    ): MediaItemEntity {
        val t = ms(iso)
        return MediaItemEntity(
            mediaStoreId = id, uri = "content://m/$id", kind = kind, displayName = name, mimeType = if (kind == MediaKind.IMAGE) "image/jpeg" else "video/mp4",
            sizeBytes = size, dateModifiedSec = if (modifiedSec != 0L) modifiedSec else t / 1000, dateTakenMs = t, width = 10, height = 10, folderName = folder,
            assetId = "asset$id", present = present, syncState = state, dayKey = DateKeys.dayKey(t, UTC), dateSource = source,
        )
    }

    private fun seed() = runBlocking {
        dao.insertAll(
            listOf(
                item(1, "IMG_0001.jpg", "2024-03-12T10:00:00Z", size = 5_000),
                item(2, "IMG_0002.jpg", "2024-03-12T10:00:00Z", size = 3_000), // same instant as #1: ordering must stay stable
                item(3, "vacaciones_playa.jpg", "2024-03-15T08:00:00Z", folder = "WhatsApp", state = SyncState.BACKED_UP, size = 9_000),
                item(4, "clip.mp4", "2024-03-15T09:00:00Z", kind = MediaKind.VIDEO, state = SyncState.BACKED_UP, size = 90_000),
                item(5, "100%_real.jpg", "2023-12-31T23:30:00Z", state = SyncState.QUEUED),
                item(6, "a_b.jpg", "2023-06-01T00:00:00Z", state = SyncState.FAILED, folder = "Screenshots"),
                item(7, "Zeta.jpg", "2022-01-01T12:00:00Z", source = DateSource.FALLBACK, size = 100),
                item(8, "alfa.jpg", "2024-04-02T12:00:00Z", state = SyncState.UPLOADING, size = 7_000),
                item(9, "gone.jpg", "2024-04-02T12:00:00Z", present = false),
                item(10, "Alfa.jpg", "2024-04-03T12:00:00Z", size = 7_000),
            ),
        )
    }

    private fun rows(q: LibraryQuery, limit: Int = 100, offset: Int = 0) = runBlocking { dao.rows(LocalLibrarySql.rows(q, limit, offset, TODAY)) }
    private fun names(q: LibraryQuery) = rows(q).map { it.displayName }
    private fun count(q: LibraryQuery) = runBlocking { dao.countOnce(LocalLibrarySql.count(q, TODAY)) }
    private fun q(search: String = "", filters: LibraryFilters = LibraryFilters(), sort: SortField = SortField.CAPTURE_DATE, desc: Boolean = true) =
        LibraryQuery(search, filters, sort, desc)

    @Test fun `default query lists present photos newest first with a stable tie break`() {
        seed()
        assertEquals(
            listOf("Alfa.jpg", "alfa.jpg", "clip.mp4", "vacaciones_playa.jpg", "IMG_0002.jpg", "IMG_0001.jpg", "100%_real.jpg", "a_b.jpg", "Zeta.jpg"),
            names(q()),
        )
        assertEquals("the photo that left the device is not listed", 9, count(q()))
    }

    @Test fun `pages are contiguous and never overlap`() {
        seed()
        val all = rows(q()).map { it.mediaStoreId }
        val paged = (0 until all.size step 4).flatMap { rows(q(), 4, it).map { r -> r.mediaStoreId } }
        assertEquals(all, paged)
    }

    @Test fun `oldest first`() {
        seed()
        assertEquals(listOf("Zeta.jpg", "a_b.jpg", "100%_real.jpg"), names(q(desc = false)).take(3))
    }

    @Test fun `timeline has one row per day in the order of the list`() {
        seed()
        val days = runBlocking { dao.observeDayCounts(LocalLibrarySql.dayCounts(q(), TODAY)).let { it.first() } }
        assertEquals(
            listOf(DayCount(20240403, 1), DayCount(20240402, 1), DayCount(20240315, 2), DayCount(20240312, 2), DayCount(20231231, 1), DayCount(20230601, 1), DayCount(20220101, 1)),
            days,
        )
        assertEquals(count(q()), days.sumOf { it.n })
    }

    @Test fun `search by file name is case insensitive and matches the folder too`() {
        seed()
        assertEquals(listOf("alfa.jpg".lowercase()), names(q("ALFA")).map { it.lowercase() }.distinct())
        assertEquals(listOf("vacaciones_playa.jpg"), names(q("whatsapp")))
        assertEquals(listOf("vacaciones_playa.jpg"), names(q("playa vacaciones")))
        assertEquals("every term must match", emptyList<String>(), names(q("playa clip")))
    }

    @Test fun `percent and underscore typed by the user are literal characters`() {
        seed()
        assertEquals(listOf("100%_real.jpg"), names(q("100%")))
        assertEquals("an underscore is not a wildcard", listOf("a_b.jpg"), names(q("a_b")))
        assertEquals(emptyList<String>(), names(q("a_")).filter { it == "alfa.jpg" })
        assertEquals("a lone percent matches only names that contain it", listOf("100%_real.jpg"), names(q("%")))
    }

    @Test fun `search understands years months and dates`() {
        seed()
        assertEquals(listOf("Alfa.jpg", "alfa.jpg", "clip.mp4", "vacaciones_playa.jpg", "IMG_0002.jpg", "IMG_0001.jpg"), names(q("2024")))
        assertEquals(listOf("clip.mp4", "vacaciones_playa.jpg", "IMG_0002.jpg", "IMG_0001.jpg"), names(q("marzo 2024")))
        assertEquals(listOf("clip.mp4", "vacaciones_playa.jpg"), names(q("15/03/2024")))
        assertEquals(listOf("clip.mp4", "vacaciones_playa.jpg"), names(q("15 de marzo")))
        assertEquals("March of any year", listOf("clip.mp4", "vacaciones_playa.jpg", "IMG_0002.jpg", "IMG_0001.jpg"), names(q("marzo")))
        assertEquals("a month with no photos", emptyList<String>(), names(q("agosto 2024")))
    }

    @Test fun `search by type and the type filter combine as an intersection`() {
        seed()
        assertEquals(listOf("clip.mp4"), names(q("videos")))
        assertEquals(listOf("clip.mp4"), names(q(filters = LibraryFilters(kinds = setOf(MediaKind.VIDEO)))))
        assertEquals("videos in the search but only photos allowed by the filter", emptyList<String>(), names(q("videos", LibraryFilters(kinds = setOf(MediaKind.IMAGE)))))
        assertEquals(8, count(q(filters = LibraryFilters(kinds = setOf(MediaKind.IMAGE)))))
    }

    @Test fun `date range filter is inclusive and combines with the search`() {
        seed()
        val march = LibraryFilters(dateRange = DateRange(20240312, 20240315))
        assertEquals(4, count(q(filters = march)))
        assertEquals(listOf("clip.mp4"), names(q("clip", march)))
        assertEquals("filter and search ranges that do not meet match nothing", 0, count(q("2023", march)))
        assertEquals("an inverted range is empty", 0, count(q(filters = LibraryFilters(dateRange = DateRange(20240315, 20240312)))))
    }

    @Test fun `backup status filter maps to sync states`() {
        seed()
        fun ids(vararg f: BackupFilter) = rows(q(filters = LibraryFilters(backup = f.toSet()))).map { it.mediaStoreId }.sorted()
        assertEquals(listOf(3L, 4L), ids(BackupFilter.BACKED_UP))
        assertEquals(listOf(1L, 2L, 7L, 10L), ids(BackupFilter.NOT_BACKED_UP))
        assertEquals(listOf(5L, 8L), ids(BackupFilter.IN_PROGRESS))
        assertEquals(listOf(6L), ids(BackupFilter.FAILED))
        assertEquals(listOf(3L, 4L, 6L), ids(BackupFilter.BACKED_UP, BackupFilter.FAILED))
    }

    @Test fun `folder and undated filters`() {
        seed()
        assertEquals(listOf("vacaciones_playa.jpg"), names(q(filters = LibraryFilters(folders = setOf("WhatsApp")))))
        assertEquals(listOf("vacaciones_playa.jpg", "a_b.jpg").toSet(), names(q(filters = LibraryFilters(folders = setOf("WhatsApp", "Screenshots")))).toSet())
        assertEquals(listOf("Zeta.jpg"), names(q(filters = LibraryFilters(undatedOnly = true))))
    }

    @Test fun `sorting by name size and modified date`() {
        seed()
        assertEquals("case-insensitive, equal names ordered by id", listOf("100%_real.jpg", "a_b.jpg", "alfa.jpg", "Alfa.jpg"), names(q(sort = SortField.NAME, desc = false)).take(4))
        assertEquals("Zeta.jpg", names(q(sort = SortField.NAME, desc = false)).last())
        assertEquals(listOf("clip.mp4", "vacaciones_playa.jpg"), names(q(sort = SortField.SIZE)).take(2))
        assertEquals(listOf("Zeta.jpg"), names(q(sort = SortField.SIZE, desc = false)).take(1))
        assertEquals("Alfa.jpg", names(q(sort = SortField.MODIFIED_DATE)).first())
    }

    @Test fun `position of an item is its index in the list for every sort and direction`() {
        seed()
        for (sort in LocalLibrarySortFields) for (desc in listOf(true, false)) for (search in listOf("", "jpg")) {
            val query = q(search, sort = sort, desc = desc)
            val list = rows(query)
            list.forEachIndexed { index, e ->
                assertEquals("$sort desc=$desc search='$search' ${e.displayName}", index, runBlocking { dao.countOnce(LocalLibrarySql.positionOf(query, e, TODAY)) })
            }
        }
    }

    @Test fun `ids of a section and of everything`() {
        seed()
        assertEquals(9, runBlocking { dao.idList(LocalLibrarySql.ids(q(), today = TODAY)) }.size)
        assertEquals(listOf(4L, 3L), runBlocking { dao.idList(LocalLibrarySql.ids(q(), GroupBy.DAY to 20240315, TODAY)) })
        assertEquals(setOf(1L, 2L, 3L, 4L, 8L, 10L), runBlocking { dao.idList(LocalLibrarySql.ids(q(), GroupBy.YEAR to 2024, TODAY)) }.toSet())
        assertEquals(setOf(1L, 2L, 3L, 4L), runBlocking { dao.idList(LocalLibrarySql.ids(q(), GroupBy.MONTH to 202403, TODAY)) }.toSet())
    }

    @Test fun `a user typed quote cannot change the query`() {
        seed()
        assertEquals(0, count(q("x' OR '1'='1")))
        assertEquals(0, count(q("'; DROP TABLE media_items; --")))
        assertEquals(9, count(q()))
    }

    @Test fun `folders list for the filter sheet`() {
        seed()
        val folders = runBlocking { dao.observeFolders().first() }
        assertEquals("Camera", folders.first().name)
        assertTrue(folders.any { it.name == "WhatsApp" })
    }

    companion object {
        val LocalLibrarySortFields = listOf(SortField.CAPTURE_DATE, SortField.MODIFIED_DATE, SortField.NAME, SortField.SIZE)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudLibrarySqlTest {
    private lateinit var db: AppDatabase
    private val dao get() = db.remote()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    private fun obj(
        n: Int, name: String, captured: String?, uploaded: String, size: Long = 1_000, kind: MediaKind? = MediaKind.IMAGE,
        source: DateSource = if (captured != null) DateSource.CAPTURE else DateSource.FALLBACK, restored: String? = null,
    ): RemoteObjectEntity {
        val up = ms(uploaded)
        val eff = captured?.let { ms(it) } ?: up
        return RemoteObjectEntity(
            key = "p/photos/%032x/%064x/$name".format(n, n), assetId = "%032x".format(n), versionId = "%064x".format(n), displayName = name,
            sizeBytes = size, lastModifiedMs = up, restoredUri = restored, effectiveDateMs = eff, dateSource = source,
            dayKey = if (source == DateSource.FALLBACK) 0 else DateKeys.dayKey(eff, UTC), uploadDayKey = DateKeys.dayKey(up, UTC), mediaKind = kind,
        )
    }

    private fun seed() = runBlocking {
        dao.upsertAll(
            listOf(
                obj(1, "IMG_20240312_101530.jpg", "2024-03-12T10:15:30Z", "2026-10-01T10:00:00Z", size = 4_000),
                obj(2, "beach.jpg", "2024-03-15T08:00:00Z", "2026-10-01T10:00:01Z", size = 9_000),
                obj(3, "clip.mp4", "2023-12-31T23:00:00Z", "2026-10-02T10:00:00Z", kind = MediaKind.VIDEO, size = 90_000),
                obj(4, "mystery.jpg", null, "2026-10-05T10:00:00Z", size = 2_000),
                obj(5, "Another.jpg", null, "2026-10-04T10:00:00Z", size = 3_000),
                obj(6, "legacy.bin", null, "2026-10-06T10:00:00Z", kind = null),
                obj(7, "from_filename.jpg", "2021-07-04T12:00:00Z", "2026-10-07T10:00:00Z", source = DateSource.FILENAME),
            ),
        )
        // Object 1 also exists on this device; object 2 was restored once.
        db.media().insertAll(
            listOf(
                MediaItemEntity(
                    mediaStoreId = 100, uri = "content://m/100", kind = MediaKind.IMAGE, displayName = "IMG_20240312_101530.jpg", mimeType = "image/jpeg", sizeBytes = 4_000,
                    dateModifiedSec = 0, dateTakenMs = ms("2024-03-12T10:15:30Z"), width = 1, height = 1, folderName = "Camera", assetId = "x100",
                    remoteKey = "p/photos/%032x/%064x/IMG_20240312_101530.jpg".format(1, 1), syncState = SyncState.BACKED_UP,
                ),
            ),
        )
        dao.upsertAll(listOf(obj(2, "beach.jpg", "2024-03-15T08:00:00Z", "2026-10-01T10:00:01Z", size = 9_000, restored = "content://restored/2")))
    }

    private fun rows(q: LibraryQuery, limit: Int = 100, offset: Int = 0) = runBlocking { dao.rows(CloudLibrarySql.rows(q, limit, offset, TODAY)) }
    private fun names(q: LibraryQuery) = rows(q).map { it.displayName }
    private fun count(q: LibraryQuery) = runBlocking { dao.countOnce(CloudLibrarySql.count(q, TODAY)) }
    private fun q(search: String = "", filters: LibraryFilters = LibraryFilters(), sort: SortField = SortField.CAPTURE_DATE, desc: Boolean = true) =
        LibraryQuery(search, filters, sort, desc)

    @Test fun `dated objects come first newest first and undated ones last`() {
        seed()
        assertEquals(
            listOf("beach.jpg", "IMG_20240312_101530.jpg", "clip.mp4", "from_filename.jpg", "legacy.bin", "mystery.jpg", "Another.jpg"),
            names(q()),
        )
    }

    @Test fun `undated objects stay last when the direction is reversed`() {
        seed()
        assertEquals(listOf("from_filename.jpg", "clip.mp4", "IMG_20240312_101530.jpg", "beach.jpg"), names(q(desc = false)).take(4))
        assertEquals(setOf("legacy.bin", "mystery.jpg", "Another.jpg"), names(q(desc = false)).drop(4).toSet())
    }

    @Test fun `timeline puts the section without a capture date last`() {
        seed()
        val days = runBlocking { dao.observeDayCounts(CloudLibrarySql.dayCounts(q(), TODAY)).first() }
        assertEquals(
            listOf(DayCount(20240315, 1), DayCount(20240312, 1), DayCount(20231231, 1), DayCount(20210704, 1), DayCount(0, 3)),
            days,
        )
        assertEquals(count(q()), days.sumOf { it.n })
    }

    @Test fun `date filters and searches never match objects without a capture date`() {
        seed()
        assertEquals(setOf("beach.jpg", "IMG_20240312_101530.jpg"), names(q("2024")).toSet())
        assertEquals(setOf("beach.jpg", "IMG_20240312_101530.jpg", "clip.mp4"), names(q(filters = LibraryFilters(dateRange = DateRange(20231201, 20240430)))).toSet())
        assertEquals("a name term alone still finds undated objects", listOf("mystery.jpg"), names(q("mystery")))
        assertEquals("but not when a date is also asked for", 0, count(q("mystery 2026")))
    }

    @Test fun `search is limited to the file name`() {
        seed()
        assertEquals(listOf("IMG_20240312_101530.jpg"), names(q("20240312")))
        assertEquals(listOf("clip.mp4"), names(q("videos")))
        assertEquals(listOf("legacy.bin"), names(q("bin")))
    }

    @Test fun `type filter uses the extension and unknown types match neither`() {
        seed()
        assertEquals(5, count(q(filters = LibraryFilters(kinds = setOf(MediaKind.IMAGE)))))
        assertEquals(listOf("clip.mp4"), names(q(filters = LibraryFilters(kinds = setOf(MediaKind.VIDEO)))))
        assertEquals(7, count(q()))
    }

    @Test fun `availability filter separates objects only in S3 from those also on the device`() {
        seed()
        assertEquals(setOf("IMG_20240312_101530.jpg", "beach.jpg"), names(q(filters = LibraryFilters(availability = CloudAvailability.ALSO_LOCAL))).toSet())
        assertEquals(5, count(q(filters = LibraryFilters(availability = CloudAvailability.CLOUD_ONLY))))
    }

    @Test fun `a file that left the device no longer counts as also local`() {
        seed()
        runBlocking { db.media().update(db.media().get(100)!!.copy(present = false)) }
        assertEquals(listOf("beach.jpg"), names(q(filters = LibraryFilters(availability = CloudAvailability.ALSO_LOCAL))))
    }

    @Test fun `upload date sort groups by the upload day`() {
        seed()
        assertEquals(listOf("from_filename.jpg", "legacy.bin", "mystery.jpg"), names(q(sort = SortField.UPLOAD_DATE)).take(3))
        val days = runBlocking { dao.observeDayCounts(CloudLibrarySql.dayCounts(q(sort = SortField.UPLOAD_DATE), TODAY)).first() }
        assertEquals(listOf(20261007, 20261006, 20261005, 20261004, 20261002, 20261001), days.map { it.day })
        assertEquals(2, days.last().n)
    }

    @Test fun `name and size sorting`() {
        seed()
        assertEquals(listOf("Another.jpg", "beach.jpg", "clip.mp4"), names(q(sort = SortField.NAME, desc = false)).take(3))
        assertEquals(listOf("clip.mp4", "beach.jpg"), names(q(sort = SortField.SIZE)).take(2))
    }

    @Test fun `position of an object is its index for every sort and direction`() {
        seed()
        for (sort in listOf(SortField.CAPTURE_DATE, SortField.UPLOAD_DATE, SortField.NAME, SortField.SIZE)) for (desc in listOf(true, false)) {
            val query = q(sort = sort, desc = desc)
            rows(query).forEachIndexed { index, e ->
                assertEquals("$sort desc=$desc ${e.displayName}", index, runBlocking { dao.countOnce(CloudLibrarySql.positionOf(query, e, TODAY)) })
            }
        }
    }

    @Test fun `keys of a section`() {
        seed()
        assertEquals(3, runBlocking { dao.keyList(CloudLibrarySql.keys(q(), GroupBy.DAY to 0, TODAY)) }.size)
        assertEquals(1, runBlocking { dao.keyList(CloudLibrarySql.keys(q(), GroupBy.YEAR to 2023, TODAY)) }.size)
        assertEquals(2, runBlocking { dao.keyList(CloudLibrarySql.keys(q(), GroupBy.MONTH to 202403, TODAY)) }.size)
        assertEquals(7, runBlocking { dao.keyList(CloudLibrarySql.keys(q(), null, TODAY)) }.size)
    }

    @Test fun `pages are contiguous`() {
        seed()
        val all = rows(q()).map { it.key }
        assertEquals(all, (0 until all.size step 3).flatMap { rows(q(), 3, it).map { r -> r.key } })
    }
}
