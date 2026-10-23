package com.s3vault.app.domain.cost

import com.s3vault.app.domain.AuthMethod
import com.s3vault.app.domain.RemoteKeys
import com.s3vault.app.domain.RemoteListing
import com.s3vault.app.domain.S3Config
import com.s3vault.app.presentation.settings.formatAge
import com.s3vault.app.presentation.settings.formatMonthKey
import com.s3vault.app.presentation.settings.formatRequests
import com.s3vault.app.presentation.settings.formatUsdBrief
import com.s3vault.app.presentation.settings.formatUsd
import com.s3vault.app.presentation.settings.formatUsdPerGb
import com.s3vault.app.settings.InventoryCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class StorageInventoryTest {
    private val keys = RemoteKeys("p/")
    private val a = keys.photoKey("a".repeat(32), "b".repeat(64), "one.jpg")
    private val b = keys.photoKey("c".repeat(32), "d".repeat(64), "two.jpg")

    private fun build(vararg l: RemoteListing, requests: Int = 1) =
        StorageInventory.from(l.toList(), keys::isPhotoKey, "eu-west-1", "bucket", "p/", takenAtMs = 5_000, listRequests = requests)

    @Test fun `objects are counted by storage class, biggest class first`() {
        val inv = build(
            RemoteListing(a, 4_000_000, 1, "STANDARD"),
            RemoteListing(b, 10_000_000, 1, "GLACIER_IR"),
            RemoteListing(keys.photoKey("e".repeat(32), "f".repeat(64), "three.jpg"), 2_000_000, 1, "STANDARD"),
        )
        assertEquals(listOf("GLACIER_IR", "STANDARD"), inv.classes.map { it.storageClass })
        assertEquals(2L, inv.classes.single { it.storageClass == "STANDARD" }.objects)
        assertEquals(6_000_000L, inv.classes.single { it.storageClass == "STANDARD" }.bytes)
        assertEquals(3L, inv.totalObjects)
        assertEquals(16_000_000L, inv.totalBytes)
        assertEquals(3L, inv.photoObjects)
    }

    @Test fun `a listing that does not name the class is Standard`() {
        assertEquals("STANDARD", build(RemoteListing(a, 1, 1, null)).classes.single().storageClass)
    }

    @Test fun `objects under 128 KiB are tracked because some classes bill them as 128 KiB`() {
        val inv = build(RemoteListing(a, 131_071, 1, "STANDARD_IA"), RemoteListing(b, 131_072, 1, "STANDARD_IA"), RemoteListing("p/photos/x", 0, 1, "STANDARD_IA"))
        val c = inv.classes.single()
        assertEquals(2L, c.smallObjects)
        assertEquals(131_071L, c.smallBytes)
    }

    @Test fun `objects that are not photos are still counted, and counted apart`() {
        val inv = build(RemoteListing(a, 100, 1, "STANDARD"), RemoteListing("p/photos/notes.txt", 5_000, 1, "STANDARD"))
        assertEquals(2L, inv.totalObjects)
        assertEquals(5_100L, inv.totalBytes)
        assertEquals(1L, inv.photoObjects)
        assertEquals(1L, inv.unrecognizedObjects)
        assertEquals(5_000L, inv.unrecognizedBytes)
    }

    @Test fun `upload months are UTC and an unknown date is ignored`() {
        val inv = build(
            RemoteListing(a, 1, Instant.parse("2026-10-31T23:59:59Z").toEpochMilli()),
            RemoteListing(b, 1, Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()),
            RemoteListing("p/photos/c", 1, Instant.parse("2026-11-15T10:00:00Z").toEpochMilli()),
            RemoteListing("p/photos/d", 1, 0),
        )
        assertEquals(mapOf(202_610 to 1, 202_611 to 2), inv.modifiedByMonth)
        assertEquals(202_601, StorageInventory.monthKey(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()))
    }

    @Test fun `an empty listing is a valid, empty inventory`() {
        val inv = build(requests = 1)
        assertTrue(inv.complete)
        assertEquals(0L, inv.totalObjects)
        assertEquals(1, inv.listRequests)
    }

    @Test fun `an inventory only describes the configuration it was taken for`() {
        val inv = build(RemoteListing(a, 1, 1))
        assertTrue(inv.matches(S3Config("eu-west-1", "bucket", "p/", AuthMethod.STATIC)))
        assertFalse(inv.matches(S3Config("eu-west-1", "other", "p/", AuthMethod.STATIC)))
        assertFalse(inv.matches(S3Config("us-east-1", "bucket", "p/", AuthMethod.STATIC)))
        assertFalse(inv.matches(S3Config("eu-west-1", "bucket", "q/", AuthMethod.STATIC)))
    }
}

class InventoryCodecTest {
    private val inv = StorageInventory(
        region = "eu-west-1", bucket = "mi-bucket", prefix = "fotos/", takenAtMs = 1_700_000_000_123, listRequests = 4,
        classes = listOf(ClassUsage("STANDARD", 10, 4_000_000_000, 1, 100), ClassUsage("GLACIER_IR", 3, 9_000, 3, 9_000)),
        photoObjects = 12, unrecognizedObjects = 1, unrecognizedBytes = 77, modifiedByMonth = mapOf(202_610 to 5, 202_509 to 8), complete = true,
    )

    @Test fun `an inventory survives storage unchanged`() {
        assertEquals(inv, InventoryCodec.decode(InventoryCodec.encode(inv)))
    }

    @Test fun `the stored document holds counts and the bucket name, nothing else`() {
        val text = InventoryCodec.encode(inv)
        assertTrue(text.length < 600)
        assertFalse(text.contains("AKIA"))
        assertFalse(text.contains(".jpg"))
    }

    @Test fun `unreadable or foreign data means no inventory rather than a crash or a wrong one`() {
        assertNull(InventoryCodec.decode(""))
        assertNull(InventoryCodec.decode("not json"))
        assertNull(InventoryCodec.decode("{}"))
        assertNull(InventoryCodec.decode(InventoryCodec.encode(inv).replace("\"v\":1", "\"v\":99")))
        assertNull(InventoryCodec.decode(InventoryCodec.encode(inv).replace("\"takenAtMs\"", "\"x\"")))
    }

    @Test fun `an incomplete inventory stays incomplete after a round trip`() {
        assertFalse(InventoryCodec.decode(InventoryCodec.encode(inv.copy(complete = false)))!!.complete)
    }
}

class CostFormatTest {
    @Test fun `small amounts never read as free`() {
        assertEquals("US$ 0,00", formatUsd(0.0))
        assertEquals("US$ 0,23", formatUsd(0.23))
        assertEquals("a cent or more is shown in cents, rounded", "US$ 0,01", formatUsd(0.0149))
        assertEquals("US$ 0,0099", formatUsd(0.0099))
        assertEquals("US$ 0,0004", formatUsd(0.0004))
        assertEquals("US$ 0,000005", formatUsd(0.000005))
        assertEquals("US$ 12.345,50", formatUsd(12_345.5))
    }

    @Test fun `secondary tiny amounts are summarised and requests agree in number`() {
        assertEquals("menos de US$ 0,01", formatUsdBrief(0.00000526))
        assertEquals("US$ 0,00", formatUsdBrief(0.0))
        assertEquals("US$ 4,76", formatUsdBrief(4.76))
        assertEquals("1 solicitud LIST", formatRequests(1, "LIST"))
        assertEquals("3 solicitudes LIST", formatRequests(3, "LIST"))
    }

    @Test fun `a price per GB shows enough digits to tell tiers apart`() {
        assertEquals("US$ 0,023", formatUsdPerGb(0.023))
        assertEquals("US$ 0,0125", formatUsdPerGb(0.0125))
        assertEquals("US$ 0,00099", formatUsdPerGb(0.00099))
    }

    @Test fun `ages are written the way people say them`() {
        assertEquals("hace un momento", formatAge(30_000))
        assertEquals("hace 5 min", formatAge(5 * 60_000))
        assertEquals("hace 3 h", formatAge(3 * 3_600_000L))
        assertEquals("hace 1 día", formatAge(26 * 3_600_000L))
        assertEquals("hace 12 días", formatAge(12 * 86_400_000L))
        assertEquals("hace un momento", formatAge(-5))
    }

    @Test fun `months are written in Spanish`() {
        assertEquals("octubre de 2026", formatMonthKey(202_610))
        assertEquals("enero de 2025", formatMonthKey(202_501))
    }
}
