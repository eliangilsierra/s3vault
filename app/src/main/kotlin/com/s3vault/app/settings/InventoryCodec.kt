package com.s3vault.app.settings

import com.s3vault.app.domain.cost.ClassUsage
import com.s3vault.app.domain.cost.StorageInventory
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores the last inventory as a small JSON document (a few hundred bytes: one entry per storage class and per upload
 * month). It holds counts, sizes and the bucket name that is already in the settings; no credentials, keys or file names.
 */
object InventoryCodec {
    private const val VERSION = 1

    fun encode(i: StorageInventory): String = JSONObject().apply {
        put("v", VERSION)
        put("region", i.region); put("bucket", i.bucket); put("prefix", i.prefix)
        put("takenAtMs", i.takenAtMs); put("listRequests", i.listRequests); put("complete", i.complete)
        put("photoObjects", i.photoObjects)
        put("unrecognizedObjects", i.unrecognizedObjects); put("unrecognizedBytes", i.unrecognizedBytes)
        put(
            "classes",
            JSONArray().apply {
                i.classes.forEach { c ->
                    put(JSONObject().put("c", c.storageClass).put("o", c.objects).put("b", c.bytes).put("so", c.smallObjects).put("sb", c.smallBytes))
                }
            },
        )
        put("months", JSONObject().apply { i.modifiedByMonth.forEach { (m, n) -> put(m.toString(), n) } })
    }.toString()

    /** Null for anything unreadable or from an unknown version: the app then simply has no inventory. */
    fun decode(text: String): StorageInventory? = try {
        val o = JSONObject(text)
        if (o.getInt("v") != VERSION) null else {
            val classes = o.getJSONArray("classes").let { a ->
                (0 until a.length()).map { idx ->
                    a.getJSONObject(idx).let { ClassUsage(it.getString("c"), it.getLong("o"), it.getLong("b"), it.getLong("so"), it.getLong("sb")) }
                }
            }
            val months = o.getJSONObject("months").let { m -> m.keys().asSequence().associate { it.toInt() to m.getInt(it) } }
            StorageInventory(
                region = o.getString("region"), bucket = o.getString("bucket"), prefix = o.getString("prefix"),
                takenAtMs = o.getLong("takenAtMs"), listRequests = o.getInt("listRequests"), classes = classes,
                photoObjects = o.getLong("photoObjects"), unrecognizedObjects = o.getLong("unrecognizedObjects"),
                unrecognizedBytes = o.getLong("unrecognizedBytes"), modifiedByMonth = months, complete = o.getBoolean("complete"),
            )
        }
    } catch (e: Exception) {
        null
    }
}
