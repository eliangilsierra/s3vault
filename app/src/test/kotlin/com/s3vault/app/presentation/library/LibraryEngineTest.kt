package com.s3vault.app.presentation.library

import com.s3vault.app.data.local.DayCount
import com.s3vault.app.domain.library.GroupBy
import com.s3vault.app.domain.library.LibraryQuery
import com.s3vault.app.domain.library.LibraryScope
import com.s3vault.app.domain.library.Section
import com.s3vault.app.domain.library.SortField
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryLayoutTest {
    private val days = listOf(DayCount(20240402, 3), DayCount(20240315, 2), DayCount(20240312, 1), DayCount(20231231, 4), DayCount(0, 2))

    @Test fun `days merge into the sections of the chosen grouping`() {
        assertEquals(
            listOf(Section(20240402, 3), Section(20240315, 2), Section(20240312, 1), Section(20231231, 4), Section(0, 2)),
            LibraryLayout.from(days, GroupBy.DAY).sections,
        )
        assertEquals(listOf(Section(202404, 3), Section(202403, 3), Section(202312, 4), Section(0, 2)), LibraryLayout.from(days, GroupBy.MONTH).sections)
        assertEquals(listOf(Section(2024, 6), Section(2023, 4), Section(0, 2)), LibraryLayout.from(days, GroupBy.YEAR).sections)
        assertTrue(LibraryLayout.from(days, GroupBy.NONE).sections.isEmpty())
        assertEquals(12, LibraryLayout.from(days, GroupBy.NONE).itemCount)
    }

    @Test fun `entries are headings followed by their photos`() {
        val l = LibraryLayout.from(days, GroupBy.YEAR) // 2024: 6 photos, 2023: 4, undated: 2
        assertEquals(12 + 3, l.entryCount)
        assertTrue(l.isHeader(0)); assertTrue(l.isHeader(7)); assertTrue(l.isHeader(12))
        assertEquals(-1, l.itemIndexOf(0))
        assertEquals(0, l.itemIndexOf(1)); assertEquals(5, l.itemIndexOf(6))
        assertEquals(6, l.itemIndexOf(8)); assertEquals(9, l.itemIndexOf(11))
        assertEquals(10, l.itemIndexOf(13)); assertEquals(11, l.itemIndexOf(14))
        assertEquals(0, l.sectionOfEntry(3)); assertEquals(1, l.sectionOfEntry(9)); assertEquals(2, l.sectionOfEntry(14))
    }

    @Test fun `item to entry and back is the identity for every item`() {
        for (g in listOf(GroupBy.DAY, GroupBy.MONTH, GroupBy.YEAR, GroupBy.NONE)) {
            val l = LibraryLayout.from(days, g)
            for (item in 0 until l.itemCount) {
                val entry = l.entryOfItem(item)
                assertFalse("$g $item", l.isHeader(entry))
                assertEquals("$g $item", item, l.itemIndexOf(entry))
            }
            var headers = 0
            for (e in 0 until l.entryCount) if (l.isHeader(e)) headers++
            assertEquals(l.sections.size, headers)
        }
    }

    @Test fun `entry of a section is its heading`() {
        val l = LibraryLayout.from(days, GroupBy.MONTH)
        l.sections.indices.forEach { assertTrue(l.isHeader(l.entryOfSection(it))) }
        assertEquals(0, l.entryOfSection(0))
    }

    @Test fun `labels name the sections and the undated one`() {
        val l = LibraryLayout.from(days, GroupBy.MONTH)
        assertEquals("Abril 2024", l.sectionLabel(0))
        assertEquals("Sin fecha de captura", l.sectionLabel(3))
        assertEquals("Marzo 2024", l.labelAt(l.entryOfItem(3)))
    }

    @Test fun `an empty or flat layout has no headings`() {
        val empty = LibraryLayout.from(emptyList(), GroupBy.DAY)
        assertEquals(0, empty.entryCount); assertFalse(empty.hasHeaders)
        val flat = LibraryLayout.flat(5)
        assertEquals(5, flat.entryCount); assertFalse(flat.isHeader(2)); assertEquals(2, flat.itemIndexOf(2)); assertEquals(2, flat.entryOfItem(2))
    }

    @Test fun `jump to a date lands on the closest earlier day when the day has no photos`() {
        val l = LibraryLayout.from(days, GroupBy.DAY) // newest first
        assertEquals(1 to true, l.sectionNear(20240315, true))
        assertEquals("no photos on the 14th: the latest day before it", 2 to false, l.sectionNear(20240314, true))
        assertEquals("after everything: the newest day", 0 to false, l.sectionNear(20250101, true))
        assertEquals("before everything: the oldest dated day, never the undated section", 3 to false, l.sectionNear(19950101, true))
    }

    @Test fun `jump works in the oldest-first order too`() {
        val asc = LibraryLayout.from(listOf(DayCount(20231231, 4), DayCount(20240312, 1), DayCount(20240402, 3), DayCount(0, 2)), GroupBy.DAY)
        assertEquals(1 to true, asc.sectionNear(20240312, false))
        assertEquals("the latest day before the 14th, found from the other side", 1 to false, asc.sectionNear(20240314, false))
        assertEquals(0 to false, asc.sectionNear(19950101, false))
    }

    @Test fun `jump by month or year uses the section granularity`() {
        val l = LibraryLayout.from(days, GroupBy.MONTH)
        assertEquals(1 to true, l.sectionNear(20240320, true))
        assertNull(LibraryLayout.flat(3).sectionNear(20240101, true))
        assertNull("only undated sections: nowhere to jump", LibraryLayout.from(listOf(DayCount(0, 5)), GroupBy.DAY).sectionNear(20240101, true))
    }

    @Test fun `visible range of entries maps to the photos to load`() {
        val l = LibraryLayout.from(days, GroupBy.YEAR) // entries: H 0..5 H 0..3 H 0..1  (headings at 0, 7, 12)
        assertEquals(0..5, l.itemRange(0, 6))
        assertEquals("starting on a heading skips it", 6..9, l.itemRange(7, 11))
        assertEquals("ending on a heading skips it", 4..5, l.itemRange(5, 7))
        assertEquals(10..11, l.itemRange(12, 14))
        assertEquals("out of range values are clamped", 0..11, l.itemRange(-5, 500))
        assertEquals(IntRange.EMPTY, LibraryLayout.flat(0).itemRange(0, 3))
        assertEquals(3..5, LibraryLayout.flat(10).itemRange(3, 5))
    }

    @Test fun `sections of a month year and the undated heading`() {
        val byDay = LibraryLayout.from(days, GroupBy.DAY)
        assertEquals(0, byDay.sectionOfMonth(202404)); assertEquals("the latest day of the month comes first", 1, byDay.sectionOfMonth(202403))
        assertEquals(3, byDay.sectionOfMonth(202312)); assertNull(byDay.sectionOfMonth(202001))
        assertEquals(0, byDay.sectionOfYear(2024)); assertEquals(3, byDay.sectionOfYear(2023)); assertEquals(4, byDay.undatedSection())
        val byMonth = LibraryLayout.from(days, GroupBy.MONTH)
        assertEquals(1, byMonth.sectionOfMonth(202403)); assertEquals(2, byMonth.sectionOfYear(2023))
        val byYear = LibraryLayout.from(days, GroupBy.YEAR)
        assertEquals("a year heading answers for any of its months", 0, byYear.sectionOfMonth(202403)); assertEquals(1, byYear.sectionOfYear(2023))
        assertNull(LibraryLayout.flat(5).sectionOfYear(2024)); assertNull(LibraryLayout.flat(5).undatedSection())
        assertNull(LibraryLayout.from(listOf(DayCount(20240101, 1)), GroupBy.DAY).undatedSection())
    }

    @Test fun `timeline index builds years and months with counts from the same rows`() {
        val t = TimelineIndex(days)
        assertEquals(listOf(2024, 2023), t.years.map { it.year })
        assertEquals(6, t.years[0].count)
        assertEquals(listOf(MonthEntry(202404, 3), MonthEntry(202403, 3)), t.years[0].months)
        assertEquals(listOf(MonthEntry(202312, 4)), t.years[1].months)
        assertEquals(2, t.undated)
        assertEquals(20231231, t.firstDay); assertEquals(20240402, t.lastDay)
        assertEquals(0, TimelineIndex(emptyList()).years.size)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PagedWindowTest {
    private fun TestScope.window(pageSize: Int = 10, maxPages: Int = 3, loads: MutableList<Int>? = null, source: (Int) -> String = { "item$it" }) =
        PagedWindow<String>(this, pageSize, maxPages) { offset, limit ->
            loads?.add(offset)
            (offset until minOf(offset + limit, 1000)).map(source)
        }

    @Test fun `items appear after their page loads, with look-ahead`() = runTest(StandardTestDispatcher()) {
        val loads = mutableListOf<Int>()
        val w = window(loads = loads)
        w.reset(100)
        assertNull(w[5])
        w.ensure(0..4)
        advanceUntilIdle()
        assertEquals("item5", w[5]); assertEquals("item15", w[15]) // next page prefetched
        assertNull(w[25])
        assertEquals(listOf(0, 10), loads.sorted())
    }

    @Test fun `a page is never requested twice while it is loading or loaded`() = runTest(StandardTestDispatcher()) {
        val loads = mutableListOf<Int>()
        val w = window(loads = loads)
        w.reset(100)
        repeat(5) { w.ensure(0..9) }
        advanceUntilIdle()
        repeat(5) { w.ensure(0..9) }
        advanceUntilIdle()
        assertEquals(listOf(0, 10), loads.sorted())
    }

    @Test fun `memory is bounded to the pages nearest the last request`() = runTest(StandardTestDispatcher()) {
        val w = window(maxPages = 3)
        w.reset(1000)
        for (start in listOf(0, 200, 400, 600, 800)) { w.ensure(start..start + 9); advanceUntilIdle() }
        assertTrue("loaded ${w.loadedPages}", w.loadedPages <= 3)
        assertEquals("the area around the last request survives", "item800", w[800])
        assertNull("far away pages were evicted", w[0])
    }

    @Test fun `ensure clamps to the size and ignores empty windows`() = runTest(StandardTestDispatcher()) {
        val loads = mutableListOf<Int>()
        val w = window(loads = loads)
        w.reset(15)
        w.ensure(0..500); advanceUntilIdle()
        assertEquals(listOf(0, 10), loads.sorted())
        w.ensure(IntRange.EMPTY); w.ensure(-50..-1)
        advanceUntilIdle()
        assertEquals(2, loads.size)
        val empty = window(); empty.reset(0); empty.ensure(0..10); advanceUntilIdle()
        assertNull(empty[0])
    }

    @Test fun `invalidate refreshes in place and keeps the old content visible until then`() = runTest(StandardTestDispatcher()) {
        var version = 1
        val w = PagedWindow<String>(this, 10, 3) { offset, limit -> (offset until offset + limit).map { "v$version-$it" } }
        w.reset(30); w.ensure(0..9); advanceUntilIdle()
        assertEquals("v1-3", w[3])
        version = 2
        w.invalidate()
        assertEquals("old content is still there before the reload finishes", "v1-3", w[3])
        advanceUntilIdle()
        assertEquals("v2-3", w[3])
    }

    @Test fun `reset drops loaded pages and ignores answers of the previous query`() = runTest(StandardTestDispatcher()) {
        var q = "a"
        val w = PagedWindow<String>(this, 10, 3) { offset, limit -> (offset until offset + limit).map { "$q$it" } }
        w.reset(30); w.ensure(0..9); runCurrent()
        q = "b"
        w.reset(30)
        assertNull(w[0])
        w.ensure(0..9); advanceUntilIdle()
        assertEquals("b0", w[0])
    }

    @Test fun `a page that fails to load is retried on the next request`() = runTest(StandardTestDispatcher()) {
        var fail = true
        val w = PagedWindow<String>(this, 10, 3) { offset, limit ->
            if (fail) throw IllegalStateException("disk hiccup")
            (offset until offset + limit).map { "x$it" }
        }
        w.reset(20); w.ensure(0..9); advanceUntilIdle()
        assertNull(w[0])
        fail = false
        w.ensure(0..9); advanceUntilIdle()
        assertEquals("x0", w[0])
    }

    @Test fun `invalidate re-reads the pages around the viewport and drops the far ones`() = runTest(StandardTestDispatcher()) {
        val loads = mutableListOf<Int>()
        val w = PagedWindow<String>(this, 10, 20) { offset, limit -> loads += offset / 10; (offset until offset + limit).map { "i$it" } }
        w.reset(1000)
        for (start in listOf(0, 100, 200, 300, 400)) { w.ensure(start..start + 9); advanceUntilIdle() }
        assertTrue("several pages are loaded", w.loadedPages >= 10)
        loads.clear()

        w.invalidate()
        advanceUntilIdle()

        assertTrue("only pages near the last viewport (40..) were re-read: $loads", loads.all { it in 38..42 })
        assertNull("a far page is not kept stale", w[0])
        assertEquals("the viewport is still there", "i400", w[400])
    }

    @Test fun `a page that was still loading when the data changed is asked for again`() = runTest(StandardTestDispatcher()) {
        var version = 1
        val w = PagedWindow<String>(this, 10, 3) { offset, limit -> delay(10); (offset until offset + limit).map { "v$version-$it" } }
        w.reset(30)
        w.ensure(0..9); runCurrent() // the read of page 0 has started and is waiting
        version = 2
        w.invalidate() // the answer in flight is now stale and will be dropped

        advanceUntilIdle()

        assertEquals("the page is not lost: it was re-read after the change", "v2-3", w[3])
    }

    @Test fun `list source is a window over a plain list`() {
        val s = ListSource(listOf("a", "b"))
        assertEquals(2, s.size); assertEquals("b", s[1]); assertNull(s[2]); s.ensure(0..1)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryBrowserTest {
    private val query = MutableStateFlow(LibraryQuery())

    /** The browser and its window collect forever: cancel them when the test body ends so runTest can finish. */
    private fun browserTest(body: suspend TestScope.() -> Unit) = runTest(StandardTestDispatcher()) {
        try { body() } finally { coroutineContext[kotlinx.coroutines.Job]!!.children.forEach { it.cancel() } }
    }

    private class Fake {
        val days = MutableSharedFlow<List<DayCount>>(replay = 1)
        val counts = MutableSharedFlow<Int>(replay = 1)
        val pageLoads = mutableListOf<Triple<LibraryQuery, Int, Int>>()
    }

    private fun TestScope.browser(f: Fake, settle: Long = 100) = LibraryBrowser<String>(
        scope = this, library = LibraryScope.LOCAL, queries = query,
        observeCount = { f.counts }, observeDays = { f.days },
        loadPage = { q, off, lim -> f.pageLoads += Triple(q, off, lim); (off until off + lim).map { "${q.search}#$it" } },
        pageSize = 10, settleMs = settle, computeContext = kotlin.coroutines.EmptyCoroutineContext,
    )

    @Test fun `grouped queries observe one row per day and expose the layout`() = browserTest {
        val f = Fake(); val b = browser(f)
        f.days.emit(listOf(DayCount(20240402, 3), DayCount(20240312, 2)))
        advanceUntilIdle()
        val s = b.state.value
        assertTrue(s.ready); assertEquals(5, s.total); assertEquals(7, s.layout.entryCount); assertEquals(2, s.days.size)
        assertEquals(5, b.window.size)
        b.window.ensure(0..4); advanceUntilIdle()
        assertEquals("#2", b.window[2])
    }

    @Test fun `flat queries only count`() = browserTest {
        val f = Fake(); query.value = LibraryQuery(sort = SortField.NAME)
        val b = browser(f)
        f.counts.emit(42); advanceUntilIdle()
        assertEquals(42, b.state.value.total); assertFalse(b.state.value.layout.hasHeaders); assertEquals(42, b.state.value.layout.entryCount)
        query.value = LibraryQuery()
    }

    @Test fun `nothing is ready before the first answer`() = browserTest {
        val f = Fake(); val b = browser(f)
        advanceUntilIdle()
        assertFalse(b.state.value.ready)
    }

    @Test fun `a new query starts the window over and loads with that query`() = browserTest {
        val f = Fake(); val b = browser(f)
        f.days.emit(listOf(DayCount(20240402, 30))); advanceUntilIdle()
        b.window.ensure(0..9); advanceUntilIdle()
        assertEquals("#0", b.window[0])

        query.value = LibraryQuery(search = "mar")
        f.days.emit(listOf(DayCount(20240312, 12))); advanceUntilIdle()
        assertEquals(12, b.state.value.total)
        assertNull("old query pages are gone", b.window[0])
        b.window.ensure(0..9); advanceUntilIdle()
        assertEquals("mar#0", b.window[0])
        assertEquals("mar", f.pageLoads.last().first.search)
        query.value = LibraryQuery()
    }

    @Test fun `data changes of the same query refresh in place after a short settle time`() = browserTest {
        val f = Fake(); val b = browser(f, settle = 100)
        f.days.emit(listOf(DayCount(20240402, 30))); advanceUntilIdle()
        b.window.ensure(0..9); advanceUntilIdle()
        val loadsBefore = f.pageLoads.size

        f.days.emit(listOf(DayCount(20240402, 31)))
        runCurrent()
        assertEquals("the change is merged for a moment before it is applied", 30, b.state.value.total)
        advanceTimeBy(150); runCurrent()
        assertEquals(31, b.state.value.total)
        assertEquals(31, b.window.size)
        advanceUntilIdle()
        assertTrue("loaded pages were re-read", f.pageLoads.size > loadsBefore)
        assertEquals("#0", b.window[0])
    }

    @Test fun `a change that moves no photo keeps the layout and still refreshes the cells`() = browserTest {
        val f = Fake(); val b = browser(f, settle = 100)
        f.days.emit(listOf(DayCount(20240402, 30))); advanceUntilIdle()
        b.window.ensure(0..9); advanceUntilIdle()
        val layout = b.state.value.layout
        val loadsBefore = f.pageLoads.size

        f.days.emit(listOf(DayCount(20240402, 30))) // a badge changed: same days, same counts
        advanceUntilIdle()

        assertSame("the grid is not rebuilt for a badge", layout, b.state.value.layout)
        assertTrue("but the visible cells are re-read", f.pageLoads.size > loadsBefore)
    }

    @Test fun `a burst of changes is applied once, with the latest data`() = browserTest {
        val f = Fake(); val b = browser(f, settle = 100)
        f.days.emit(listOf(DayCount(20240402, 10))); advanceUntilIdle()
        for (n in 11..20) { f.days.emit(listOf(DayCount(20240402, n))); runCurrent() }
        advanceUntilIdle()
        assertEquals(20, b.state.value.total)
    }
}
