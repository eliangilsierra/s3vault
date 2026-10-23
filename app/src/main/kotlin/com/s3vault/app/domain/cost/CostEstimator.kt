package com.s3vault.app.domain.cost

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** Where a number comes from. Shown next to every figure so measurements are never confused with guesses. */
enum class Basis {
    /** Read from the S3 listing (counts, bytes, storage class). Prices still come from the list-price table. */
    MEASURED,

    /** Computed from measured data with a stated rule, but the real bill can differ (lower bounds, upper bounds). */
    ESTIMATED,

    /** Depends on a number the app cannot know and had to assume. */
    ASSUMED,
}

enum class Confidence { HIGH, MEDIUM, LOW }

/** Uploads and cloud deletions logged by the app since the inventory was taken. */
data class PendingChanges(val uploads: Int = 0, val deletions: Int = 0) {
    val any: Boolean get() = uploads + deletions > 0
}

enum class ClassNoteKind {
    /** Objects under 128 KiB are billed as 128 KiB in this class; value = extra billed bytes. */
    MIN_SIZE_APPLIED,

    /** Archive classes add 40 KiB of metadata per object (32 KiB at the class price, 8 KiB at S3 Standard price). */
    ARCHIVE_OVERHEAD,

    /** The Frequent Access price is used; objects that moved to cheaper tiers cost less. Upper bound. */
    TIER_UNKNOWN,

    /** Intelligent-Tiering monitoring fee of the month; value = USD. */
    MONITORING_FEE,
}

data class ClassNote(val kind: ClassNoteKind, val value: Double = 0.0)

enum class Unpriced {
    /** No prices for this region in the table. */
    NO_PRICES_FOR_REGION,

    /** The class is not covered (e.g. Glacier Deep Archive has no storage product in the price list). */
    UNSUPPORTED_CLASS,

    /** The region's price list has no product for this class. */
    PRICE_MISSING,
}

data class ClassCost(
    val storageClass: String,
    val label: String,
    val objects: Long,
    val bytes: Long,
    /** Bytes the class bills for after its minimum-size and overhead rules. */
    val billedBytes: Long,
    val monthlyUsd: Double?,
    val unpriced: Unpriced?,
    /** Price of the first (or only) tier, for display. */
    val pricePerGb: Double?,
    val notes: List<ClassNote>,
)

enum class ReasonKind { PRICES_AGED, INVENTORY_AGED, PENDING_CHANGES, TIER_UNKNOWN, PREVIEWS_SHARE }

data class ConfidenceReason(val kind: ReasonKind, val severity: Confidence, val value: Long = 0, val value2: Long = 0)

/** Why there is no monthly total. A total is never shown over data that is incomplete or unpriced. */
enum class Blocker { INCOMPLETE_INVENTORY, NO_PRICES_FOR_REGION, UNSUPPORTED_CLASS }

data class CostEstimate(
    val classes: List<ClassCost>,
    /** Monthly storage of the listed objects at today's usage; null when [blocker] is set. */
    val storageUsd: Double?,
    /** Assumed size of the S3 previews (not listable) and its monthly cost. Zero when previews are off or unknown. */
    val previewsBytes: Long,
    val previewsUsd: Double?,
    /** [storageUsd] + [previewsUsd]. */
    val monthlyUsd: Double?,
    val blocker: Blocker?,
    /** Objects uploaded in the month of the inventory (lower bound of its PUT requests) and their price. */
    val putMonthKey: Int,
    val putObjects: Int,
    val putUsd: Double?,
    /** What one refresh of the inventory costs in LIST requests. */
    val listRequests: Int,
    val listUsd: Double?,
    val confidence: Confidence?,
    val reasons: List<ConfidenceReason>,
    val inventoryAgeDays: Long,
    val priceAgeDays: Long,
    /** Retrieval fee per GB for the classes that charge one and are present. */
    val retrievalPerGb: Map<String, Double>,
)

/**
 * Turns a measured inventory plus list prices into a monthly estimate, with every rule explicit:
 *
 *  - GB means GiB (2^30 bytes), as AWS bills; storage is averaged over the month, so the figure is "if today's usage
 *    lasted the whole month".
 *  - S3 Standard is priced by volume tier. Standard-IA, One Zone-IA and Glacier Instant Retrieval bill objects under
 *    128 KiB as 128 KiB. Glacier Flexible Retrieval adds 40 KiB per object. Intelligent-Tiering uses the Frequent
 *    Access price (an upper bound) plus the monitoring fee for objects of 128 KiB or more.
 *  - Any class that cannot be priced, missing region prices or an incomplete inventory give no total at all.
 *  - Previews are assumed (60 KiB each) because they cannot be listed with the recommended IAM policy.
 *  - Free tier, discounts, credits, taxes, other prefixes, versions, transfer, GET and retrieval are not included.
 */
object CostEstimator {
    const val GIB = 1024.0 * 1024.0 * 1024.0
    const val MIN_BILLABLE_BYTES = 128L * 1024
    const val ARCHIVE_OVERHEAD_AT_CLASS_BYTES = 32L * 1024
    const val ARCHIVE_OVERHEAD_AT_STANDARD_BYTES = 8L * 1024
    const val ASSUMED_PREVIEW_BYTES = 60L * 1024

    const val INVENTORY_AGED_DAYS = 7L
    const val INVENTORY_OLD_DAYS = 30L
    const val PREVIEWS_SHARE_LIMIT = 0.10

    fun label(storageClass: String): String = when (storageClass) {
        "STANDARD" -> "S3 Standard"
        "STANDARD_IA" -> "Standard-IA"
        "ONEZONE_IA" -> "One Zone-IA"
        "GLACIER_IR" -> "Glacier Instant Retrieval"
        "GLACIER" -> "Glacier Flexible Retrieval"
        "DEEP_ARCHIVE" -> "Glacier Deep Archive"
        "INTELLIGENT_TIERING" -> "Intelligent-Tiering"
        "REDUCED_REDUNDANCY" -> "Reduced Redundancy"
        else -> storageClass
    }

    /** Volume-tiered price of [gb]; tiers are consecutive and the last one has no upper limit. */
    fun tieredCost(gb: Double, tiers: List<Tier>): Double {
        var remaining = gb
        var lower = 0.0
        var total = 0.0
        for (t in tiers) {
            if (remaining <= 0) break
            val cap = t.upToGb?.toDouble() ?: Double.POSITIVE_INFINITY
            val inTier = minOf(remaining, cap - lower)
            if (inTier > 0) { total += inTier * t.pricePerGb; remaining -= inTier }
            lower = cap
        }
        return total
    }

    fun estimate(
        inventory: StorageInventory,
        prices: S3RegionPrices?,
        now: Instant,
        includePreviews: Boolean,
        changes: PendingChanges = PendingChanges(),
        priceListPublished: LocalDate = S3Pricing.published,
    ): CostEstimate {
        val classes = inventory.classes.map { priceClass(it, prices) }
        val blocker = when {
            !inventory.complete -> Blocker.INCOMPLETE_INVENTORY
            prices == null -> Blocker.NO_PRICES_FOR_REGION
            classes.any { it.unpriced != null } -> Blocker.UNSUPPORTED_CLASS
            else -> null
        }
        val storageUsd = if (blocker == null) classes.sumOf { it.monthlyUsd ?: 0.0 } else null

        val previewsBytes = if (includePreviews && blocker == null) inventory.photoObjects * ASSUMED_PREVIEW_BYTES else 0L
        val previewsUsd = if (blocker == null && prices != null) previewsBytes / GIB * prices.standard.first().pricePerGb else null
        val monthlyUsd = if (storageUsd != null) storageUsd + (previewsUsd ?: 0.0) else null

        val putKey = StorageInventory.monthKey(inventory.takenAtMs)
        val putObjects = inventory.modifiedByMonth[putKey] ?: 0
        val today = now.atZone(ZoneOffset.UTC).toLocalDate()
        val inventoryAge = ChronoUnit.DAYS.between(Instant.ofEpochMilli(inventory.takenAtMs), now).coerceAtLeast(0)
        val priceAge = S3Pricing.ageDays(today, priceListPublished)

        val reasons = if (monthlyUsd == null) emptyList() else buildList {
            if (priceAge > S3Pricing.OLD_AFTER_DAYS) add(ConfidenceReason(ReasonKind.PRICES_AGED, Confidence.LOW, priceAge))
            else if (priceAge > S3Pricing.AGED_AFTER_DAYS) add(ConfidenceReason(ReasonKind.PRICES_AGED, Confidence.MEDIUM, priceAge))
            if (inventoryAge > INVENTORY_OLD_DAYS) add(ConfidenceReason(ReasonKind.INVENTORY_AGED, Confidence.LOW, inventoryAge))
            else if (inventoryAge > INVENTORY_AGED_DAYS) add(ConfidenceReason(ReasonKind.INVENTORY_AGED, Confidence.MEDIUM, inventoryAge))
            if (changes.any) add(ConfidenceReason(ReasonKind.PENDING_CHANGES, Confidence.MEDIUM, changes.uploads.toLong(), changes.deletions.toLong()))
            if (classes.any { it.storageClass == "INTELLIGENT_TIERING" && it.objects > 0 }) add(ConfidenceReason(ReasonKind.TIER_UNKNOWN, Confidence.MEDIUM))
            if (monthlyUsd > 0 && (previewsUsd ?: 0.0) / monthlyUsd > PREVIEWS_SHARE_LIMIT) add(ConfidenceReason(ReasonKind.PREVIEWS_SHARE, Confidence.MEDIUM))
        }
        val confidence = when {
            monthlyUsd == null -> null
            reasons.any { it.severity == Confidence.LOW } -> Confidence.LOW
            reasons.isNotEmpty() -> Confidence.MEDIUM
            else -> Confidence.HIGH
        }

        val retrieval = buildMap {
            if (prices != null) {
                val present = inventory.classes.map { it.storageClass }.toSet()
                if ("STANDARD_IA" in present) prices.retrievalStandardIaPerGb?.let { put("STANDARD_IA", it) }
                if ("ONEZONE_IA" in present) prices.retrievalOneZoneIaPerGb?.let { put("ONEZONE_IA", it) }
                if ("GLACIER_IR" in present) prices.retrievalGlacierIrPerGb?.let { put("GLACIER_IR", it) }
            }
        }

        return CostEstimate(
            classes = classes, storageUsd = storageUsd, previewsBytes = previewsBytes, previewsUsd = previewsUsd,
            monthlyUsd = monthlyUsd, blocker = blocker,
            putMonthKey = putKey, putObjects = putObjects, putUsd = prices?.putPerRequest?.let { it * putObjects },
            listRequests = inventory.listRequests, listUsd = prices?.putPerRequest?.let { it * inventory.listRequests },
            confidence = confidence, reasons = reasons, inventoryAgeDays = inventoryAge, priceAgeDays = priceAge,
            retrievalPerGb = retrieval,
        )
    }

    private fun priceClass(u: ClassUsage, prices: S3RegionPrices?): ClassCost {
        fun unpriced(why: Unpriced) = ClassCost(u.storageClass, label(u.storageClass), u.objects, u.bytes, u.bytes, null, why, null, emptyList())
        if (prices == null) return unpriced(Unpriced.NO_PRICES_FOR_REGION)

        // Bytes billed by classes with a 128 KiB minimum: small objects count as 128 KiB.
        val minSizeExtra = u.smallObjects * MIN_BILLABLE_BYTES - u.smallBytes
        fun flatMinSize(price: Double?): ClassCost {
            price ?: return unpriced(Unpriced.PRICE_MISSING)
            val billed = u.bytes + minSizeExtra
            val notes = if (minSizeExtra > 0) listOf(ClassNote(ClassNoteKind.MIN_SIZE_APPLIED, minSizeExtra.toDouble())) else emptyList()
            return ClassCost(u.storageClass, label(u.storageClass), u.objects, u.bytes, billed, billed / GIB * price, null, price, notes)
        }

        return when (u.storageClass) {
            "STANDARD" -> ClassCost(
                u.storageClass, label(u.storageClass), u.objects, u.bytes, u.bytes,
                tieredCost(u.bytes / GIB, prices.standard), null, prices.standard.first().pricePerGb, emptyList(),
            )
            "STANDARD_IA" -> flatMinSize(prices.standardIa)
            "ONEZONE_IA" -> flatMinSize(prices.oneZoneIa)
            "GLACIER_IR" -> flatMinSize(prices.glacierIr)
            "GLACIER" -> {
                val price = prices.glacierFlexible ?: return unpriced(Unpriced.PRICE_MISSING)
                val atClass = u.bytes + u.objects * ARCHIVE_OVERHEAD_AT_CLASS_BYTES
                val atStandard = u.objects * ARCHIVE_OVERHEAD_AT_STANDARD_BYTES
                val cost = atClass / GIB * price + atStandard / GIB * prices.standard.first().pricePerGb
                ClassCost(
                    u.storageClass, label(u.storageClass), u.objects, u.bytes, atClass + atStandard, cost, null, price,
                    listOf(ClassNote(ClassNoteKind.ARCHIVE_OVERHEAD, (atClass + atStandard - u.bytes).toDouble())),
                )
            }
            "INTELLIGENT_TIERING" -> {
                if (prices.intelligentFrequent.isEmpty()) return unpriced(Unpriced.PRICE_MISSING)
                val monitored = u.objects - u.smallObjects
                val fee = (prices.intelligentMonitoringPerObject ?: 0.0) * monitored
                val cost = tieredCost(u.bytes / GIB, prices.intelligentFrequent) + fee
                ClassCost(
                    u.storageClass, label(u.storageClass), u.objects, u.bytes, u.bytes, cost, null, prices.intelligentFrequent.first().pricePerGb,
                    listOf(ClassNote(ClassNoteKind.TIER_UNKNOWN), ClassNote(ClassNoteKind.MONITORING_FEE, fee)),
                )
            }
            else -> unpriced(Unpriced.UNSUPPORTED_CLASS)
        }
    }
}
