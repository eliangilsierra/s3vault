package com.s3vault.app.data.local

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.s3vault.app.domain.MediaKind
import com.s3vault.app.domain.library.BackupFilter
import com.s3vault.app.domain.library.CloudAvailability
import com.s3vault.app.domain.library.GroupBy
import com.s3vault.app.domain.library.LibraryQuery
import com.s3vault.app.domain.library.ParsedSearch
import com.s3vault.app.domain.library.SearchParser
import com.s3vault.app.domain.library.SortField
import java.time.LocalDate

/** One row per day of the (filtered) library: the whole timeline in a few KB, however many photos there are. */
data class DayCount(val day: Int, val n: Int)

data class FolderCount(val name: String, val n: Int)

/** Collects AND-ed conditions and their arguments. Values are always bound, never concatenated into the SQL. */
private class Where {
    val sql = ArrayList<String>()
    val args = ArrayList<Any?>()

    fun and(clause: String, vararg values: Any?) { sql += clause; args.addAll(values) }

    fun render(): String = if (sql.isEmpty()) "1" else sql.joinToString(" AND ") { "($it)" }
}

/** `%` and `_` typed by the user are literal characters, not wildcards. */
internal fun likeContains(term: String): String =
    "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

private fun inList(column: String, values: Collection<String>, w: Where) {
    w.and("$column IN (${values.joinToString(",") { "?" }})", *values.toTypedArray())
}

/**
 * SQL for the local gallery over `media_items`. Everything the user can ask for (search, date, type, backup status, folder,
 * sort) is answered by indexed columns that already exist, so the screen needs no more than one page of rows at a time.
 */
object LocalLibrarySql {
    private fun where(q: LibraryQuery, parsed: ParsedSearch): Where {
        val w = Where()
        w.and("present = 1")
        for (t in parsed.textTerms) {
            val like = likeContains(t)
            w.and("displayName LIKE ? ESCAPE '\\' OR folderName LIKE ? ESCAPE '\\'", like, like)
        }
        val kinds = intersectKinds(parsed.kind, q.filters.kinds)
        if (kinds != null) { if (kinds.isEmpty()) w.and("0") else inList("kind", kinds.map { it.name }, w) }
        parsed.dateRange?.let { w.and("dayKey BETWEEN ? AND ?", it.fromDay, it.toDay) }
        q.filters.dateRange?.let { w.and("dayKey BETWEEN ? AND ?", it.fromDay, it.toDay) }
        parsed.month?.let { w.and("(dayKey / 100) % 100 = ?", it) }
        parsed.dayOfMonth?.let { w.and("dayKey % 100 = ?", it) }
        if (q.filters.backup.isNotEmpty()) {
            val states = q.filters.backup.flatMap {
                when (it) {
                    BackupFilter.BACKED_UP -> listOf("BACKED_UP")
                    BackupFilter.NOT_BACKED_UP -> listOf("LOCAL_ONLY")
                    BackupFilter.IN_PROGRESS -> listOf("QUEUED", "UPLOADING", "VERIFYING")
                    BackupFilter.FAILED -> listOf("FAILED")
                }
            }
            inList("syncState", states, w)
        }
        if (q.filters.folders.isNotEmpty()) inList("folderName", q.filters.folders, w)
        if (q.filters.undatedOnly) w.and("dateSource = 'FALLBACK'")
        q.filters.minSizeBytes?.let { w.and("sizeBytes >= ?", it) }
        return w
    }

    private fun orderBy(q: LibraryQuery): String {
        val dir = if (q.descending) "DESC" else "ASC"
        return when (q.sort) {
            SortField.CAPTURE_DATE, SortField.UPLOAD_DATE -> "dateTakenMs $dir, mediaStoreId $dir"
            SortField.MODIFIED_DATE -> "dateModifiedSec $dir, mediaStoreId $dir"
            SortField.NAME -> "displayName COLLATE NOCASE $dir, mediaStoreId $dir"
            SortField.SIZE -> "sizeBytes $dir, mediaStoreId $dir"
        }
    }

    private fun sortExpr(q: LibraryQuery) = when (q.sort) {
        SortField.CAPTURE_DATE, SortField.UPLOAD_DATE -> "dateTakenMs"
        SortField.MODIFIED_DATE -> "dateModifiedSec"
        SortField.NAME -> "displayName COLLATE NOCASE"
        SortField.SIZE -> "sizeBytes"
    }

    private fun parsed(q: LibraryQuery, today: LocalDate) = SearchParser.parse(q.search, today)

    private fun q(sql: String, args: List<Any?>): SupportSQLiteQuery = SimpleSQLiteQuery(sql, args.toTypedArray())

    fun rows(q: LibraryQuery, limit: Int, offset: Int, today: LocalDate = LocalDate.now()): SupportSQLiteQuery {
        val w = where(q, parsed(q, today))
        return q("SELECT * FROM media_items WHERE ${w.render()} ORDER BY ${orderBy(q)} LIMIT ? OFFSET ?", w.args + limit + offset)
    }

    fun count(q: LibraryQuery, today: LocalDate = LocalDate.now()): SupportSQLiteQuery {
        val w = where(q, parsed(q, today))
        return q("SELECT COUNT(*) FROM media_items WHERE ${w.render()}", w.args)
    }

    /** The timeline: photos per day, in the same order as [rows] when sorted by capture date. */
    fun dayCounts(q: LibraryQuery, today: LocalDate = LocalDate.now()): SupportSQLiteQuery {
        val w = where(q, parsed(q, today))
        val dir = if (q.descending) "DESC" else "ASC"
        return q("SELECT dayKey AS day, COUNT(*) AS n FROM media_items WHERE ${w.render()} GROUP BY dayKey ORDER BY dayKey $dir", w.args)
    }

    fun ids(q: LibraryQuery, section: Pair<GroupBy, Int>? = null, today: LocalDate = LocalDate.now()): SupportSQLiteQuery {
        val w = where(q, parsed(q, today))
        section?.let { (g, key) -> w.and(sectionClause("dayKey", g), key) }
        return q("SELECT mediaStoreId FROM media_items WHERE ${w.render()} ORDER BY ${orderBy(q)}", w.args)
    }

    /** Index of [item] in the list [rows] returns: how many rows come before it. Used to open the viewer on the right photo. */
    fun positionOf(q: LibraryQuery, item: MediaItemEntity, today: LocalDate = LocalDate.now()): SupportSQLiteQuery {
        val w = where(q, parsed(q, today))
        val cmp = if (q.descending) ">" else "<"
        val expr = sortExpr(q)
        val value: Any = when (q.sort) {
            SortField.CAPTURE_DATE, SortField.UPLOAD_DATE -> item.dateTakenMs
            SortField.MODIFIED_DATE -> item.dateModifiedSec
            SortField.NAME -> item.displayName
            SortField.SIZE -> item.sizeBytes
        }
        w.and("$expr $cmp ? OR ($expr = ? AND mediaStoreId $cmp ?)", value, value, item.mediaStoreId)
        return q("SELECT COUNT(*) FROM media_items WHERE ${w.render()}", w.args)
    }

    /** The folders that exist, for the filter sheet. */
    const val FOLDERS = "SELECT folderName AS name, COUNT(*) AS n FROM media_items WHERE present = 1 AND folderName != '' GROUP BY folderName ORDER BY n DESC, folderName LIMIT 60"
}

/**
 * SQL for the S3 gallery over the cached listing `remote_objects`. Nothing here talks to S3: it filters what was already
 * listed. Date filters and searches look at the capture date only; objects without one (dayKey 0) never match them and are
 * shown in their own section at the end.
 */
object CloudLibrarySql {
    private const val ALSO_LOCAL =
        "(EXISTS (SELECT 1 FROM media_items m WHERE m.remoteKey = remote_objects.`key` AND m.present = 1) OR restoredUri IS NOT NULL)"

    private fun where(q: LibraryQuery, parsed: ParsedSearch): Where {
        val w = Where()
        for (t in parsed.textTerms) w.and("displayName LIKE ? ESCAPE '\\'", likeContains(t))
        val kinds = intersectKinds(parsed.kind, q.filters.kinds)
        if (kinds != null) { if (kinds.isEmpty()) w.and("0") else inList("mediaKind", kinds.map { it.name }, w) }
        val dated = parsed.hasDate || q.filters.dateRange != null
        if (dated) w.and("dayKey > 0")
        parsed.dateRange?.let { w.and("dayKey BETWEEN ? AND ?", it.fromDay, it.toDay) }
        q.filters.dateRange?.let { w.and("dayKey BETWEEN ? AND ?", it.fromDay, it.toDay) }
        parsed.month?.let { w.and("(dayKey / 100) % 100 = ?", it) }
        parsed.dayOfMonth?.let { w.and("dayKey % 100 = ?", it) }
        when (q.filters.availability) {
            CloudAvailability.ALSO_LOCAL -> w.and(ALSO_LOCAL)
            CloudAvailability.CLOUD_ONLY -> w.and("NOT $ALSO_LOCAL")
            null -> Unit
        }
        // Objects with no capture date are the ones listed apart (dayKey 0).
        if (q.filters.undatedOnly) w.and("dayKey = 0")
        q.filters.minSizeBytes?.let { w.and("sizeBytes >= ?", it) }
        return w
    }

    private fun orderBy(q: LibraryQuery): String {
        val dir = if (q.descending) "DESC" else "ASC"
        return when (q.sort) {
            // Objects without a capture date always come last, whatever the direction.
            SortField.CAPTURE_DATE, SortField.MODIFIED_DATE -> "(dayKey = 0) ASC, effectiveDateMs $dir, `key` $dir"
            SortField.UPLOAD_DATE -> "lastModifiedMs $dir, `key` $dir"
            SortField.NAME -> "displayName COLLATE NOCASE $dir, `key` $dir"
            SortField.SIZE -> "sizeBytes $dir, `key` $dir"
        }
    }

    private fun parsed(q: LibraryQuery, today: LocalDate) = SearchParser.parse(q.search, today)

    private fun q(sql: String, args: List<Any?>): SupportSQLiteQuery = SimpleSQLiteQuery(sql, args.toTypedArray())

    fun rows(q: LibraryQuery, limit: Int, offset: Int, today: LocalDate = LocalDate.now()): SupportSQLiteQuery {
        val w = where(q, parsed(q, today))
        return q("SELECT * FROM remote_objects WHERE ${w.render()} ORDER BY ${orderBy(q)} LIMIT ? OFFSET ?", w.args + limit + offset)
    }

    fun count(q: LibraryQuery, today: LocalDate = LocalDate.now()): SupportSQLiteQuery {
        val w = where(q, parsed(q, today))
        return q("SELECT COUNT(*) FROM remote_objects WHERE ${w.render()}", w.args)
    }

    /** Objects per day (of the capture date, or of the upload date when sorted by it). Day 0 is "no capture date". */
    fun dayCounts(q: LibraryQuery, today: LocalDate = LocalDate.now()): SupportSQLiteQuery {
        val w = where(q, parsed(q, today))
        val dir = if (q.descending) "DESC" else "ASC"
        return if (q.sort == SortField.UPLOAD_DATE) {
            q("SELECT uploadDayKey AS day, COUNT(*) AS n FROM remote_objects WHERE ${w.render()} GROUP BY uploadDayKey ORDER BY uploadDayKey $dir", w.args)
        } else {
            q("SELECT dayKey AS day, COUNT(*) AS n FROM remote_objects WHERE ${w.render()} GROUP BY dayKey ORDER BY (dayKey = 0) ASC, dayKey $dir", w.args)
        }
    }

    /** Keys of everything that matches (optionally one section), for "select all" and "select this day". */
    fun keys(q: LibraryQuery, section: Pair<GroupBy, Int>? = null, today: LocalDate = LocalDate.now()): SupportSQLiteQuery {
        val w = where(q, parsed(q, today))
        section?.let { (g, key) ->
            val col = if (q.sort == SortField.UPLOAD_DATE) "uploadDayKey" else "dayKey"
            if (key == 0 && col == "dayKey") w.and("dayKey = 0") else w.and(sectionClause(col, g), key)
        }
        return q("SELECT `key` FROM remote_objects WHERE ${w.render()} ORDER BY ${orderBy(q)}", w.args)
    }

    fun positionOf(q: LibraryQuery, item: RemoteObjectEntity, today: LocalDate = LocalDate.now()): SupportSQLiteQuery {
        val w = where(q, parsed(q, today))
        val cmp = if (q.descending) ">" else "<"
        val tie = "`key` $cmp ?"
        when (q.sort) {
            SortField.CAPTURE_DATE, SortField.MODIFIED_DATE -> {
                if (item.dayKey > 0) {
                    w.and("dayKey > 0 AND (effectiveDateMs $cmp ? OR (effectiveDateMs = ? AND $tie))", item.effectiveDateMs, item.effectiveDateMs, item.key)
                } else {
                    // Undated objects come after every dated one, then by their own order.
                    w.and("dayKey > 0 OR effectiveDateMs $cmp ? OR (effectiveDateMs = ? AND $tie)", item.effectiveDateMs, item.effectiveDateMs, item.key)
                }
            }
            SortField.UPLOAD_DATE -> w.and("lastModifiedMs $cmp ? OR (lastModifiedMs = ? AND $tie)", item.lastModifiedMs, item.lastModifiedMs, item.key)
            SortField.NAME -> w.and("displayName COLLATE NOCASE $cmp ? OR (displayName COLLATE NOCASE = ? AND $tie)", item.displayName, item.displayName, item.key)
            SortField.SIZE -> w.and("sizeBytes $cmp ? OR (sizeBytes = ? AND $tie)", item.sizeBytes, item.sizeBytes, item.key)
        }
        return q("SELECT COUNT(*) FROM remote_objects WHERE ${w.render()}", w.args)
    }
}

private fun sectionClause(column: String, g: GroupBy) = when (g) {
    GroupBy.MONTH -> "$column / 100 = ?"
    GroupBy.YEAR -> "$column / 10000 = ?"
    else -> "$column = ?"
}

/** Both the search ("videos") and the type filter can name a kind: only what both allow can match. */
private fun intersectKinds(fromSearch: MediaKind?, fromFilter: Set<MediaKind>): Set<MediaKind>? = when {
    fromSearch == null && fromFilter.isEmpty() -> null
    fromSearch == null -> fromFilter
    fromFilter.isEmpty() -> setOf(fromSearch)
    else -> fromFilter.intersect(setOf(fromSearch))
}
