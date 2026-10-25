package com.s3vault.app.domain.library

import com.s3vault.app.domain.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val UTC: ZoneId = ZoneId.of("UTC")
private val BOGOTA: ZoneId = ZoneId.of("America/Bogota")
private val MADRID: ZoneId = ZoneId.of("Europe/Madrid")
private val NOW = Instant.parse("2026-10-23T12:00:00Z").toEpochMilli()
private val TODAY: LocalDate = LocalDate.of(2026, 10, 23)

class DateKeysTest {
    @Test fun `day keys use the explicit zone, not a hidden default`() {
        val ms = Instant.parse("2024-03-12T02:00:00Z").toEpochMilli()
        assertEquals(20240312, DateKeys.dayKey(ms, UTC))
        assertEquals("02:00 UTC is still the previous evening in Colombia", 20240311, DateKeys.dayKey(ms, BOGOTA))
    }

    @Test fun `day keys follow daylight saving time`() {
        // Madrid switches to summer time on 2024-03-31 at 01:00 UTC.
        assertEquals(20240331, DateKeys.dayKey(Instant.parse("2024-03-30T23:30:00Z").toEpochMilli(), MADRID))
        assertEquals(20240331, DateKeys.dayKey(Instant.parse("2024-03-31T00:30:00Z").toEpochMilli(), MADRID))
        // 22:30 UTC is 00:30 in summer time (+2): already April 1st. A fixed +1 offset would say March 31st.
        assertEquals(20240401, DateKeys.dayKey(Instant.parse("2024-03-31T22:30:00Z").toEpochMilli(), MADRID))
    }

    @Test fun `changing the zone changes the key of the same instant`() {
        val ms = Instant.parse("2024-12-31T23:30:00Z").toEpochMilli()
        assertEquals(20241231, DateKeys.dayKey(ms, UTC))
        assertEquals(20250101, DateKeys.dayKey(ms, ZoneId.of("Asia/Tokyo")))
    }

    @Test fun `plausible dates are between 1990 and two days from now`() {
        assertFalse("zeroed field", DateKeys.isPlausible(0, NOW))
        assertFalse("negative", DateKeys.isPlausible(-1, NOW))
        assertFalse("before 1990", DateKeys.isPlausible(Instant.parse("1989-12-31T23:59:59Z").toEpochMilli(), NOW))
        assertTrue(DateKeys.isPlausible(Instant.parse("1990-01-01T00:00:00Z").toEpochMilli(), NOW))
        assertTrue("small clock skew is tolerated", DateKeys.isPlausible(NOW + 86_400_000L, NOW))
        assertFalse("far future (broken clock)", DateKeys.isPlausible(NOW + 10 * 86_400_000L, NOW))
    }

    @Test fun `keys convert between day month and year`() {
        assertEquals(202403, DateKeys.monthKey(20240312))
        assertEquals(2024, DateKeys.yearKey(20240312))
        assertEquals(202403, DateKeys.groupKey(20240312, GroupBy.MONTH))
        assertEquals(2024, DateKeys.groupKey(20240312, GroupBy.YEAR))
        assertEquals(20240312, DateKeys.groupKey(20240312, GroupBy.DAY))
        assertEquals("the undated section keeps key 0", 0, DateKeys.groupKey(0, GroupBy.YEAR))
        assertEquals(LocalDate.of(2024, 3, 12), DateKeys.toLocalDate(20240312))
        assertNull(DateKeys.toLocalDate(0))
        assertNull(DateKeys.toLocalDate(20240231))
    }

    @Test fun `labels are Spanish and capitalized`() {
        assertTrue(DateKeys.dayLabel(20240331), DateKeys.dayLabel(20240331).startsWith("Domingo 31"))
        assertTrue(DateKeys.dayLabel(20240331).endsWith("2024"))
        assertEquals("Marzo 2024", DateKeys.monthLabel(202403))
        assertEquals("2024", DateKeys.yearLabel(2024))
        assertEquals("Sin fecha de captura", DateKeys.sectionLabel(0, GroupBy.DAY))
        assertEquals("Marzo 2024", DateKeys.sectionLabel(202403, GroupBy.MONTH))
    }

    @Test fun `midday never slips to the neighbouring day in any zone`() {
        val d = LocalDate.of(2024, 3, 12)
        listOf(UTC, BOGOTA, MADRID, ZoneId.of("Pacific/Auckland")).forEach { z ->
            assertEquals(z.id, 20240312, DateKeys.dayKey(DateKeys.middayOf(d, z), z))
        }
    }

    @Test fun `fold removes accents and case`() {
        assertEquals("septiembre", DateKeys.fold("Séptiembre"))
        assertEquals("imagenes", DateKeys.fold("Imágenes"))
    }
}

class FilenameDatesTest {
    private fun parse(name: String, zone: ZoneId = UTC) = FilenameDates.parse(name, NOW, zone)
    private fun key(name: String, zone: ZoneId = UTC) = parse(name, zone)?.let { DateKeys.dayKey(it, zone) }

    @Test fun `camera and messenger names carry the date`() {
        assertEquals(20240312, key("IMG_20240312_101530.jpg"))
        assertEquals(20240312, key("PXL_20240312_101530123.jpg"))
        assertEquals(20240312, key("Screenshot_2024-03-12-10-15-30.png"))
        assertEquals(20240312, key("IMG-20240312-WA0001.jpg"))
        assertEquals(20240312, key("WhatsApp Image 2024-03-12 at 10.15.30.jpeg"))
        assertEquals(20240312, key("VID_20240312_101530.mp4"))
        assertEquals(20240312, key("2024_03_12_photo.jpg"))
        assertEquals(20240312, key("20240312101530.jpg"))
    }

    @Test fun `the time is used when the name has one, midday otherwise`() {
        val withTime = parse("IMG_20240312_101530.jpg")!!
        assertEquals(Instant.parse("2024-03-12T10:15:30Z").toEpochMilli(), withTime)
        val noTime = parse("IMG-20240312-WA0001.jpg")!!
        assertEquals(Instant.parse("2024-03-12T12:00:00Z").toEpochMilli(), noTime)
    }

    @Test fun `the name is read in the given zone`() {
        val ms = parse("IMG_20240312_233000.jpg", BOGOTA)!!
        assertEquals(Instant.parse("2024-03-13T04:30:00Z").toEpochMilli(), ms)
        assertEquals(20240312, DateKeys.dayKey(ms, BOGOTA))
    }

    @Test fun `epoch milliseconds in the name are understood`() {
        assertEquals(1710238530123L, parse("1710238530123.jpg"))
    }

    @Test fun `impossible or implausible dates are ignored`() {
        assertNull("February 31st", parse("IMG_20240231_101530.jpg"))
        assertNull("month 13", parse("IMG_20241312.jpg"))
        assertNull("before 1990", parse("IMG_19851001.jpg"))
        assertNull("in the future", parse("IMG_20300101.jpg"))
        assertNull(parse("DSC_0001.jpg"))
        assertNull("a long number that is not a date", parse("IMG_123456789012345.jpg"))
        assertNull("an epoch in the future", parse("9999999999999.jpg"))
    }

    @Test fun `an invalid first match does not hide a valid later one`() {
        assertEquals(20240312, key("20240231_backup_20240312.jpg"))
    }
}

class SearchParserTest {
    private fun parse(q: String) = SearchParser.parse(q, TODAY)
    private fun range(from: Int, to: Int) = DateRange(from, to)

    @Test fun `blank search is blank`() {
        assertTrue(parse("").isBlank)
        assertTrue(parse("   ").isBlank)
        assertEquals(emptyList<SearchChip>(), parse("  ").chips)
    }

    @Test fun `plain words are file name terms and all must match`() {
        val p = parse("IMG vacaciones")
        assertEquals(listOf("IMG", "vacaciones"), p.textTerms)
        assertFalse(p.hasDate)
        assertEquals(listOf(SearchChip("«IMG»", SearchChip.Type.TEXT, "IMG"), SearchChip("«vacaciones»", SearchChip.Type.TEXT, "vacaciones")), p.chips)
    }

    @Test fun `a year is a date range`() {
        val p = parse("2023")
        assertEquals(range(20230101, 20231231), p.dateRange)
        assertTrue(p.textTerms.isEmpty())
        assertEquals("2023", p.chips.single().label)
    }

    @Test fun `numbers that are not plausible years stay text`() {
        assertEquals(listOf("4032"), parse("4032").textTerms)
        assertEquals(listOf("1985"), parse("1985").textTerms)
        assertEquals("a year after next year is not offered", listOf("2030"), parse("2030").textTerms)
        assertNotNull("next year is accepted (clock skew)", parse("2027").dateRange)
    }

    @Test fun `month and year in words`() {
        assertEquals(range(20230301, 20230331), parse("marzo 2023").dateRange)
        assertEquals(range(20230301, 20230331), parse("marzo de 2023").dateRange)
        assertEquals(range(20230301, 20230331), parse("Mar 2023").dateRange)
        assertEquals(range(20230901, 20230930), parse("sept 2023").dateRange)
        assertEquals(range(20240201, 20240229), parse("febrero 2024").dateRange)
        assertEquals("Marzo 2023", parse("marzo 2023").chips.single().label)
    }

    @Test fun `accents and case do not matter`() {
        assertEquals(range(20230901, 20230930), parse("SÉPTIEMBRE 2023").dateRange)
    }

    @Test fun `a month without a year means that month of every year`() {
        val p = parse("marzo")
        assertEquals(3, p.month)
        assertNull(p.dateRange)
        assertEquals("Cada marzo", p.chips.single().label)
    }

    @Test fun `numeric dates`() {
        assertEquals(range(20230315, 20230315), parse("2023-03-15").dateRange)
        assertEquals(range(20230315, 20230315), parse("15/03/2023").dateRange)
        assertEquals(range(20230315, 20230315), parse("15-03-2023").dateRange)
        assertEquals(range(20230301, 20230331), parse("2023-03").dateRange)
        assertEquals(range(20230301, 20230331), parse("03/2023").dateRange)
    }

    @Test fun `day and month in words`() {
        assertEquals(range(20230315, 20230315), parse("15 de marzo de 2023").dateRange)
        assertEquals(range(20230315, 20230315), parse("15 marzo 2023").dateRange)
        val p = parse("15 marzo")
        assertEquals(3, p.month)
        assertEquals(15, p.dayOfMonth)
        assertNull(p.dateRange)
    }

    @Test fun `invalid dates are not dates`() {
        val p = parse("31/02/2023")
        assertNull(p.dateRange)
        assertEquals(listOf("31/02/2023"), p.textTerms)
    }

    @Test fun `type words filter photos or videos`() {
        assertEquals(MediaKind.VIDEO, parse("videos").kind)
        assertEquals(MediaKind.VIDEO, parse("Vídeo").kind)
        assertEquals(MediaKind.IMAGE, parse("fotos 2023").kind)
        assertEquals(range(20230101, 20231231), parse("fotos 2023").dateRange)
    }

    @Test fun `terms and dates combine`() {
        val p = parse("IMG marzo 2023 videos")
        assertEquals(listOf("IMG"), p.textTerms)
        assertEquals(range(20230301, 20230331), p.dateRange)
        assertEquals(MediaKind.VIDEO, p.kind)
        assertEquals(3, p.chips.size)
    }

    @Test fun `two dates intersect and contradictions match nothing`() {
        assertEquals(range(20230301, 20230331), parse("2023 marzo 2023").dateRange)
        assertTrue("different years", parse("2022 2023").dateRange!!.isEmpty)
        assertTrue("two different months", parse("marzo abril").dateRange!!.isEmpty)
        val both = parse("marzo abril 2023")
        assertEquals("March of any year AND April 2023: the query itself is empty", 3, both.month)
        assertEquals(range(20230401, 20230430), both.dateRange)
    }

    @Test fun `punctuation around words is ignored`() {
        assertEquals(range(20230101, 20231231), parse("2023,").dateRange)
    }

    @Test fun `every chip remembers the text it came from`() {
        val p = parse("IMG marzo de 2023 videos 15 marzo")
        assertEquals(listOf("IMG", "marzo de 2023", "videos", "15 marzo"), p.chips.map { it.raw })
    }

    @Test fun `removing a chip removes just its part of the search`() {
        val text = "IMG marzo 2023 videos"
        val p = parse(text)
        assertEquals("IMG videos", SearchParser.remove(text, p.chips[1]))
        assertEquals("marzo 2023 videos", SearchParser.remove(text, p.chips[0]))
        assertEquals("IMG marzo 2023", SearchParser.remove(text, p.chips[2]))
        assertEquals("a chip without text changes nothing", text, SearchParser.remove(text, SearchChip("x", SearchChip.Type.TEXT)))
    }

    @Test fun `removing works with irregular spacing`() {
        val text = "marzo   2023  IMG"
        val p = parse(text)
        assertEquals("IMG", SearchParser.remove(text, p.chips[0]))
    }
}
