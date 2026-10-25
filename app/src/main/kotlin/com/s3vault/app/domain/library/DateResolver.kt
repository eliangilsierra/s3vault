package com.s3vault.app.domain.library

import com.s3vault.app.domain.MediaKind
import java.time.ZoneId

data class ResolvedDate(val ms: Long, val source: DateSource)

/** Picks the best available date for a photo and says how trustworthy it is. Pure, so every rule is unit-tested. */
object DateResolver {
    /**
     * Local files: MediaStore DATE_TAKEN when it is believable, else a date written in the file name, else the file's
     * own dates (modified, then added), which are shown on the timeline but flagged as [DateSource.FALLBACK].
     * [takenMs] is 0 when MediaStore has no value.
     */
    fun local(takenMs: Long, modifiedSec: Long, addedSec: Long, name: String, nowMs: Long, zone: ZoneId): ResolvedDate {
        if (takenMs > 0 && DateKeys.isPlausible(takenMs, nowMs)) return ResolvedDate(takenMs, DateSource.CAPTURE)
        FilenameDates.parse(name, nowMs, zone)?.let { return ResolvedDate(it, DateSource.FILENAME) }
        val modified = modifiedSec * 1000
        if (DateKeys.isPlausible(modified, nowMs)) return ResolvedDate(modified, DateSource.FALLBACK)
        val added = addedSec * 1000
        if (DateKeys.isPlausible(added, nowMs)) return ResolvedDate(added, DateSource.FALLBACK)
        return ResolvedDate(maxOf(modified, added).takeIf { it > 0 } ?: nowMs, DateSource.FALLBACK)
    }

    /**
     * S3 objects: capture time from the object's metadata or from the local file that backs it (both [DateSource.CAPTURE]),
     * else a date in the file name, else the upload time (a [DateSource.FALLBACK] that is never shown as a capture date,
     * because a library migrated in one afternoon has the same upload day for every photo).
     */
    fun remote(capturedMs: Long?, name: String, uploadMs: Long, nowMs: Long, zone: ZoneId): ResolvedDate {
        if (capturedMs != null && DateKeys.isPlausible(capturedMs, nowMs)) return ResolvedDate(capturedMs, DateSource.CAPTURE)
        FilenameDates.parse(name, nowMs, zone)?.let { return ResolvedDate(it, DateSource.FILENAME) }
        return ResolvedDate(uploadMs, DateSource.FALLBACK)
    }

    /** Day key stored with a date: 0 means "no capture date" (only the S3 gallery files those apart). */
    fun remoteDayKey(date: ResolvedDate, zone: ZoneId): Int =
        if (date.source == DateSource.FALLBACK) 0 else DateKeys.dayKey(date.ms, zone)
}

object MediaKinds {
    private val IMAGES = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "avif", "dng", "tif", "tiff")
    private val VIDEOS = setOf("mp4", "mov", "m4v", "3gp", "mkv", "webm", "avi")

    /** From the file extension, which the S3 key preserves; null when it is not a known media type (no filter claims it). */
    fun fromName(name: String): MediaKind? = when (name.substringAfterLast('.', "").lowercase()) {
        in IMAGES -> MediaKind.IMAGE
        in VIDEOS -> MediaKind.VIDEO
        else -> null
    }
}
