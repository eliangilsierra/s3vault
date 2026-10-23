package com.s3vault.app.domain.cost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

private const val GIB = 1L shl 30
private const val KIB = 1024L
private const val DELTA = 1e-9

private val NOW: Instant = Instant.parse("2026-10-23T12:00:00Z")
private val TODAY: LocalDate = LocalDate.of(2026, 10, 23)

/** Round list prices so every expected figure in these tests can be checked by hand. */
private val PRICES = S3RegionPrices(
    standard = listOf(Tier(51_200, 0.023), Tier(512_000, 0.022), Tier(null, 0.021)),
    standardIa = 0.0125, oneZoneIa = 0.01, glacierIr = 0.004, glacierFlexible = 0.0036,
    intelligentFrequent = listOf(Tier(51_200, 0.023), Tier(512_000, 0.022), Tier(null, 0.021)),
    intelligentMonitoringPerObject = 0.0000025,
    putPerRequest = 0.000005, getPerRequest = 0.0000004,
    retrievalStandardIaPerGb = 0.01, retrievalOneZoneIaPerGb = 0.01, retrievalGlacierIrPerGb = 0.03,
)

private fun inventory(
    vararg classes: ClassUsage,
    takenAt: Instant = NOW.minusSeconds(3600),
    photos: Long = 0,
    months: Map<Int, Int> = emptyMap(),
    complete: Boolean = true,
    requests: Int = 1,
) = StorageInventory(
    region = "eu-west-1", bucket = "b", prefix = "p/", takenAtMs = takenAt.toEpochMilli(), listRequests = requests,
    classes = classes.toList(), photoObjects = photos, unrecognizedObjects = 0, unrecognizedBytes = 0, modifiedByMonth = months, complete = complete,
)

private fun usage(cls: String, objects: Long, bytes: Long, small: Long = 0, smallBytes: Long = 0) = ClassUsage(cls, objects, bytes, small, smallBytes)

private fun estimate(
    inv: StorageInventory,
    prices: S3RegionPrices? = PRICES,
    previews: Boolean = false,
    changes: PendingChanges = PendingChanges(),
    published: LocalDate = S3Pricing.published,
) = CostEstimator.estimate(inv, prices, NOW, previews, changes, published)

class CostEstimatorTest {
    // ------------------------------------------------------------------ storage maths

    @Test fun `one GiB of Standard costs exactly the first tier price`() {
        val e = estimate(inventory(usage("STANDARD", 10, GIB)))
        assertEquals(0.023, e.monthlyUsd!!, DELTA)
        assertEquals(0.023, e.storageUsd!!, DELTA)
    }

    @Test fun `a gigabyte is 2^30 bytes, not 10^9`() {
        // 10^9 bytes is 0.9313 GiB.
        assertEquals(1_000_000_000.0 / GIB * 0.023, estimate(inventory(usage("STANDARD", 1, 1_000_000_000))).monthlyUsd!!, DELTA)
    }

    @Test fun `Standard is priced by volume tier`() {
        // 60,000 GB: 51,200 at 0.023 and the remaining 8,800 at 0.022.
        val e = estimate(inventory(usage("STANDARD", 1, 60_000L * GIB)))
        assertEquals(51_200 * 0.023 + 8_800 * 0.022, e.monthlyUsd!!, 1e-6)
        assertEquals(0.0, CostEstimator.tieredCost(0.0, PRICES.standard), DELTA)
        assertEquals(1.0 * 0.021 + 51_200 * 0.023 + 460_800 * 0.022, CostEstimator.tieredCost(512_001.0, PRICES.standard), 1e-6)
    }

    @Test fun `infrequent access classes bill small objects as 128 KiB`() {
        // 1,000 objects of 10 KiB: stored 10,240,000 bytes but billed 1,000 x 128 KiB.
        val e = estimate(inventory(usage("STANDARD_IA", 1000, 1000 * 10 * KIB, small = 1000, smallBytes = 1000 * 10 * KIB)))
        val billed = 1000 * 128 * KIB
        val c = e.classes.single()
        assertEquals(billed, c.billedBytes)
        assertEquals(billed.toDouble() / GIB * 0.0125, e.monthlyUsd!!, DELTA)
        assertEquals(ClassNoteKind.MIN_SIZE_APPLIED, c.notes.single().kind)
        assertEquals((billed - 1000 * 10 * KIB).toDouble(), c.notes.single().value, 0.0)
    }

    @Test fun `only the small objects are rounded up, big ones are billed as they are`() {
        val big = 10L * 1024 * KIB
        val small = 1000 * 10 * KIB
        val e = estimate(inventory(usage("GLACIER_IR", 1010, big + small, small = 1000, smallBytes = small)))
        assertEquals(big + 1000 * 128 * KIB, e.classes.single().billedBytes)
        assertEquals((big + 1000 * 128 * KIB).toDouble() / GIB * 0.004, e.monthlyUsd!!, DELTA)
    }

    @Test fun `Standard has no minimum object size`() {
        val e = estimate(inventory(usage("STANDARD", 1000, 1000 * 10 * KIB, small = 1000, smallBytes = 1000 * 10 * KIB)))
        assertEquals(1000 * 10 * KIB, e.classes.single().billedBytes)
        assertTrue(e.classes.single().notes.isEmpty())
    }

    @Test fun `Glacier Flexible adds 40 KiB per object, split between its own price and Standard`() {
        val bytes = 1000 * 1024 * KIB
        val e = estimate(inventory(usage("GLACIER", 1000, bytes)))
        val atClass = bytes + 1000 * 32 * KIB
        val atStandard = 1000 * 8 * KIB
        assertEquals(atClass.toDouble() / GIB * 0.0036 + atStandard.toDouble() / GIB * 0.023, e.monthlyUsd!!, DELTA)
        assertEquals(ClassNoteKind.ARCHIVE_OVERHEAD, e.classes.single().notes.single().kind)
    }

    @Test fun `Intelligent-Tiering uses the Frequent Access price as an upper bound plus monitoring of objects of 128 KiB or more`() {
        val bytes = 100 * 1024 * KIB
        val e = estimate(inventory(usage("INTELLIGENT_TIERING", 100, bytes, small = 40, smallBytes = 40 * KIB)))
        val fee = 60 * 0.0000025
        assertEquals(bytes.toDouble() / GIB * 0.023 + fee, e.monthlyUsd!!, DELTA)
        val notes = e.classes.single().notes
        assertTrue(notes.any { it.kind == ClassNoteKind.TIER_UNKNOWN })
        assertEquals(fee, notes.single { it.kind == ClassNoteKind.MONITORING_FEE }.value, DELTA)
        assertEquals("an upper bound is not a high-confidence figure", Confidence.MEDIUM, e.confidence)
        assertTrue(e.reasons.any { it.kind == ReasonKind.TIER_UNKNOWN })
    }

    @Test fun `several classes add up`() {
        val e = estimate(inventory(usage("STANDARD", 1, GIB), usage("GLACIER_IR", 1, 10 * GIB)))
        assertEquals(0.023 + 10 * 0.004, e.monthlyUsd!!, DELTA)
        assertEquals(2, e.classes.size)
    }

    // ------------------------------------------------------------------ never a misleading total

    @Test fun `a class without a price gives no total at all`() {
        val e = estimate(inventory(usage("STANDARD", 1, GIB), usage("DEEP_ARCHIVE", 5, 5 * GIB)))
        assertNull(e.monthlyUsd); assertNull(e.storageUsd); assertNull(e.confidence)
        assertEquals(Blocker.UNSUPPORTED_CLASS, e.blocker)
        assertEquals(Unpriced.UNSUPPORTED_CLASS, e.classes.single { it.storageClass == "DEEP_ARCHIVE" }.unpriced)
        assertNotNull("the class that can be priced still shows its own amount", e.classes.single { it.storageClass == "STANDARD" }.monthlyUsd)
    }

    @Test fun `a region without prices gives no total`() {
        val e = estimate(inventory(usage("STANDARD", 1, GIB)), prices = null)
        assertNull(e.monthlyUsd)
        assertEquals(Blocker.NO_PRICES_FOR_REGION, e.blocker)
        assertEquals(Unpriced.NO_PRICES_FOR_REGION, e.classes.single().unpriced)
        assertNull(e.putUsd); assertNull(e.listUsd)
    }

    @Test fun `an incomplete inventory gives no total, whatever else is known`() {
        val e = estimate(inventory(usage("STANDARD", 1, GIB), complete = false))
        assertNull(e.monthlyUsd); assertNull(e.confidence)
        assertEquals(Blocker.INCOMPLETE_INVENTORY, e.blocker)
        assertEquals("incomplete wins over missing prices", Blocker.INCOMPLETE_INVENTORY, estimate(inventory(complete = false), prices = null).blocker)
    }

    @Test fun `a class missing from the region price list is reported, not guessed`() {
        val e = estimate(inventory(usage("GLACIER", 1, GIB)), prices = PRICES.copy(glacierFlexible = null))
        assertEquals(Unpriced.PRICE_MISSING, e.classes.single().unpriced)
        assertNull(e.monthlyUsd)
    }

    @Test fun `an empty bucket costs zero with high confidence`() {
        val e = estimate(inventory())
        assertEquals(0.0, e.monthlyUsd!!, 0.0)
        assertEquals(Confidence.HIGH, e.confidence)
        assertTrue(e.classes.isEmpty())
    }

    // ------------------------------------------------------------------ assumptions

    @Test fun `previews are an assumption of 60 KiB per photo at the Standard price and are optional`() {
        val inv = inventory(usage("STANDARD", 1000, 1000 * 4096 * KIB), photos = 1000)
        val without = estimate(inv, previews = false)
        val with = estimate(inv, previews = true)
        assertEquals(0L, without.previewsBytes)
        assertEquals(1000 * 60 * KIB, with.previewsBytes)
        assertEquals((1000 * 60 * KIB).toDouble() / GIB * 0.023, with.previewsUsd!!, DELTA)
        assertEquals(without.storageUsd!! + with.previewsUsd!!, with.monthlyUsd!!, DELTA)
        assertEquals("the measured part does not change", without.storageUsd, with.storageUsd)
    }

    @Test fun `when previews dominate the figure the confidence says so`() {
        val e = estimate(inventory(usage("STANDARD", 1000, 1000 * KIB), photos = 1000), previews = true)
        assertTrue(e.reasons.any { it.kind == ReasonKind.PREVIEWS_SHARE })
        assertEquals(Confidence.MEDIUM, e.confidence)
    }

    // ------------------------------------------------------------------ confidence

    @Test fun `fresh data, fresh prices and nothing pending is high confidence`() {
        val e = estimate(inventory(usage("STANDARD", 10, GIB)), published = TODAY.minusDays(10))
        assertEquals(Confidence.HIGH, e.confidence)
        assertTrue(e.reasons.isEmpty())
    }

    @Test fun `an inventory older than a week is medium and older than a month is low`() {
        val week = estimate(inventory(usage("STANDARD", 1, GIB), takenAt = NOW.minusSeconds(8 * 86_400L)))
        assertEquals(Confidence.MEDIUM, week.confidence)
        assertEquals(8L, week.reasons.single { it.kind == ReasonKind.INVENTORY_AGED }.value)
        assertEquals(Confidence.LOW, estimate(inventory(usage("STANDARD", 1, GIB), takenAt = NOW.minusSeconds(31 * 86_400L))).confidence)
        assertEquals("exactly 7 days is still fine", Confidence.HIGH, estimate(inventory(usage("STANDARD", 1, GIB), takenAt = NOW.minusSeconds(7 * 86_400L))).confidence)
    }

    @Test fun `prices older than 180 days warn and older than a year are low confidence`() {
        val inv = inventory(usage("STANDARD", 1, GIB))
        assertEquals(Confidence.HIGH, estimate(inv, published = TODAY.minusDays(180)).confidence)
        assertEquals(Confidence.MEDIUM, estimate(inv, published = TODAY.minusDays(181)).confidence)
        assertEquals(Confidence.MEDIUM, estimate(inv, published = TODAY.minusDays(365)).confidence)
        val old = estimate(inv, published = TODAY.minusDays(366))
        assertEquals(Confidence.LOW, old.confidence)
        assertEquals(366L, old.priceAgeDays)
    }

    @Test fun `uploads or deletions since the inventory lower the confidence`() {
        val e = estimate(inventory(usage("STANDARD", 1, GIB)), changes = PendingChanges(uploads = 12, deletions = 3))
        assertEquals(Confidence.MEDIUM, e.confidence)
        val r = e.reasons.single { it.kind == ReasonKind.PENDING_CHANGES }
        assertEquals(12L, r.value); assertEquals(3L, r.value2)
    }

    @Test fun `the lowest severity wins`() {
        val e = estimate(inventory(usage("STANDARD", 1, GIB), takenAt = NOW.minusSeconds(40 * 86_400L)), changes = PendingChanges(1, 0))
        assertEquals(Confidence.LOW, e.confidence)
        assertEquals(2, e.reasons.size)
    }

    // ------------------------------------------------------------------ requests and context

    @Test fun `PUT requests are a lower bound taken from the upload month of the inventory`() {
        val takenAt = Instant.parse("2026-10-20T08:00:00Z")
        val inv = inventory(usage("STANDARD", 1, GIB), takenAt = takenAt, months = mapOf(202_610 to 42, 202_609 to 900))
        val e = estimate(inv)
        assertEquals(202_610, e.putMonthKey)
        assertEquals(42, e.putObjects)
        assertEquals(42 * 0.000005, e.putUsd!!, DELTA)
        assertEquals("a month with no uploads is zero, not unknown", 0, estimate(inv.copy(modifiedByMonth = emptyMap())).putObjects)
    }

    @Test fun `a refresh costs one LIST per thousand objects at the PUT price`() {
        val e = estimate(inventory(usage("STANDARD", 2500, GIB), requests = 3))
        assertEquals(3, e.listRequests)
        assertEquals(3 * 0.000005, e.listUsd!!, DELTA)
    }

    @Test fun `retrieval prices are listed only for the classes that charge them and are present`() {
        val e = estimate(inventory(usage("STANDARD", 1, GIB), usage("GLACIER_IR", 1, GIB), usage("STANDARD_IA", 1, GIB)))
        assertEquals(mapOf("GLACIER_IR" to 0.03, "STANDARD_IA" to 0.01), e.retrievalPerGb)
        assertTrue(estimate(inventory(usage("STANDARD", 1, GIB))).retrievalPerGb.isEmpty())
    }

    @Test fun `labels are readable and unknown classes keep their own name`() {
        assertEquals("Glacier Instant Retrieval", CostEstimator.label("GLACIER_IR"))
        assertEquals("SOMETHING_NEW", CostEstimator.label("SOMETHING_NEW"))
    }
}

class S3PricingTableTest {
    @Test fun `the table has the prices published by AWS for the regions people use`() {
        val us = S3Pricing.forRegion("us-east-1")!!
        assertEquals(0.023, us.standard.first().pricePerGb, DELTA)
        assertEquals(0.0125, us.standardIa!!, DELTA)
        assertEquals(0.004, us.glacierIr!!, DELTA)
        assertEquals(0.005 / 1000, us.putPerRequest!!, DELTA)
        assertEquals(0.0004 / 1000, us.getPerRequest!!, DELTA)
        assertEquals(0.03, us.retrievalGlacierIrPerGb!!, DELTA)
        assertEquals("eu-west-1 is the region the docs use as an example", 0.023, S3Pricing.forRegion("eu-west-1")!!.standard.first().pricePerGb, DELTA)
        assertTrue("sa-east-1 is famously more expensive", S3Pricing.forRegion("sa-east-1")!!.standard.first().pricePerGb > 0.04)
    }

    @Test fun `every region is complete and its tiers are ordered`() {
        assertTrue(S3Pricing.regions.size >= 30)
        S3Pricing.regions.forEach { r ->
            val p = S3Pricing.forRegion(r)!!
            assertTrue("$r standard", p.standard.isNotEmpty() && p.standard.all { it.pricePerGb > 0 })
            assertNull("$r: last tier has no limit", p.standard.last().upToGb)
            assertEquals("$r: tiers ascend", p.standard.mapNotNull { it.upToGb }.sorted(), p.standard.mapNotNull { it.upToGb })
            assertTrue("$r: tiers do not get dearer", p.standard.map { it.pricePerGb }.zipWithNext().all { (a, b) -> b <= a })
            listOf(p.standardIa, p.oneZoneIa, p.glacierIr, p.glacierFlexible, p.putPerRequest, p.getPerRequest, p.retrievalGlacierIrPerGb, p.intelligentMonitoringPerObject)
                .forEach { assertTrue("$r has every price", it != null && it > 0) }
            assertTrue("$r: Standard-IA is cheaper than Standard", p.standardIa!! < p.standard.first().pricePerGb)
            assertTrue("$r: Glacier IR is cheaper than Standard-IA", p.glacierIr!! < p.standardIa!!)
        }
    }

    @Test fun `region lookup ignores case and spaces and unknown regions have no prices`() {
        assertNotNull(S3Pricing.forRegion(" EU-West-1 "))
        assertNull(S3Pricing.forRegion("mars-north-1"))
        assertNull(S3Pricing.forRegion(""))
        assertFalse(S3Pricing.regions.any { it.contains("-ath-") || it.contains("-han-") || it == "aws-other" })
    }

    @Test fun `the price list says when it was published and its age is never negative`() {
        assertEquals(LocalDate.of(2026, 10, 15), S3Pricing.published)
        assertEquals(8L, S3Pricing.ageDays(LocalDate.of(2026, 10, 23)))
        assertEquals(0L, S3Pricing.ageDays(LocalDate.of(2026, 1, 1)))
        assertEquals(10L, S3Pricing.ageDays(TODAY, TODAY.minusDays(10)))
    }
}
