package app.s3vault.domain.remote

import app.s3vault.domain.model.S3ConfigRules
import java.net.URLDecoder
import java.net.URLEncoder

/** Componentes de una clave `{prefix}photos/{assetId}/{versionId}/{name}`. */
data class ParsedKey(val assetId: String, val versionId: String, val name: String)

/**
 * Esquema de claves y barrera de aislamiento por prefijo.
 *
 * La interfaz valida el prefijo al guardarlo, pero la seguridad no depende de eso: toda operación
 * remota pasa por [requireInScope], y la política IAM del usuario es el límite real.
 */
class RemoteKeys(val prefix: String) {
    init {
        require(prefix.isEmpty() || (S3ConfigRules.normalizePrefix(prefix) == prefix)) {
            "Prefijo no normalizado"
        }
    }

    /** Raíz de las fotografías: se lista exactamente este prefijo. */
    val photosRoot: String = prefix + PHOTOS_DIR

    /** Raíz de objetos auxiliares de la app (solo la sonda de permisos). */
    val metaRoot: String = prefix + META_DIR

    fun objectKey(assetId: String, versionId: String, displayName: String): String {
        require(ID.matches(assetId)) { "assetId inválido" }
        require(ID.matches(versionId)) { "versionId inválido" }
        return "$photosRoot$assetId/$versionId/${safeName(displayName)}"
    }

    fun probeKey(): String = metaRoot + "connection-check.txt"

    fun isInScope(key: String): Boolean =
        key.startsWith(prefix) &&
            key.length <= MAX_KEY_LENGTH &&
            !key.contains("//") &&
            key.split('/').none { it == ".." || it == "." } &&
            key.none { it.isISOControl() }

    fun requireInScope(key: String) {
        require(isInScope(key)) { "La clave está fuera del prefijo configurado" }
    }

    /** Devuelve null si la clave no es un objeto de fotografía de esta app dentro del prefijo. */
    fun parse(key: String): ParsedKey? {
        if (!isInScope(key) || !key.startsWith(photosRoot)) return null
        val parts = key.removePrefix(photosRoot).split('/')
        if (parts.size != 3) return null
        val (asset, version, name) = parts
        if (!ID.matches(asset) || !ID.matches(version) || name.isEmpty()) return null
        return ParsedKey(asset, version, name)
    }

    companion object {
        const val PHOTOS_DIR = "photos/"
        const val META_DIR = ".s3vault/"
        const val MAX_KEY_LENGTH = 1024
        private val ID = Regex("^[0-9a-f]{32}$")

        /** Nombre seguro para usar como último segmento de la clave (conserva la extensión). */
        fun safeName(displayName: String): String {
            val base = displayName.substringAfterLast('/').substringAfterLast('\\')
            val dot = base.lastIndexOf('.')
            val stem = if (dot > 0) base.substring(0, dot) else base
            val ext = if (dot > 0) base.substring(dot + 1) else ""
            fun clean(s: String) = s.map { if (it.isLetterOrDigit() && it.code < 128 || it in "-_") it else '_' }
                .joinToString("")
            val cleanStem = clean(stem).trim('_').ifEmpty { "file" }.take(80)
            val cleanExt = clean(ext).take(10)
            return if (cleanExt.isEmpty()) cleanStem else "$cleanStem.$cleanExt"
        }

        /** Los metadatos de usuario de S3 deben ser ASCII; el nombre original se guarda codificado. */
        fun encodeMetadataValue(value: String): String = URLEncoder.encode(value, "UTF-8")
        fun decodeMetadataValue(value: String): String =
            runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }
}
