package com.s3vault.app.domain

import java.text.Normalizer

/**
 * Single place that builds and validates S3 object keys. Every remote operation takes its keys from here,
 * so the configured prefix is applied consistently:
 *
 *   {prefix}photos/{assetId}/{versionId}/{safeName}
 *
 * - assetId: random UUID (32 hex chars) assigned the first time a local file is queued.
 * - versionId: SHA-256 of the content (64 hex chars). A modified file gets a new versionId, hence a new
 *   object; the old object is never overwritten or deleted.
 * - safeName: sanitized original file name, so the bucket is browsable and LIST alone yields names.
 */
class RemoteKeys(rawPrefix: String) {
    val prefix: String = normalizePrefix(rawPrefix)
    val photosPrefix: String = "${prefix}photos/"
    val connectionCheckKey: String = "${prefix}.s3vault/connection-check.txt"

    fun photoKey(assetId: String, versionId: String, displayName: String): String {
        require(ASSET_ID.matches(assetId)) { "Invalid assetId" }
        require(VERSION_ID.matches(versionId)) { "Invalid versionId" }
        return "$photosPrefix$assetId/$versionId/${sanitizeFileName(displayName)}"
    }

    fun isWithinPrefix(key: String): Boolean = key.startsWith(prefix) && !key.contains("..")

    fun isPhotoKey(key: String): Boolean = key.startsWith(photosPrefix) && parse(key) != null

    fun parse(key: String): ParsedKey? {
        if (!key.startsWith(photosPrefix)) return null
        val parts = key.removePrefix(photosPrefix).split('/', limit = 3)
        if (parts.size != 3) return null
        val (asset, version, name) = parts
        if (!ASSET_ID.matches(asset) || !VERSION_ID.matches(version) || name.isEmpty()) return null
        return ParsedKey(asset, version, name)
    }

    data class ParsedKey(val assetId: String, val versionId: String, val fileName: String)

    companion object {
        private val ASSET_ID = Regex("^[0-9a-f]{32}$")
        private val VERSION_ID = Regex("^[0-9a-f]{64}$")
        const val MAX_PREFIX_LENGTH = 200

        /** "", "/", "  " -> ""; "/a//b" -> "a/b/". Does not validate; see [ConfigValidator]. */
        fun normalizePrefix(raw: String): String {
            val segments = raw.trim().split('/').map { it.trim() }.filter { it.isNotEmpty() }
            return if (segments.isEmpty()) "" else segments.joinToString("/", postfix = "/")
        }

        fun sanitizeFileName(name: String): String {
            val base = name.substringAfterLast('/').substringAfterLast('\\')
            val ascii = Normalizer.normalize(base, Normalizer.Form.NFKD)
                .replace(Regex("\\p{M}+"), "")
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .trim('.', '_')
                .take(120)
            return ascii.ifEmpty { "file" }
        }
    }
}
