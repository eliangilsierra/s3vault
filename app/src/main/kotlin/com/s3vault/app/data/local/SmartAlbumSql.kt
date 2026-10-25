package com.s3vault.app.data.local

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.s3vault.app.domain.library.GroupBy
import com.s3vault.app.domain.smart.SmartAlbum
import com.s3vault.app.domain.smart.SmartBackup
import com.s3vault.app.domain.smart.SmartFilter
import com.s3vault.app.domain.space.SafetyRules
import com.s3vault.app.domain.space.SpaceBucket

data class YearCount(val year: Int, val n: Int)

/**
 * The SQL of the smart albums. A filter becomes one WHERE clause over `media_items m` (joined to the audit results, which only
 * "listos para liberar espacio" reads), always with bound values. The count of every built-in album is computed in a single pass
 * over the table with conditional sums built from the very same clauses the detail view uses, so a count and the album it opens
 * can never disagree and showing the albums does not cost one scan per album.
 */
object SmartAlbumSql {
    private const val DAY_MS = 24L * 3600 * 1000
    private const val FROM = "media_items m LEFT JOIN integrity_results r ON r.remoteKey = m.remoteKey"

    /** Folders Android and manufacturers use for screenshots. A name match: it is the only signal MediaStore offers on every version. */
    const val SCREENSHOT_SQL = "(LOWER(m.folderName) LIKE '%screenshot%' OR LOWER(m.folderName) LIKE '%captura%de%pantalla%')"

    class Clause(val sql: String, val args: List<Any?>)

    fun where(f: SmartFilter, rules: SafetyRules, nowMs: Long): Clause {
        val parts = ArrayList<String>()
        val args = ArrayList<Any?>()
        parts += "m.present = 1"
        if (f.kinds.isNotEmpty()) { parts += "m.kind IN (${f.kinds.joinToString(",") { "?" }})"; args.addAll(f.kinds.map { it.name }) }
        if (f.favorites) parts += "m.isFavorite = 1"
        if (f.screenshots) parts += SCREENSHOT_SQL
        f.minSizeBytes?.let { parts += "m.sizeBytes >= ?"; args += it }
        f.addedWithinDays?.let { parts += "m.dateAddedSec >= ?"; args += (nowMs - it * DAY_MS) / 1000 }
        f.dateRange?.let { parts += "m.dayKey BETWEEN ? AND ?"; args += it.fromDay; args += it.toDay }
        when (f.backup) {
            SmartBackup.NOT_BACKED_UP -> parts += "m.syncState IN ('LOCAL_ONLY', 'FAILED')"
            SmartBackup.BACKED_UP -> parts += "m.syncState = 'BACKED_UP'"
            null -> Unit
        }
        if (f.recoverable) {
            val b = SpaceRecoverySql.bucketExpr(rules, nowMs)
            parts += "m.syncState = 'BACKED_UP' AND m.remoteKey IS NOT NULL AND (${b.sql}) = ?"
            args.addAll(b.args); args += SpaceBucket.VERIFIED.name
        }
        f.restoredWithinDays?.let { parts += "m.dateAddedSec >= ?"; args += (nowMs - it * DAY_MS) / 1000 }
        if (f.missingMetadata) parts += "(m.dateSource = 'FALLBACK' OR m.width <= 0 OR m.height <= 0)"
        if (f.folders.isNotEmpty()) { parts += "m.folderName IN (${f.folders.joinToString(",") { "?" }})"; args.addAll(f.folders.sorted()) }
        if (f.nameContains.isNotBlank()) { parts += "m.displayName LIKE ? ESCAPE '\\'"; args += likeContains(f.nameContains.trim()) }
        return Clause(parts.joinToString(" AND ") { "($it)" }, args)
    }

    private fun q(sql: String, args: List<Any?>): SupportSQLiteQuery = SimpleSQLiteQuery(sql, args.toTypedArray())

    fun count(f: SmartFilter, rules: SafetyRules, nowMs: Long): SupportSQLiteQuery {
        val w = where(f, rules, nowMs)
        return q("SELECT COUNT(*) FROM $FROM WHERE ${w.sql}", w.args)
    }

    fun rows(f: SmartFilter, rules: SafetyRules, nowMs: Long, descending: Boolean, limit: Int, offset: Int): SupportSQLiteQuery {
        val w = where(f, rules, nowMs)
        val dir = if (descending) "DESC" else "ASC"
        return q("SELECT m.* FROM $FROM WHERE ${w.sql} ORDER BY m.dateTakenMs $dir, m.mediaStoreId $dir LIMIT ? OFFSET ?", w.args + limit + offset)
    }

    fun dayCounts(f: SmartFilter, rules: SafetyRules, nowMs: Long, descending: Boolean): SupportSQLiteQuery {
        val w = where(f, rules, nowMs)
        return q("SELECT m.dayKey AS day, COUNT(*) AS n FROM $FROM WHERE ${w.sql} GROUP BY m.dayKey ORDER BY m.dayKey ${if (descending) "DESC" else "ASC"}", w.args)
    }

    fun ids(f: SmartFilter, rules: SafetyRules, nowMs: Long): SupportSQLiteQuery {
        val w = where(f, rules, nowMs)
        return q("SELECT m.mediaStoreId FROM $FROM WHERE ${w.sql} ORDER BY m.dateTakenMs DESC, m.mediaStoreId DESC", w.args)
    }

    /**
     * The count of every album in one scan: `SUM(CASE WHEN <album's clause> ...)` per album. Columns are `c0`, `c1`... in the order
     * of [albums]. Only rows that are present are read, and the clause of each album repeats `m.present = 1` harmlessly.
     */
    fun countsOf(albums: List<SmartAlbum>, rules: SafetyRules, nowMs: Long): SupportSQLiteQuery {
        val sums = ArrayList<String>()
        val args = ArrayList<Any?>()
        albums.forEachIndexed { i, a ->
            val w = where(a.filter, rules, nowMs)
            sums += "COALESCE(SUM(CASE WHEN ${w.sql} THEN 1 ELSE 0 END), 0) AS c$i"
            args.addAll(w.args)
        }
        return q("SELECT ${sums.joinToString(", ")} FROM $FROM WHERE m.present = 1", args)
    }



    private fun likeContains(term: String): String = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
}
