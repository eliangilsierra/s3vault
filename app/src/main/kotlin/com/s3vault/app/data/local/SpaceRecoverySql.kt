package com.s3vault.app.data.local

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.s3vault.app.domain.space.SafetyRules
import com.s3vault.app.domain.space.SpaceBucket
import com.s3vault.app.domain.space.SpaceGrouping

data class SpaceBucketRow(val cls: String, val n: Int, val bytes: Long)

data class SpaceGroupRow(val gkey: String?, val n: Int, val bytes: Long)

/**
 * One SQL expression decides, for every backed-up file on the device, which [SpaceBucket] it is in: a remote problem first, then
 * the user's rules, then how fresh the proof is that S3 holds it. Counts, sizes, the list and "select this group" all use that
 * same expression, so they can never disagree. Values are bound, never concatenated. It reads two tables with indexes
 * (`media_items.syncState`, `integrity_results` by key) and runs once per screen change, not once per row.
 */
object SpaceRecoverySql {
    private const val DAY_MS = 24L * 3600 * 1000
    private const val HOUR_MS = 3600L * 1000

    private const val FROM = "media_items m LEFT JOIN integrity_results r ON r.remoteKey = m.remoteKey"
    private const val BASE = "m.present = 1 AND m.syncState = 'BACKED_UP' AND m.remoteKey IS NOT NULL"

    /** The CASE expression and the values for its placeholders, in order. */
    class Fragment(val sql: String, val args: List<Any?>)

    fun bucketExpr(rules: SafetyRules, nowMs: Long): Fragment {
        val whens = ArrayList<String>()
        val args = ArrayList<Any?>()
        // A remote problem, or a file that changed since the backup, outranks every preference of the user.
        whens += "WHEN r.verdict IN ('MISSING', 'STALE_RECORD') THEN 'MISSING'"
        whens += "WHEN r.verdict = 'CHANGED' OR (m.backedUpSizeBytes IS NOT NULL AND (m.backedUpSizeBytes != m.sizeBytes OR m.backedUpDateModifiedSec != m.dateModifiedSec)) THEN 'OUTDATED'"
        if (rules.excludeFavorites) whens += "WHEN m.isFavorite = 1 THEN 'EXCL_FAVORITE'"
        if (!rules.includeVideos) whens += "WHEN m.kind = 'VIDEO' THEN 'EXCL_VIDEO'"
        if (rules.keepRecentDays > 0) { whens += "WHEN m.dateTakenMs > ? THEN 'EXCL_RECENT'"; args += nowMs - rules.keepRecentDays * DAY_MS }
        if (rules.minBackedUpDays > 0) {
            whens += "WHEN m.lastBackupAtMs IS NULL OR m.lastBackupAtMs > ? THEN 'EXCL_NEW_BACKUP'"; args += nowMs - rules.minBackedUpDays * DAY_MS
        }
        if (rules.excludedFolders.isNotEmpty()) {
            whens += "WHEN m.folderName IN (${rules.excludedFolders.joinToString(",") { "?" }}) THEN 'EXCL_FOLDER'"; args.addAll(rules.excludedFolders.sorted())
        }
        if (rules.keepAlbumPhotos) whens += "WHEN EXISTS (SELECT 1 FROM album_items ai WHERE ai.mediaStoreId = m.mediaStoreId) THEN 'EXCL_ALBUM'"
        val fresh = nowMs - rules.verifiedWithinHours * HOUR_MS
        // Evidence: an audit that confirmed it recently, or (with no audit) a backup that was itself verified recently.
        whens += "WHEN r.verdict = 'VERIFIED' AND r.checkedAtMs >= ? THEN 'VERIFIED'"; args += fresh
        whens += "WHEN r.remoteKey IS NULL AND m.lastBackupAtMs >= ? THEN 'VERIFIED'"; args += fresh
        return Fragment("CASE ${whens.joinToString(" ")} ELSE 'UNVERIFIED' END", args)
    }

    private fun groupExpr(g: SpaceGrouping) = when (g) {
        SpaceGrouping.MONTH -> "CAST(m.dayKey / 100 AS TEXT)"
        SpaceGrouping.YEAR -> "CAST(m.dayKey / 10000 AS TEXT)"
        SpaceGrouping.FOLDER -> "m.folderName"
    }

    private fun order(g: SpaceGrouping) = when (g) {
        SpaceGrouping.MONTH -> "m.dayKey / 100 DESC"
        SpaceGrouping.YEAR -> "m.dayKey / 10000 DESC"
        SpaceGrouping.FOLDER -> "m.folderName COLLATE NOCASE ASC"
    }

    private fun q(sql: String, args: List<Any?>): SupportSQLiteQuery = SimpleSQLiteQuery(sql, args.toTypedArray())

    /** Files, their size and the share of each bucket: one pass. */
    fun buckets(rules: SafetyRules, nowMs: Long): SupportSQLiteQuery {
        val b = bucketExpr(rules, nowMs)
        return q("SELECT ${b.sql} AS cls, COUNT(*) AS n, COALESCE(SUM(m.sizeBytes), 0) AS bytes FROM $FROM WHERE $BASE GROUP BY cls", b.args)
    }

    /** The files of one bucket, sectioned by [grouping], at most [limit]. */
    fun rows(rules: SafetyRules, nowMs: Long, bucket: SpaceBucket, grouping: SpaceGrouping, limit: Int): SupportSQLiteQuery {
        val b = bucketExpr(rules, nowMs)
        return q(
            "SELECT m.* FROM $FROM WHERE $BASE AND (${b.sql}) = ? ORDER BY ${order(grouping)}, m.dateTakenMs DESC, m.mediaStoreId DESC LIMIT ?",
            b.args + bucket.name + limit,
        )
    }

    /** Sections of a bucket with their counts and sizes, for the headers and "select this group". */
    fun groups(rules: SafetyRules, nowMs: Long, bucket: SpaceBucket, grouping: SpaceGrouping): SupportSQLiteQuery {
        val b = bucketExpr(rules, nowMs)
        val g = groupExpr(grouping)
        return q(
            "SELECT $g AS gkey, COUNT(*) AS n, COALESCE(SUM(m.sizeBytes), 0) AS bytes FROM $FROM WHERE $BASE AND (${b.sql}) = ? GROUP BY gkey ORDER BY ${order(grouping)}",
            b.args + bucket.name,
        )
    }

    /** Ids of a whole bucket, or of one section of it. */
    fun ids(rules: SafetyRules, nowMs: Long, bucket: SpaceBucket, grouping: SpaceGrouping? = null, groupKey: String? = null): SupportSQLiteQuery {
        val b = bucketExpr(rules, nowMs)
        val extra = if (grouping != null && groupKey != null) " AND ${groupExpr(grouping)} = ?" else ""
        val args = b.args + bucket.name + (if (extra.isNotEmpty()) listOf(groupKey) else emptyList())
        return q("SELECT m.mediaStoreId FROM $FROM WHERE $BASE AND (${b.sql}) = ?$extra ORDER BY m.dateTakenMs DESC, m.mediaStoreId DESC", args)
    }

    /** Of these ids, the ones that are in [bucket] right now. Used to re-check a selection against the rules as they are at this moment. */
    fun stillIn(rules: SafetyRules, nowMs: Long, bucket: SpaceBucket, ids: List<Long>): SupportSQLiteQuery {
        val b = bucketExpr(rules, nowMs)
        return q(
            "SELECT m.mediaStoreId FROM $FROM WHERE $BASE AND (${b.sql}) = ? AND m.mediaStoreId IN (${ids.joinToString(",") { "?" }})",
            b.args + bucket.name + ids,
        )
    }
}
