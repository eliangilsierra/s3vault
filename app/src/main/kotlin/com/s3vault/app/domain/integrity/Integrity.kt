package com.s3vault.app.domain.integrity

import com.s3vault.app.data.local.AuditTarget
import com.s3vault.app.data.local.IntegrityReason
import com.s3vault.app.data.local.IntegrityResultEntity
import com.s3vault.app.data.local.IntegrityVerdict
import com.s3vault.app.data.local.VerifyLevel
import com.s3vault.app.domain.RemoteKeys
import com.s3vault.app.domain.RemoteObjectMetadata
import com.s3vault.app.domain.cost.S3Pricing
import java.util.Base64

/** What the cached listing says about one object. */
data class ListedObject(val sizeBytes: Long, val lastModifiedMs: Long, val etag: String?)

/** How trustworthy the listing the audit compares against is. */
data class ListingFacts(
    /** The listing reached its last page. A partial listing proves presence, never absence. */
    val complete: Boolean,
    /** The listing found no photo object at all (wrong bucket, a policy that hides everything...). */
    val empty: Boolean,
)

data class Finding(
    val verdict: IntegrityVerdict,
    val level: VerifyLevel,
    val reason: IntegrityReason,
    val remoteSize: Long? = null,
)

/**
 * The rules that turn "what the device recorded" and "what S3 reports" into a verdict. Pure, so each rule is a unit test.
 *
 * Two principles hold everywhere:
 *  - **A failed or inconclusive check is UNKNOWN, never MISSING.** Only a complete listing that lacks the key, or a HEAD that
 *    answered "not found", says the object is gone.
 *  - **An ETag is a version marker, not a checksum.** It is compared only against the marker recorded when the backup was
 *    verified, to notice a replaced object; it is never treated as an MD5 of the content (multipart uploads and SSE-KMS break
 *    that). Content is judged only by the SHA-256 S3 stored at upload time (`x-amz-checksum-sha256`).
 */
object IntegrityAnalyzer {
    /** An object modified later than this after the backup was verified is suspicious when no version marker was recorded. */
    const val MODIFIED_SLACK_MS = 10L * 60 * 1000

    fun fromListing(t: AuditTarget, remote: ListedObject?, facts: ListingFacts, keys: RemoteKeys): Finding {
        stale(t, keys)?.let { return it }
        if (remote == null) {
            return when {
                !facts.complete -> Finding(IntegrityVerdict.UNKNOWN, VerifyLevel.LISTING, IntegrityReason.LISTING_INCOMPLETE)
                facts.empty -> Finding(IntegrityVerdict.UNKNOWN, VerifyLevel.LISTING, IntegrityReason.LISTING_EMPTY)
                else -> Finding(IntegrityVerdict.MISSING, VerifyLevel.LISTING, IntegrityReason.NOT_LISTED)
            }
        }
        if (remote.sizeBytes != t.expectedSize) {
            return Finding(IntegrityVerdict.CHANGED, VerifyLevel.LISTING, IntegrityReason.SIZE_MISMATCH, remote.sizeBytes)
        }
        if (t.etag != null && remote.etag != null) {
            return if (t.etag != remote.etag) Finding(IntegrityVerdict.CHANGED, VerifyLevel.LISTING, IntegrityReason.ETAG_CHANGED, remote.sizeBytes)
            else Finding(IntegrityVerdict.VERIFIED, VerifyLevel.LISTING, IntegrityReason.NONE, remote.sizeBytes)
        }
        // No marker to compare with (an older backup): fall back to the time, which is only a hint.
        val backedUp = t.backedUpAtMs
        if (backedUp != null && remote.lastModifiedMs > backedUp + MODIFIED_SLACK_MS) {
            return Finding(IntegrityVerdict.CHANGED, VerifyLevel.LISTING, IntegrityReason.MODIFIED_AFTER_BACKUP, remote.sizeBytes)
        }
        return Finding(IntegrityVerdict.VERIFIED, VerifyLevel.LISTING, IntegrityReason.NONE, remote.sizeBytes)
    }

    /** [meta] is null when HEAD said the object does not exist. */
    fun fromHead(t: AuditTarget, meta: RemoteObjectMetadata?, keys: RemoteKeys): Finding {
        stale(t, keys)?.let { return it }
        if (meta == null) return Finding(IntegrityVerdict.MISSING, VerifyLevel.LISTING, IntegrityReason.HEAD_NOT_FOUND)
        if (meta.sizeBytes != t.expectedSize) {
            return Finding(IntegrityVerdict.CHANGED, VerifyLevel.LISTING, IntegrityReason.SIZE_MISMATCH, meta.sizeBytes)
        }
        val version = t.versionId ?: return Finding(IntegrityVerdict.UNKNOWN, VerifyLevel.LISTING, IntegrityReason.VERSION_MISMATCH, meta.sizeBytes)
        val remoteSum = meta.checksumSha256Base64
        // A composite checksum ("...-12") belongs to a multipart upload and is not a hash of the whole content.
        if (remoteSum != null && '-' !in remoteSum) {
            return if (remoteSum == hexToBase64(version)) Finding(IntegrityVerdict.VERIFIED, VerifyLevel.CHECKSUM, IntegrityReason.NONE, meta.sizeBytes)
            else Finding(IntegrityVerdict.CHANGED, VerifyLevel.CHECKSUM, IntegrityReason.CHECKSUM_MISMATCH, meta.sizeBytes)
        }
        // No usable checksum in S3. The uploader's own metadata can only vouch for what the uploader claimed.
        return if (meta.userMetadata["sha256-hex"]?.lowercase() == version) {
            Finding(IntegrityVerdict.VERIFIED, VerifyLevel.METADATA, IntegrityReason.NO_REMOTE_CHECKSUM, meta.sizeBytes)
        } else {
            Finding(IntegrityVerdict.UNKNOWN, VerifyLevel.LISTING, IntegrityReason.NO_REMOTE_CHECKSUM, meta.sizeBytes)
        }
    }

    /** A record that cannot describe an object of the configured bucket: it is stale, not the object missing. */
    private fun stale(t: AuditTarget, keys: RemoteKeys): Finding? {
        val parsed = keys.parse(t.key)
            ?: return Finding(IntegrityVerdict.STALE_RECORD, VerifyLevel.LISTING, IntegrityReason.KEY_OUTSIDE_PREFIX)
        if (t.versionId != null && t.versionId != parsed.versionId) {
            return Finding(IntegrityVerdict.STALE_RECORD, VerifyLevel.LISTING, IntegrityReason.VERSION_MISMATCH)
        }
        return null
    }

    fun hexToBase64(hex: String): String =
        Base64.getEncoder().encodeToString(ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
}

/** Chooses what a bounded, incremental deep audit checks next. */
object IntegrityPlanner {
    /**
     * Objects never confirmed by checksum first, then the ones confirmed longest ago; anything confirmed after [freshAfterMs] is
     * left alone. [onlyKeys] narrows the choice to what the user selected (a recheck ignores freshness: they asked).
     */
    fun nextDeep(
        targets: List<AuditTarget>,
        results: Map<String, IntegrityResultEntity>,
        freshAfterMs: Long,
        limit: Int,
        onlyKeys: Set<String>? = null,
    ): List<AuditTarget> {
        val pool = if (onlyKeys != null) targets.filter { it.key in onlyKeys } else targets.filter {
            val deep = results[it.key]?.deepCheckedAtMs
            deep == null || deep < freshAfterMs
        }
        return pool.sortedWith(compareBy<AuditTarget> { results[it.key]?.deepCheckedAtMs ?: Long.MIN_VALUE }.thenBy { it.key }).take(limit)
    }
}

/** What an audit would cost in S3 requests, so the user decides knowing it. */
data class AuditCost(val requests: Int, val usd: Double?, val kind: String)

object AuditCosts {
    private const val LIST_PAGE = 1000

    /** One LIST request per 1000 objects of the bucket's photo folder. Free when a recent complete listing is reused. */
    fun listing(objectCount: Int, region: String, reusedListing: Boolean): AuditCost {
        val requests = if (reusedListing) 0 else maxOf(1, (objectCount + LIST_PAGE - 1) / LIST_PAGE)
        return AuditCost(requests, price(region)?.putPerRequest?.times(requests), "LIST")
    }

    /** One HEAD per object; HEAD is billed like a GET request. Standard list price (other classes can be higher). */
    fun deep(objects: Int, region: String): AuditCost = AuditCost(objects, price(region)?.getPerRequest?.times(objects), "HEAD")

    private fun price(region: String) = S3Pricing.forRegion(region)
}
