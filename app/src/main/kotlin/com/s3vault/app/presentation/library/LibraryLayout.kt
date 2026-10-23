package com.s3vault.app.presentation.library

import com.s3vault.app.data.local.DayCount
import com.s3vault.app.domain.library.DateKeys
import com.s3vault.app.domain.library.GroupBy
import com.s3vault.app.domain.library.Section

/**
 * Maps positions of the lazy grid to photos and headings. The grid has one entry per photo plus one per heading, but
 * only the headings (a few per screenful of years) are stored: everything else is arithmetic, so the layout of a
 * 100 000 photo library takes a few KB and a binary search per lookup.
 *
 * With [GroupBy.NONE] (or before the first count arrives) there are no headings and entry == item index.
 */
class LibraryLayout(val sections: List<Section>, val group: GroupBy, val itemCount: Int) {
    private val grouped = group != GroupBy.NONE && sections.isNotEmpty()

    /** Entry index of each heading, ascending. */
    private val headerEntry = IntArray(if (grouped) sections.size else 0)

    /** Item index of the first photo under each heading. */
    private val firstItem = IntArray(if (grouped) sections.size else 0)

    val entryCount: Int

    init {
        var entry = 0
        var item = 0
        if (grouped) {
            for ((i, s) in sections.withIndex()) {
                headerEntry[i] = entry
                firstItem[i] = item
                entry += 1 + s.count
                item += s.count
            }
        } else {
            entry = itemCount
        }
        entryCount = entry
    }

    val hasHeaders: Boolean get() = grouped

    /** Index of the heading at or before [entry] (the section [entry] belongs to). */
    fun sectionOfEntry(entry: Int): Int {
        if (!grouped) return -1
        var lo = 0
        var hi = headerEntry.size - 1
        var found = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (headerEntry[mid] <= entry) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    fun isHeader(entry: Int): Boolean = grouped && headerEntry[sectionOfEntry(entry)] == entry

    /** Photo index of a grid entry, or -1 for a heading. */
    fun itemIndexOf(entry: Int): Int {
        if (!grouped) return entry
        val s = sectionOfEntry(entry)
        return if (headerEntry[s] == entry) -1 else firstItem[s] + (entry - headerEntry[s] - 1)
    }

    fun entryOfItem(item: Int): Int {
        if (!grouped) return item
        return item + sectionOfItem(item) + 1
    }

    fun sectionOfItem(item: Int): Int {
        if (!grouped) return -1
        var lo = 0
        var hi = firstItem.size - 1
        var found = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (firstItem[mid] <= item) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    fun entryOfSection(section: Int): Int = if (grouped) headerEntry[section.coerceIn(0, headerEntry.size - 1)] else 0

    fun sectionLabel(section: Int): String = if (grouped) DateKeys.sectionLabel(sections[section].key, group) else ""

    /** Label for the date bubble of the fast scroller at [entry]. */
    fun labelAt(entry: Int): String = if (grouped) sectionLabel(sectionOfEntry(entry)) else ""

    /** Photos shown by the grid entries [firstEntry]..[lastEntry] (headings skipped): the range worth loading. */
    fun itemRange(firstEntry: Int, lastEntry: Int): IntRange {
        if (itemCount == 0 || entryCount == 0) return IntRange.EMPTY
        var a = firstEntry.coerceIn(0, entryCount - 1)
        var b = lastEntry.coerceIn(0, entryCount - 1)
        if (isHeader(a) && a < entryCount - 1) a++
        if (isHeader(b) && b > 0) b--
        val first = itemIndexOf(a).coerceIn(0, itemCount - 1)
        val last = itemIndexOf(b).coerceIn(0, itemCount - 1)
        return first..maxOf(first, last)
    }

    private fun yearOf(key: Int) = when (group) { GroupBy.DAY -> key / 10_000; GroupBy.MONTH -> key / 100; else -> key }

    /** The first heading of [monthKey] (`yyyyMM`), or of its year when headings are yearly; null when there is none. */
    fun sectionOfMonth(monthKey: Int): Int? {
        if (!grouped) return null
        val i = sections.indexOfFirst {
            it.key != 0 && when (group) { GroupBy.DAY -> it.key / 100 == monthKey; GroupBy.MONTH -> it.key == monthKey; else -> it.key == monthKey / 100 }
        }
        return i.takeIf { it >= 0 }
    }

    fun sectionOfYear(year: Int): Int? {
        if (!grouped) return null
        return sections.indexOfFirst { it.key != 0 && yearOf(it.key) == year }.takeIf { it >= 0 }
    }

    /** The "no capture date" heading, if any. */
    fun undatedSection(): Int? = if (grouped) sections.indexOfFirst { it.key == 0 }.takeIf { it >= 0 } else null

    /**
     * The section to show for [dayKey]: the one holding that day, or, when there is none, the closest one *before it in
     * time* (so jumping to "12 March" with no photos that day lands on the latest day up to it). Returns null when the
     * list has no dated section; [exact] tells whether the day itself is there.
     */
    fun sectionNear(dayKey: Int, descending: Boolean): Pair<Int, Boolean>? {
        if (!grouped) return null
        val target = DateKeys.groupKey(dayKey, group)
        val dated = sections.indices.filter { sections[it].key != 0 }
        if (dated.isEmpty()) return null
        dated.firstOrNull { sections[it].key == target }?.let { return it to true }
        val atOrBefore = if (descending) dated.firstOrNull { sections[it].key < target } else dated.lastOrNull { sections[it].key < target }
        return (atOrBefore ?: if (descending) dated.last() else dated.first()) to false
    }

    companion object {
        /** Builds the sections of [group] from one row per day (ordered as the list is), merging days of the same month or year. */
        fun from(days: List<DayCount>, group: GroupBy): LibraryLayout {
            val total = days.sumOf { it.n }
            if (group == GroupBy.NONE) return LibraryLayout(emptyList(), group, total)
            val sections = ArrayList<Section>()
            for (d in days) {
                val key = DateKeys.groupKey(d.day, group)
                val last = sections.lastOrNull()
                if (last != null && last.key == key) sections[sections.size - 1] = last.copy(count = last.count + d.n) else sections += Section(key, d.n)
            }
            return LibraryLayout(sections, group, total)
        }

        fun flat(itemCount: Int) = LibraryLayout(emptyList(), GroupBy.NONE, itemCount)
    }
}

data class MonthEntry(val monthKey: Int, val count: Int)
data class YearEntry(val year: Int, val count: Int, val months: List<MonthEntry>)

/**
 * The calendar of what exists (years, then months, with counts) for the "go to date" sheet. Built from the same one row per
 * day the grid uses, so it always agrees with the list and costs no extra query.
 */
class TimelineIndex(days: List<DayCount>) {
    val years: List<YearEntry>

    /** Photos without a capture date (day key 0), shown apart. */
    val undated: Int

    /** Earliest and latest day with photos, for the date picker bounds. */
    val firstDay: Int?
    val lastDay: Int?

    init {
        val dated = days.filter { it.day > 0 }
        undated = days.firstOrNull { it.day == 0 }?.n ?: 0
        firstDay = dated.minOfOrNull { it.day }
        lastDay = dated.maxOfOrNull { it.day }
        val byMonth = LinkedHashMap<Int, Int>()
        for (d in dated.sortedByDescending { it.day }) byMonth.merge(DateKeys.monthKey(d.day), d.n, Int::plus)
        years = byMonth.entries.groupBy { it.key / 100 }.map { (year, months) ->
            YearEntry(year, months.sumOf { it.value }, months.map { MonthEntry(it.key, it.value) })
        }
    }
}
