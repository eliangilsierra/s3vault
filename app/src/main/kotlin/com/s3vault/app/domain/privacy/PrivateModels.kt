package com.s3vault.app.domain.privacy

import org.json.JSONObject

/** What the vault knows about a protected picture. It only ever exists sealed in the database; it is readable while the vault is unlocked. */
data class PrivateMeta(
    val name: String,
    val mimeType: String,
    /** Bytes of the original picture (not of the sealed file). */
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val dateTakenMs: Long,
    /** SHA-256 of the original, as it was read when the picture entered the vault. */
    val sha256: String,
    /** The folder it came from, used to put it back where it was. */
    val folder: String,
) {
    fun encode(): ByteArray = JSONObject().apply {
        put("v", 1); put("name", name); put("mime", mimeType); put("size", sizeBytes); put("w", width); put("h", height)
        put("taken", dateTakenMs); put("sha", sha256); put("folder", folder)
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun decode(bytes: ByteArray): PrivateMeta {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            return PrivateMeta(
                o.getString("name"), o.getString("mime"), o.getLong("size"), o.optInt("w"), o.optInt("h"), o.optLong("taken"),
                o.optString("sha"), o.optString("folder"),
            )
        }
    }
}

data class PrivateAlbum(val id: Long, val name: String, val items: Int, val sealedBytes: Long, val createdAtMs: Long)

data class PrivateItem(val id: Long, val albumId: Long, val fileName: String, val sealedBytes: Long, val addedAtMs: Long, val meta: PrivateMeta)

/** How the vault stands. Only [Ready] lets anything be read or written. */
enum class VaultAvailability {
    /** The device has no PIN, pattern, password or biometrics: the vault cannot be protected, so it is not offered. */
    NEEDS_SCREEN_LOCK,
    /** No vault was created yet. */
    NOT_CREATED,
    /** A vault exists and can be unlocked. */
    READY,
    /** The Keystore key is gone (screen lock removed, data restored on another device, app data cleared in part): the sealed files cannot be read any more. */
    KEY_LOST,
}

class VaultLockedException : IllegalStateException("El álbum privado está bloqueado")
