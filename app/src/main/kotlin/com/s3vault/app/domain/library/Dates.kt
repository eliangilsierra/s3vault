package com.s3vault.app.domain.library

import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Where the date used to place a photo on the timeline came from. It is stored next to the date so the UI can be honest
 * about it and filters never treat a guess as a capture date.
 */
enum class DateSource {
    /** The moment the picture was taken: MediaStore DATE_TAKEN, the `captured-at-ms` metadata of an S3 object, or the local file that backs it. */
    CAPTURE,

    /** A date written in the file name (IMG_20240312_101530.jpg, Screenshot_2024-03-12...). Reliable for the day, not proof of capture. */
    FILENAME,

    /** No capture information: the file's own dates locally, the upload time on S3. */
    FALLBACK,
}

/**
 * Day keys are `yyyyMMdd` integers computed in an explicit [ZoneId], so grouping never depends on a hidden default and
 * is DST-correct. Timestamps themselves are always UTC epoch milliseconds.
 */
object DateKeys {
    /** 1990-01-01T00:00:00Z: anything earlier is a broken clock or a zeroed field, not a capture date. */
    const val MIN_VALID_MS = 631_152_000_000L
    private const val DAY_MS = 86_400_000L

    val UiLocale: Locale = Locale.forLanguageTag("es")

    /** A date is believable between 1990 and two days from now (clock skew between devices and time zones). */
    fun isPlausible(ms: Long, nowMs: Long): Boolean = ms in MIN_VALID_MS..(nowMs + 2 * DAY_MS)

    fun dayKey(ms: Long, zone: ZoneId): Int = dayKey(Instant.ofEpochMilli(ms).atZone(zone).toLocalDate())

    fun dayKey(date: LocalDate): Int = date.year * 10_000 + date.monthValue * 100 + date.dayOfMonth

    fun toLocalDate(dayKey: Int): LocalDate? =
        if (dayKey <= 0) null else runCatching { LocalDate.of(dayKey / 10_000, dayKey / 100 % 100, dayKey % 100) }.getOrNull()

    fun monthKey(dayKey: Int): Int = dayKey / 100
    fun yearKey(dayKey: Int): Int = dayKey / 10_000

    /** The key of a section for [group] (0 stays 0: the "no capture date" section). */
    fun groupKey(dayKey: Int, group: GroupBy): Int = when {
        dayKey <= 0 -> 0
        group == GroupBy.MONTH -> monthKey(dayKey)
        group == GroupBy.YEAR -> yearKey(dayKey)
        else -> dayKey
    }

    private val dayFormat = DateTimeFormatter.ofPattern("EEEE d MMM yyyy", UiLocale)
    private val shortFormat = DateTimeFormatter.ofPattern("d MMM yyyy", UiLocale)
    private val monthFormat = DateTimeFormatter.ofPattern("MMMM yyyy", UiLocale)

    private fun String.cap() = replaceFirstChar { it.uppercase(UiLocale) }

    fun dayLabel(dayKey: Int): String = toLocalDate(dayKey)?.format(dayFormat)?.cap().orEmpty()
    fun shortDayLabel(dayKey: Int): String = toLocalDate(dayKey)?.format(shortFormat).orEmpty()
    fun monthLabel(monthKey: Int): String = runCatching { LocalDate.of(monthKey / 100, monthKey % 100, 1).format(monthFormat).cap() }.getOrDefault("")
    fun yearLabel(yearKey: Int): String = yearKey.toString()

    /** Heading of the section with [key] under [group]. */
    fun sectionLabel(key: Int, group: GroupBy): String = when {
        key == 0 -> "Sin fecha de captura"
        group == GroupBy.MONTH -> monthLabel(key)
        group == GroupBy.YEAR -> yearLabel(key)
        else -> dayLabel(key)
    }

    /** Midday of [date] in [zone]: the safest instant for a date without a time (never slips to the neighbouring day). */
    fun middayOf(date: LocalDate, zone: ZoneId): Long = LocalDateTime.of(date, LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()

    /** Lowercase, accent-free form used to compare words ("Marzo" == "márzo"). */
    fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
}

/** Dates that camera and messaging apps write into file names. */
object FilenameDates {
    // 14 digits in a row: 20240312101530
    private val COMPACT = Regex("""(?<!\d)((?:19|20)\d{2})(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])([01]\d|2[0-3])([0-5]\d)([0-5]\d)(?!\d)""")

    // 2024-03-12, 2024_03_12, 2024.03.12 or 20240312 (same separator twice, or none)
    private val DATE = Regex("""(?<!\d)((?:19|20)\d{2})([-_.]?)(0[1-9]|1[0-2])\2(0[1-9]|[12]\d|3[01])(?!\d)""")

    // what may follow the date: _101530, T101530, " 10.15.30", " at 10.15.30"
    private val TIME = Regex("""^(?:[-_T ]|\s+at\s+)?([01]\d|2[0-3])([-_.:]?)([0-5]\d)\2([0-5]\d)(?!\d)""")

    // 1710238530123 (epoch milliseconds, used by some downloaders and messengers)
    private val EPOCH_MS = Regex("""(?<!\d)(\d{13})(?!\d)""")

    /** Epoch ms of the date in [name] interpreted in [zone], or null when there is none or it is implausible. */
    fun parse(name: String, nowMs: Long, zone: ZoneId): Long? {
        for (m in COMPACT.findAll(name)) {
            val (y, mo, d, h, mi, s) = m.destructured
            val ms = runCatching {
                LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt()).atZone(zone).toInstant().toEpochMilli()
            }.getOrNull()
            if (ms != null && DateKeys.isPlausible(ms, nowMs)) return ms
        }
        for (m in DATE.findAll(name)) {
            val date = runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[3].toInt(), m.groupValues[4].toInt()) }.getOrNull() ?: continue
            val time = TIME.find(name.substring(m.range.last + 1))
            val ms = if (time != null) {
                val (h, _, mi, s) = time.destructured
                LocalDateTime.of(date, LocalTime.of(h.toInt(), mi.toInt(), s.toInt())).atZone(zone).toInstant().toEpochMilli()
            } else {
                DateKeys.middayOf(date, zone)
            }
            if (DateKeys.isPlausible(ms, nowMs)) return ms
        }
        for (m in EPOCH_MS.findAll(name)) {
            val ms = m.groupValues[1].toLongOrNull() ?: continue
            if (DateKeys.isPlausible(ms, nowMs)) return ms
        }
        return null
    }
}
