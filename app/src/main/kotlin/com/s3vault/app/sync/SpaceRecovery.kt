package com.s3vault.app.sync

import com.s3vault.app.data.local.AppDatabase
import com.s3vault.app.data.local.IntegrityVerdict
import com.s3vault.app.data.local.SpaceRecoverySql
import com.s3vault.app.data.local.VerifyLevel
import com.s3vault.app.domain.SyncState
import com.s3vault.app.domain.space.DropReason
import com.s3vault.app.domain.space.SafetyRules
import com.s3vault.app.domain.space.SpaceBucket
import com.s3vault.app.domain.space.VerifiedBatch

/**
 * The last gate before a local file is offered to Android's deletion dialog. It never deletes anything: it decides which of the
 * chosen files may go there.
 *
 * A file passes only if, right now, in this order:
 *  1. the library index is brought up to date (so a file edited or removed since the last scan is noticed);
 *  2. it is still on the device, still backed up, and unchanged since that backup;
 *  3. a HEAD request to S3 *just made* found its object with the SHA-256 recorded at upload (or, for an object with no checksum,
 *     its size and the uploader's metadata);
 *  4. it still satisfies the user's safety rules.
 *
 * Anything else is dropped with its reason: no connection, expired credentials, a refusal or any doubt all mean "do not delete".
 * Queuing or attempting an upload never makes a file eligible; only a verified, backed-up state does.
 */
class SpaceRecoveryController(
    private val db: AppDatabase,
    private val auditor: IntegrityAuditor,
    private val refreshIndex: suspend () -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun verifyForDeletion(
        ids: List<Long>,
        rules: SafetyRules,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): VerifiedBatch {
        if (ids.isEmpty()) return VerifiedBatch(emptyList(), emptyMap())
        val dropped = LinkedHashMap<DropReason, Int>()
        fun drop(r: DropReason, n: Int = 1) { dropped[r] = (dropped[r] ?: 0) + n }

        refreshIndex()
        val rows = ids.distinct().chunked(CHUNK).flatMap { db.media().getAll(it) }
        val found = rows.map { it.mediaStoreId }.toSet()
        ids.distinct().filter { it !in found }.forEach { _ -> drop(DropReason.GONE_FROM_DEVICE) }

        val candidates = ArrayList<com.s3vault.app.data.local.MediaItemEntity>()
        for (r in rows) {
            when {
                !r.present -> drop(DropReason.GONE_FROM_DEVICE)
                r.syncState != SyncState.BACKED_UP || r.remoteKey == null || r.modifiedSinceBackup() -> drop(DropReason.LOCAL_CHANGED)
                else -> candidates += r
            }
        }
        if (candidates.isEmpty()) return VerifiedBatch(emptyList(), dropped)

        val keys = candidates.mapNotNull { it.remoteKey }.toSet()
        val startedAt = nowMs()
        val outcome = auditor.runDeep(onlyKeys = keys, limit = keys.size, onProgress = onProgress)
        if (outcome is AuditOutcome.NeedsCredentials) {
            // Nothing could be confirmed: nothing may be deleted, and the user must renew the credentials first.
            return VerifiedBatch(emptyList(), mapOf(DropReason.NOT_CONFIRMED to candidates.size) + dropped, credentialsNeeded = true)
        }
        val results = keys.chunked(CHUNK).flatMap { db.integrity().resultsFor(it) }.associateBy { it.remoteKey }
        val confirmed = ArrayList<Long>()
        for (c in candidates) {
            val res = results[c.remoteKey]
            when {
                res == null || res.checkedAtMs < startedAt -> drop(DropReason.NOT_CONFIRMED) // this run did not reach it
                res.verdict == IntegrityVerdict.VERIFIED && (res.level == VerifyLevel.CHECKSUM || res.level == VerifyLevel.METADATA) -> confirmed += c.mediaStoreId
                res.verdict == IntegrityVerdict.MISSING || res.verdict == IntegrityVerdict.STALE_RECORD -> drop(DropReason.MISSING)
                res.verdict == IntegrityVerdict.CHANGED -> drop(DropReason.CHANGED)
                else -> drop(DropReason.NOT_CONFIRMED)
            }
        }
        // The rules are applied once more to the state as it is now (a favourite flag, a folder rule or an album may have changed).
        val now = nowMs()
        val stillEligible = confirmed.chunked(CHUNK).flatMap { db.media().idList(SpaceRecoverySql.stillIn(rules, now, SpaceBucket.VERIFIED, it)) }.toSet()
        val safe = confirmed.filter { it in stillEligible }
        drop(DropReason.RULES, confirmed.size - safe.size).also { if (dropped[DropReason.RULES] == 0) dropped.remove(DropReason.RULES) }
        return VerifiedBatch(safe, dropped)
    }

    private companion object { const val CHUNK = 400 }
}
