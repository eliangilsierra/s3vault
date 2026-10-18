package app.s3vault.domain.remote

/** Metadatos de usuario que la app escribe en cada objeto (cabeceras `x-amz-meta-*`). */
data class ObjectMetadata(
    val sha256Hex: String,
    val originalName: String,
    val capturedAtMs: Long?,
    val width: Int?,
    val height: Int?,
    val assetId: String,
    val versionId: String,
) {
    fun toMap(): Map<String, String> = buildMap {
        put(SHA256, sha256Hex)
        put(NAME, RemoteKeys.encodeMetadataValue(originalName))
        put(ASSET, assetId)
        put(VERSION, versionId)
        put(APP, "s3vault/1")
        capturedAtMs?.let { put(CAPTURED, it.toString()) }
        width?.let { put(WIDTH, it.toString()) }
        height?.let { put(HEIGHT, it.toString()) }
    }

    companion object {
        const val SHA256 = "sha256"
        const val NAME = "original-name"
        const val CAPTURED = "captured-at"
        const val WIDTH = "width"
        const val HEIGHT = "height"
        const val ASSET = "asset-id"
        const val VERSION = "version-id"
        const val APP = "app"

        /** Lee metadatos de un objeto; devuelve null si no los escribió esta app (sin SHA-256). */
        fun fromMap(map: Map<String, String>): ObjectMetadata? {
            val lower = map.mapKeys { it.key.lowercase() }
            val sha = lower[SHA256]?.lowercase()?.takeIf { it.length == 64 } ?: return null
            return ObjectMetadata(
                sha256Hex = sha,
                originalName = lower[NAME]?.let(RemoteKeys::decodeMetadataValue).orEmpty(),
                capturedAtMs = lower[CAPTURED]?.toLongOrNull(),
                width = lower[WIDTH]?.toIntOrNull(),
                height = lower[HEIGHT]?.toIntOrNull(),
                assetId = lower[ASSET].orEmpty(),
                versionId = lower[VERSION].orEmpty(),
            )
        }
    }
}
