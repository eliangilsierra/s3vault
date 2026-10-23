package com.s3vault.app.domain.library

import com.s3vault.app.domain.MediaKind
import java.time.LocalDate
import java.time.YearMonth

/** A removable piece of the search shown under the field, so the user can see how the text was understood. */
data class SearchChip(val label: String, val type: Type, /** The exact text it came from, so removing the chip removes just that part of the search. */ val raw: String = "") {
    enum class Type { TEXT, DATE, KIND }
}

/**
 * How a search string is understood. Everything here is answered from metadata that is already stored (file name, folder,
 * date and type); nothing triggers a request to S3.
 *
 * - [textTerms]: substrings of the file name (and, locally, of the folder name). All must match.
 * - [dateRange] / [month] / [dayOfMonth]: constraints on the capture date. Combined with AND.
 * - [kind]: photos or videos.
 */
data class ParsedSearch(
    val textTerms: List<String> = emptyList(),
    val dateRange: DateRange? = null,
    val month: Int? = null,
    val dayOfMonth: Int? = null,
    val kind: MediaKind? = null,
    val chips: List<SearchChip> = emptyList(),
) {
    val isBlank: Boolean get() = textTerms.isEmpty() && dateRange == null && month == null && dayOfMonth == null && kind == null
    val hasDate: Boolean get() = dateRange != null || month != null || dayOfMonth != null
}

/**
 * Understands, in Spanish: `2023`, `marzo`, `marzo 2023`, `marzo de 2023`, `mar 2023`, `15 marzo`, `15 de marzo 2023`,
 * `2023-03`, `2023-03-15`, `15/03/2023`, `03/2023`, `foto(s)`, `video(s)`; anything else is a file-name term.
 */
object SearchParser {
    private val MONTHS = listOf(
        "enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
    )
    private val ABBREVIATIONS = mapOf(
        "ene" to 1, "feb" to 2, "mar" to 3, "abr" to 4, "may" to 5, "jun" to 6, "jul" to 7, "ago" to 8,
        "sep" to 9, "sept" to 9, "set" to 9, "oct" to 10, "nov" to 11, "dic" to 12,
    )
    private val PHOTO_WORDS = setOf("foto", "fotos", "imagen", "imagenes", "photo", "photos")
    private val VIDEO_WORDS = setOf("video", "videos", "clip", "clips")

    private val ISO_DAY = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})$""")
    private val DMY = Regex("""^(\d{1,2})[/.\-](\d{1,2})[/.\-](\d{4})$""")
    private val ISO_MONTH = Regex("""^(\d{4})-(\d{1,2})$""")
    private val MY = Regex("""^(\d{1,2})/(\d{4})$""")
    private val YEAR = Regex("""^(?:19|20)\d{2}$""")
    private val DAY_NUMBER = Regex("""^\d{1,2}$""")

    private fun monthOf(word: String): Int? {
        val i = MONTHS.indexOf(word)
        return if (i >= 0) i + 1 else ABBREVIATIONS[word]
    }

    /** The search text without the part that produced [chip] (what removing a chip does). */
    fun remove(text: String, chip: SearchChip): String {
        if (chip.raw.isEmpty()) return text
        fun cut(from: String, piece: String): String? {
            val i = from.indexOf(piece)
            return if (i < 0) null else from.removeRange(i, i + piece.length)
        }
        val whole = cut(text, chip.raw)
            // The user typed extra spaces between the words: remove them one by one.
            ?: chip.raw.split(' ').filter { it.isNotEmpty() }.fold(text) { acc, w -> cut(acc, w) ?: acc }
        return whole.replace(Regex("\\s+"), " ").trim()
    }

    fun parse(raw: String, today: LocalDate = LocalDate.now()): ParsedSearch {
        val tokens = raw.trim().split(Regex("\\s+")).map { it.trim(',', ';') }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return ParsedSearch()

        val maxYear = today.year + 1
        fun validYear(s: String?) = s != null && YEAR.matches(s) && s.toInt() in 1990..maxYear

        val terms = ArrayList<String>()
        val chips = ArrayList<SearchChip>()
        var range: DateRange? = null
        var month: Int? = null
        var day: Int? = null
        var kind: MediaKind? = null
        var conflict = false

        fun addRange(r: DateRange) { range = range?.intersect(r) ?: r }
        fun setMonth(m: Int) { if (month != null && month != m) conflict = true; month = m }
        fun setDay(d: Int) { if (day != null && day != d) conflict = true; day = d }
        fun dayRange(y: Int, m: Int, d: Int): DateRange? = runCatching {
            val k = DateKeys.dayKey(LocalDate.of(y, m, d))
            DateRange(k, k)
        }.getOrNull()
        fun monthRange(y: Int, m: Int): DateRange? = runCatching {
            val ym = YearMonth.of(y, m)
            DateRange(DateKeys.dayKey(ym.atDay(1)), DateKeys.dayKey(ym.atEndOfMonth()))
        }.getOrNull()
        fun chip(label: String) = chips.add(SearchChip(label, SearchChip.Type.DATE))

        fun step(i: Int): Int {
            val tok = tokens[i]
            val word = DateKeys.fold(tok)

            ISO_DAY.matchEntire(tok)?.let { m ->
                val r = dayRange(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
                if (r != null) { addRange(r); chip(DateKeys.shortDayLabel(r.fromDay)); return i + 1 }
            }
            DMY.matchEntire(tok)?.let { m ->
                val r = dayRange(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt())
                if (r != null) { addRange(r); chip(DateKeys.shortDayLabel(r.fromDay)); return i + 1 }
            }
            ISO_MONTH.matchEntire(tok)?.let { m ->
                val r = monthRange(m.groupValues[1].toInt(), m.groupValues[2].toInt())
                if (r != null) { addRange(r); chip(DateKeys.monthLabel(DateKeys.monthKey(r.fromDay))); return i + 1 }
            }
            MY.matchEntire(tok)?.let { m ->
                val r = monthRange(m.groupValues[2].toInt(), m.groupValues[1].toInt())
                if (r != null) { addRange(r); chip(DateKeys.monthLabel(DateKeys.monthKey(r.fromDay))); return i + 1 }
            }
            if (validYear(tok)) {
                val y = tok.toInt()
                addRange(DateRange(y * 10_000 + 101, y * 10_000 + 1231)); chip(y.toString()); return i + 1
            }

            // "15 marzo", "15 de marzo 2023"
            if (DAY_NUMBER.matches(tok) && tok.toInt() in 1..31) {
                var j = i + 1
                if (tokens.getOrNull(j)?.let { DateKeys.fold(it) } in setOf("de", "del")) j++
                val m = tokens.getOrNull(j)?.let { monthOf(DateKeys.fold(it)) }
                if (m != null) {
                    j++
                    if (tokens.getOrNull(j)?.let { DateKeys.fold(it) } in setOf("de", "del")) j++
                    val yearTok = tokens.getOrNull(j)?.takeIf { validYear(it) }
                    val d = tok.toInt()
                    if (yearTok != null) {
                        val r = dayRange(yearTok.toInt(), m, d)
                        if (r != null) { addRange(r); chip(DateKeys.shortDayLabel(r.fromDay)) } else conflict = true
                        j++
                    } else {
                        setMonth(m); setDay(d)
                        chip("$d de ${MONTHS[m - 1]}")
                    }
                    return j
                }
            }

            val m = monthOf(word)
            if (m != null) {
                var j = i + 1
                if (tokens.getOrNull(j)?.let { DateKeys.fold(it) } in setOf("de", "del")) j++
                val yearTok = tokens.getOrNull(j)?.takeIf { validYear(it) }
                if (yearTok != null) {
                    val r = monthRange(yearTok.toInt(), m)
                    if (r != null) { addRange(r); chip(DateKeys.monthLabel(DateKeys.monthKey(r.fromDay))) } else conflict = true
                    j++
                } else {
                    setMonth(m)
                    chip("Cada ${MONTHS[m - 1]}")
                }
                return j
            }

            when {
                word in PHOTO_WORDS -> { kind = MediaKind.IMAGE; chips.add(SearchChip("Fotos", SearchChip.Type.KIND)) }
                word in VIDEO_WORDS -> { kind = MediaKind.VIDEO; chips.add(SearchChip("Vídeos", SearchChip.Type.KIND)) }
                else -> { terms.add(tok); chips.add(SearchChip("«$tok»", SearchChip.Type.TEXT)) }
            }
            return i + 1
        }

        var i = 0
        while (i < tokens.size) {
            val before = chips.size
            val next = step(i)
            val raw = tokens.subList(i, next).joinToString(" ")
            for (k in before until chips.size) chips[k] = chips[k].copy(raw = raw)
            i = next
        }

        if (conflict) range = DateRange(1, 0) // contradictory dates ("marzo abril"): nothing can match, and the chips show why
        return ParsedSearch(terms, range, month, day, kind, chips)
    }
}
