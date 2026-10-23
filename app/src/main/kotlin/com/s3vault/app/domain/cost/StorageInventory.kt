package com.s3vault.app.domain.cost

import com.s3vault.app.domain.RemoteListing
import com.s3vault.app.domain.S3Config
import java.time.Instant
import java.time.ZoneOffset

/** Objects of one S3 storage class under the configured prefix. */
data class ClassUsage(
    val storageClass: String,
    val objects: Long,
    val bytes: Long,
    /** Objects smaller than 128 KiB and their bytes: classes with a minimum billable size charge each as 128 KiB. */
    val smallObjects: Long,
    val smallBytes: Long,
)

/**
 * What a complete `ListObjectsV2` sweep of `{prefix}photos/` found, and nothing more. It is a measurement of the
 * listing only: other prefixes, previews (`.s3vault/thumbs/`, not listable with the recommended policy), previous
 * versions and delete markers are not in it. It is built only from a listing that finished, so it is never partial;
 * [complete] exists so that anything that is not can never be turned into a total.
 */
data class StorageInventory(
    val region: String,
    val bucket: String,
    val prefix: String,
    val takenAtMs: Long,
    /** How many `ListObjectsV2` requests the sweep took (1 per 1000 objects), i.e. what a refresh costs. */
    val listRequests: Int,
    val classes: List<ClassUsage>,
    /** Objects whose key is a backed-up photo (`photos/{asset}/{version}/{name}`). */
    val photoObjects: Long,
    /** Objects under `photos/` that are not recognised photos: stored and billed all the same. */
    val unrecognizedObjects: Long,
    val unrecognizedBytes: Long,
    /** Objects by month of their S3 `LastModified` (UTC, yyyyMM): the upload month, the only date a listing gives. */
    val modifiedByMonth: Map<Int, Int>,
    val complete: Boolean = true,
) {
    val totalObjects: Long get() = classes.sumOf { it.objects }
    val totalBytes: Long get() = classes.sumOf { it.bytes }

    /** An inventory of another bucket, region or prefix says nothing about the current configuration. */
    fun matches(config: S3Config): Boolean = region == config.region && bucket == config.bucket && prefix == config.prefix

    companion object {
        const val SMALL_OBJECT_BYTES = 128L * 1024

        fun monthKey(ms: Long): Int {
            val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC)
            return d.year * 100 + d.monthValue
        }

        /** Aggregates a finished listing. [isPhoto] tells recognised photo keys from anything else stored under the prefix. */
        fun from(
            listing: List<RemoteListing>,
            isPhoto: (String) -> Boolean,
            region: String,
            bucket: String,
            prefix: String,
            takenAtMs: Long,
            listRequests: Int,
        ): StorageInventory {
            class Acc { var objects = 0L; var bytes = 0L; var small = 0L; var smallBytes = 0L }
            val byClass = LinkedHashMap<String, Acc>()
            val months = HashMap<Int, Int>()
            var photos = 0L
            var otherObjects = 0L
            var otherBytes = 0L
            for (o in listing) {
                val acc = byClass.getOrPut(o.storageClass ?: "STANDARD") { Acc() }
                acc.objects++
                acc.bytes += o.sizeBytes
                if (o.sizeBytes < SMALL_OBJECT_BYTES) { acc.small++; acc.smallBytes += o.sizeBytes }
                if (isPhoto(o.key)) photos++ else { otherObjects++; otherBytes += o.sizeBytes }
                if (o.lastModifiedMs > 0) months.merge(monthKey(o.lastModifiedMs), 1, Int::plus)
            }
            return StorageInventory(
                region = region, bucket = bucket, prefix = prefix, takenAtMs = takenAtMs, listRequests = listRequests,
                classes = byClass.map { (c, a) -> ClassUsage(c, a.objects, a.bytes, a.small, a.smallBytes) }.sortedByDescending { it.bytes },
                photoObjects = photos, unrecognizedObjects = otherObjects, unrecognizedBytes = otherBytes, modifiedByMonth = months,
            )
        }
    }
}
