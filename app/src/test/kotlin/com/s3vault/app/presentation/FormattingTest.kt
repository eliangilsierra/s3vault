package com.s3vault.app.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingTest {
    private val now = 1_000_000_000_000L

    @Test fun `ages read as short Spanish phrases`() {
        assertEquals("hace un momento", formatAge(now - 30_000, now))
        assertEquals("hace 5 min", formatAge(now - 5 * 60_000, now))
        assertEquals("hace 3 h", formatAge(now - 3 * 3_600_000L, now))
        assertEquals("hace 2 d", formatAge(now - 2 * 86_400_000L, now))
        assertEquals("a moment in the future is not negative time", "ahora", formatAge(now + 5_000, now))
    }

    @Test fun `item counts use Spanish grouping and singular`() {
        assertEquals("1 elemento", formatItems(1))
        assertEquals("0 elementos", formatItems(0))
        assertEquals("1.234 elementos", formatItems(1234))
    }
}
