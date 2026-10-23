package com.s3vault.app.presentation.settings

import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The interface is Spanish-only: amounts, dates and ages follow the same locale. */
private val Es: Locale = Locale.forLanguageTag("es")

/**
 * US dollars, as AWS prices them. Cents are enough for amounts of a cent or more; below that, digits are added until
 * the first two significant ones show, because "US$ 0,00" for a real 0,0004 would read as free.
 */
fun formatUsd(amount: Double): String {
    val abs = kotlin.math.abs(amount)
    val decimals = when {
        abs == 0.0 || abs >= 0.01 -> 2
        else -> (2 - kotlin.math.floor(kotlin.math.log10(abs)).toInt()).coerceAtMost(8)
    }
    val nf = NumberFormat.getNumberInstance(Es).apply { minimumFractionDigits = decimals; maximumFractionDigits = decimals }
    val text = nf.format(amount)
    // Two significant digits were asked for; drop the zeros that only pad them (0,00040 -> 0,0004).
    return "US$ " + if (abs in 1e-12..0.0099999) text.trimEnd('0') else text
}

/** For secondary figures where a tiny exact amount would only be noise: "menos de US$ 0,01". */
fun formatUsdBrief(amount: Double): String = if (amount > 0 && amount < 0.01) "menos de US$ 0,01" else formatUsd(amount)

/** "1 solicitud LIST", "3 solicitudes LIST". */
fun formatRequests(n: Int, kind: String): String = if (n == 1) "1 solicitud $kind" else "$n solicitudes $kind"

/** Price per GB-month: always shows enough digits to tell 0,023 from 0,0125. */
fun formatUsdPerGb(price: Double): String {
    val nf = NumberFormat.getNumberInstance(Es).apply { minimumFractionDigits = 3; maximumFractionDigits = 5 }
    return "US$ " + nf.format(price)
}

fun formatCount(n: Long): String = NumberFormat.getIntegerInstance(Es).format(n)

/** "hace 5 min", "hace 3 h", "hace 12 días": how old an inventory is, in words. */
fun formatAge(ageMs: Long): String {
    val minutes = ageMs / 60_000
    return when {
        minutes < 1 -> "hace un momento"
        minutes < 60 -> "hace $minutes min"
        minutes < 60 * 24 -> "hace ${minutes / 60} h"
        else -> (minutes / (60 * 24)).let { d -> if (d == 1L) "hace 1 día" else "hace $d días" }
    }
}

fun formatMonthKey(yyyymm: Int): String =
    LocalDate.of(yyyymm / 100, yyyymm % 100, 1).format(DateTimeFormatter.ofPattern("LLLL 'de' yyyy", Es))

fun formatDateOnly(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("d MMM yyyy", Es))

fun formatDateTimeShort(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Es))
