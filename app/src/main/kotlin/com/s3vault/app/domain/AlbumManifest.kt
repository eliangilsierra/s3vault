package com.s3vault.app.domain

import org.json.JSONException
import org.json.JSONObject

/**
 * Album membership stored in S3 at `{prefix}.s3vault/albums.json`, written by tools/s3vault_import.py and only
 * ever read by the app (so there is a single writer and nothing to merge or race on).
 *
 *     {"version": 1, "albums": [{"name": "Amor", "items": ["<sha256 hex>", ...]}]}
 *
 * Items are the SHA-256 (the `versionId` of the object key) of each photo, so membership survives any rename
 * and is matched against local photos by content, never by file name.
 */
data class AlbumManifest(val albums: List<Entry>) {
    data class Entry(val name: String, val items: List<String>)

    class Invalid(message: String) : Exception(message)

    companion object {
        const val VERSION = 1
        const val MAX_BYTES = 8L * 1024 * 1024
        const val MAX_ALBUMS = 5_000
        const val MAX_NAME = 80
        private val SHA256 = Regex("^[0-9a-f]{64}$")

        fun parse(json: String): AlbumManifest {
            val root = try {
                JSONObject(json)
            } catch (e: JSONException) {
                throw Invalid("no es un JSON válido")
            }
            if (root.optInt("version", -1) != VERSION) throw Invalid("versión no admitida")
            val array = root.optJSONArray("albums") ?: throw Invalid("falta la lista de álbumes")
            if (array.length() > MAX_ALBUMS) throw Invalid("demasiados álbumes")
            val albums = ArrayList<Entry>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: throw Invalid("álbum $i mal formado")
                val name = obj.optString("name").trim()
                if (name.isEmpty() || name.length > MAX_NAME || name.any { it.isISOControl() }) throw Invalid("nombre de álbum no válido")
                val raw = obj.optJSONArray("items") ?: throw Invalid("el álbum «$name» no tiene lista de elementos")
                val items = LinkedHashSet<String>(raw.length())
                for (j in 0 until raw.length()) {
                    val sha = raw.optString(j)
                    if (!SHA256.matches(sha)) throw Invalid("elemento no válido en «$name»")
                    items += sha
                }
                albums += Entry(name, items.toList())
            }
            return AlbumManifest(albums)
        }
    }
}
