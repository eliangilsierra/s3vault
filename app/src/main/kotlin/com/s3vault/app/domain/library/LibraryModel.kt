package com.s3vault.app.domain.library

import com.s3vault.app.domain.MediaKind

enum class GroupBy { DAY, MONTH, YEAR, NONE }

/** Not every field exists on both galleries: see [LibraryScope.sortFields]. */
enum class SortField { CAPTURE_DATE, MODIFIED_DATE, UPLOAD_DATE, NAME, SIZE }

/** State of a photo on this device with respect to the backup. */
enum class BackupFilter { BACKED_UP, NOT_BACKED_UP, IN_PROGRESS, FAILED }

/** Where an object of the S3 library also exists. */
enum class CloudAvailability { CLOUD_ONLY, ALSO_LOCAL }

/** The two galleries share one query model; each one supports the subset its metadata can answer reliably. */
enum class LibraryScope(val sortFields: List<SortField>) {
    LOCAL(listOf(SortField.CAPTURE_DATE, SortField.MODIFIED_DATE, SortField.NAME, SortField.SIZE)),
    CLOUD(listOf(SortField.CAPTURE_DATE, SortField.UPLOAD_DATE, SortField.NAME, SortField.SIZE)),
}

/** Inclusive range of day keys (`yyyyMMdd`). An inverted range is empty and matches nothing. */
data class DateRange(val fromDay: Int, val toDay: Int) {
    val isEmpty: Boolean get() = fromDay > toDay

    fun intersect(other: DateRange): DateRange = DateRange(maxOf(fromDay, other.fromDay), minOf(toDay, other.toDay))
}

data class LibraryFilters(
    /** On the capture date. In the S3 gallery objects without a capture date never match (they are listed apart). */
    val dateRange: DateRange? = null,
    val kinds: Set<MediaKind> = emptySet(),
    /** Local gallery only. */
    val backup: Set<BackupFilter> = emptySet(),
    /** S3 gallery only. */
    val availability: CloudAvailability? = null,
    /** Local gallery only: MediaStore bucket names. */
    val folders: Set<String> = emptySet(),
    /** Local gallery only: photos whose date is the file date because no capture date exists. */
    val undatedOnly: Boolean = false,
) {
    val activeCount: Int
        get() = listOf(dateRange != null, kinds.isNotEmpty(), backup.isNotEmpty(), availability != null, folders.isNotEmpty(), undatedOnly).count { it }

    val isEmpty: Boolean get() = activeCount == 0
}

data class LibraryQuery(
    val search: String = "",
    val filters: LibraryFilters = LibraryFilters(),
    val sort: SortField = SortField.CAPTURE_DATE,
    val descending: Boolean = true,
    val group: GroupBy = GroupBy.DAY,
) {
    val isDefault: Boolean get() = search.isBlank() && filters.isEmpty

    /** Sections only make sense when the list is ordered by the date they are named after. */
    fun effectiveGroup(scope: LibraryScope): GroupBy = when {
        sort == SortField.CAPTURE_DATE -> group
        sort == SortField.UPLOAD_DATE && scope == LibraryScope.CLOUD -> group
        else -> GroupBy.NONE
    }
}

/** One heading of the timeline with the number of photos under it. Key 0 is the "no capture date" section. */
data class Section(val key: Int, val count: Int)
