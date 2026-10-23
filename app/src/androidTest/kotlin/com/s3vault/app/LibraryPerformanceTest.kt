package com.s3vault.app

import android.util.Log
import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.s3vault.app.data.local.AppDatabase
import com.s3vault.app.data.local.CloudLibrarySql
import com.s3vault.app.data.local.LocalLibrarySql
import com.s3vault.app.data.local.MediaItemEntity
import com.s3vault.app.data.local.RemoteObjectEntity
import com.s3vault.app.domain.MediaKind
import com.s3vault.app.domain.SyncState
import com.s3vault.app.domain.library.DateKeys
import com.s3vault.app.domain.library.DateRange
import com.s3vault.app.domain.library.DateSource
import com.s3vault.app.domain.library.LibraryFilters
import com.s3vault.app.domain.library.LibraryQuery
import com.s3vault.app.domain.library.SortField
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

/**
 * What the gallery asks of SQLite on a library of 20 000 photos on each side, on a real device: every query the screens
 * issue (a page, the per-day counts that drive headings and the timeline, a deep page for a jump, search, filters, other
 * sorts) is timed and logged under `LibraryPerf`. The bounds are deliberately generous (an emulator is slow); they exist to
 * catch a query that fell off its index (seconds instead of milliseconds), not to benchmark.
 */
@RunWith(AndroidJUnit4::class)
class LibraryPerformanceTest {
    private lateinit var db: AppDatabase
    private val zone = ZoneId.of("UTC")
    private val total = 20_000

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        runBlocking {
            val base = java.time.LocalDate.of(2015, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
            val dayMs = 86_400_000L
            val media = ArrayList<MediaItemEntity>(total)
            val remote = ArrayList<RemoteObjectEntity>(total)
            for (i in 0 until total) {
                // ~4 photos a day for 14 years, a few videos, a few without a capture date.
                val t = base + (i / 4) * dayMs + (i % 4) * 3_600_000L
                val kind = if (i % 17 == 0) MediaKind.VIDEO else MediaKind.IMAGE
                val ext = if (kind == MediaKind.VIDEO) "mp4" else "jpg"
                val undated = i % 53 == 0
                media += MediaItemEntity(
                    mediaStoreId = i + 1L, uri = "content://m/$i", kind = kind, displayName = "IMG_${"%05d".format(i)}.$ext",
                    mimeType = if (kind == MediaKind.VIDEO) "video/mp4" else "image/jpeg", sizeBytes = 1_000L + i, dateModifiedSec = t / 1000, dateTakenMs = t,
                    width = 10, height = 10, folderName = if (i % 5 == 0) "WhatsApp" else "Camera", assetId = "a$i", present = true,
                    syncState = if (i % 3 == 0) SyncState.BACKED_UP else SyncState.LOCAL_ONLY, remoteKey = if (i % 3 == 0) "p/photos/a$i/v/IMG_$i.$ext" else null,
                    dayKey = DateKeys.dayKey(t, zone), dateSource = if (undated) DateSource.FALLBACK else DateSource.CAPTURE,
                )
                val noDate = i % 11 == 0
                remote += RemoteObjectEntity(
                    key = "p/photos/a$i/v/IMG_$i.$ext", assetId = "a$i", versionId = "v", displayName = "IMG_${"%05d".format(i)}.$ext", sizeBytes = 1_000L + i,
                    lastModifiedMs = base + 15 * 365 * dayMs, effectiveDateMs = if (noDate) base + 15 * 365 * dayMs else t,
                    dateSource = if (noDate) DateSource.FALLBACK else DateSource.CAPTURE, dayKey = if (noDate) 0 else DateKeys.dayKey(t, zone),
                    uploadDayKey = DateKeys.dayKey(base + 15 * 365 * dayMs, zone), mediaKind = kind,
                )
            }
            media.chunked(1_000).forEach { db.media().insertAll(it) }
            remote.chunked(1_000).forEach { db.remote().upsertAll(it) }
        }
    }

    @After fun tearDown() { db.close() }

    private inline fun <T> timed(label: String, limitMs: Long, block: () -> T): T {
        block() // warm-up: the first run pays for statement compilation and cold caches
        val t0 = System.nanoTime()
        val r = block()
        val ms = (System.nanoTime() - t0) / 1_000_000
        Log.i("LibraryPerf", "$label: $ms ms")
        assertTrue("$label took $ms ms (limit $limitMs)", ms <= limitMs)
        return r
    }

    @Test fun localQueriesStayFastWithTwentyThousandPhotos() = runBlocking {
        val dao = db.media()
        val today = java.time.LocalDate.of(2026, 10, 23)
        val q = LibraryQuery()
        assertEquals(total, timed("local count", 400) { runBlocking { dao.countOnce(LocalLibrarySql.count(q, today)) } })
        val days = timed("local day counts", 800) { runBlocking { dao.observeDayCounts(LocalLibrarySql.dayCounts(q, today)).first() } }
        assertEquals("the day rows add up to the library", total, days.sumOf { it.n })
        assertEquals(96, timed("local first page", 200) { runBlocking { dao.rows(LocalLibrarySql.rows(q, 96, 0, today)) } }.size)
        assertEquals(96, timed("local deep page (jump to an old date)", 400) { runBlocking { dao.rows(LocalLibrarySql.rows(q, 96, total - 200, today)) } }.size)

        val name = LibraryQuery(search = "IMG_0123")
        assertEquals(10, timed("local search by file name", 600) { runBlocking { dao.countOnce(LocalLibrarySql.count(name, today)) } })
        val year = LibraryQuery(search = "marzo 2019")
        assertTrue(timed("local search by month and year", 600) { runBlocking { dao.countOnce(LocalLibrarySql.count(year, today)) } } > 0)
        val filtered = LibraryQuery(filters = LibraryFilters(dateRange = DateRange(20180101, 20181231), kinds = setOf(MediaKind.VIDEO)))
        assertTrue(timed("local date range + kind filter", 600) { runBlocking { dao.countOnce(LocalLibrarySql.count(filtered, today)) } } > 0)
        val bySize = LibraryQuery(sort = SortField.SIZE)
        assertEquals(96, timed("local first page sorted by size", 800) { runBlocking { dao.rows(LocalLibrarySql.rows(bySize, 96, 0, today)) } }.size)
        val byName = LibraryQuery(sort = SortField.NAME, descending = false)
        assertEquals(96, timed("local first page sorted by name", 800) { runBlocking { dao.rows(LocalLibrarySql.rows(byName, 96, 0, today)) } }.size)
    }

    @Test fun cloudQueriesStayFastWithTwentyThousandObjects() = runBlocking {
        val dao = db.remote()
        val today = java.time.LocalDate.of(2026, 10, 23)
        val q = LibraryQuery()
        assertEquals(total, timed("cloud count", 400) { runBlocking { dao.countOnce(CloudLibrarySql.count(q, today)) } })
        val days = timed("cloud day counts", 800) { runBlocking { dao.observeDayCounts(CloudLibrarySql.dayCounts(q, today)).first() } }
        assertEquals(total, days.sumOf { it.n })
        assertTrue("objects without a capture date form their own section", days.any { it.day == 0 })
        assertEquals(96, timed("cloud first page", 400) { runBlocking { dao.rows(CloudLibrarySql.rows(q, 96, 0, today)) } }.size)
        assertEquals(96, timed("cloud deep page", 600) { runBlocking { dao.rows(CloudLibrarySql.rows(q, 96, total - 200, today)) } }.size)
        val search = LibraryQuery(search = "IMG_0123")
        assertEquals(10, timed("cloud search by file name", 600) { runBlocking { dao.countOnce(CloudLibrarySql.count(search, today)) } })
        val upload = LibraryQuery(sort = SortField.UPLOAD_DATE)
        assertEquals(96, timed("cloud first page by upload date", 800) { runBlocking { dao.rows(CloudLibrarySql.rows(upload, 96, 0, today)) } }.size)
    }

    @Test fun theDefaultPagesReadAnIndexInsteadOfSortingTheTable() {
        val page = LocalLibrarySql.rows(LibraryQuery(), 96, 0)
        val explain = object : SupportSQLiteQuery {
            override val sql = "EXPLAIN QUERY PLAN " + page.sql
            override val argCount get() = page.argCount
            override fun bindTo(statement: SupportSQLiteProgram) = page.bindTo(statement)
        }
        val plan = StringBuilder()
        db.openHelper.readableDatabase.query(explain).use { c ->
            while (c.moveToNext()) plan.append(c.getString(c.getColumnIndexOrThrow("detail"))).append(Char(10))
        }
        Log.i("LibraryPerf", "local page plan: " + plan)
        assertTrue("uses an index: " + plan, plan.contains("INDEX"))
        assertFalse("does not sort the whole table: " + plan, plan.contains("TEMP B-TREE"))
    }
}
