package com.s3vault.app.presentation

/** "hace 5 min", "hace 3 h", "hace 2 d"; a date beyond a month. Pure so it can be tested without a device. */
fun formatAge(thenMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val diff = nowMs - thenMs
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        diff < 0 -> "ahora"
        diff < minute -> "hace un momento"
        diff < hour -> "hace ${diff / minute} min"
        diff < day -> "hace ${diff / hour} h"
        diff < 30 * day -> "hace ${diff / day} d"
        else -> formatDateTime(thenMs)
    }
}

/** "1 elemento" / "12 elementos" with thousands separators in Spanish. */
fun formatItems(n: Int): String = if (n == 1) "1 elemento" else "%,d elementos".format(java.util.Locale.forLanguageTag("es"), n)

fun formatCount(n: Int): String = "%,d".format(java.util.Locale.forLanguageTag("es"), n)
