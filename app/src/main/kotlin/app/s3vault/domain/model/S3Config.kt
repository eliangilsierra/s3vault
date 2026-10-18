package app.s3vault.domain.model

/** Cifrado en servidor solicitado en cada PUT. S3 ya cifra con SSE-S3 por defecto. */
enum class ServerSideEncryption(val headerValue: String?) {
    /** No se envía cabecera: se aplica el cifrado por defecto del bucket. */
    BUCKET_DEFAULT(null),

    /** SSE-S3 explícito (AES256). */
    AES256("AES256"),

    /** SSE-KMS con la clave administrada por AWS (`aws/s3`). Exige permisos KMS adicionales. */
    AWS_KMS("aws:kms"),
}

/**
 * Configuración no secreta de la conexión con Amazon S3.
 * [prefix] está normalizado: vacío, o segmentos separados por `/` y terminado en `/`.
 */
data class S3Config(
    val region: String,
    val bucket: String,
    val prefix: String,
    val sse: ServerSideEncryption = ServerSideEncryption.BUCKET_DEFAULT,
) {
    companion object {
        /** Construye la configuración validando y normalizando la entrada del usuario. */
        fun create(
            region: String,
            bucket: String,
            prefix: String,
            sse: ServerSideEncryption = ServerSideEncryption.BUCKET_DEFAULT,
        ): ConfigValidation {
            val errors = mutableListOf<ConfigError>()
            val r = region.trim()
            val b = bucket.trim()
            if (!S3ConfigRules.isValidRegion(r)) errors += ConfigError.INVALID_REGION
            if (!S3ConfigRules.isValidBucketName(b)) errors += ConfigError.INVALID_BUCKET
            val p = S3ConfigRules.normalizePrefix(prefix)
            if (p == null) errors += ConfigError.INVALID_PREFIX
            return if (errors.isEmpty()) ConfigValidation.Valid(S3Config(r, b, p!!, sse))
            else ConfigValidation.Invalid(errors)
        }
    }
}

enum class ConfigError { INVALID_REGION, INVALID_BUCKET, INVALID_PREFIX }

sealed interface ConfigValidation {
    data class Valid(val config: S3Config) : ConfigValidation
    data class Invalid(val errors: List<ConfigError>) : ConfigValidation
}

object S3ConfigRules {
    private val REGION = Regex("^[a-z]{2}(-[a-z]+)+-[0-9]{1,2}$")
    private val BUCKET = Regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$")
    private val IPV4 = Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$")
    private val SEGMENT = Regex("^[A-Za-z0-9._-]+$")
    private val RESERVED_BUCKET_PREFIXES = listOf("xn--", "sthree-", "amzn-s3-demo-")
    private val RESERVED_BUCKET_SUFFIXES = listOf("-s3alias", "--ol-s3", ".mrap", "--x-s3", "--table-s3")

    const val MAX_PREFIX_LENGTH = 200

    fun isValidRegion(region: String): Boolean = REGION.matches(region)

    /** Reglas de nomenclatura de buckets de uso general de S3. */
    fun isValidBucketName(name: String): Boolean {
        if (name.length !in 3..63) return false
        if (!BUCKET.matches(name)) return false
        if (name.contains("..") || name.contains(".-") || name.contains("-.")) return false
        if (IPV4.matches(name)) return false
        if (RESERVED_BUCKET_PREFIXES.any { name.startsWith(it) }) return false
        if (RESERVED_BUCKET_SUFFIXES.any { name.endsWith(it) }) return false
        return true
    }

    /**
     * Normaliza el prefijo: quita `/` iniciales y finales y garantiza una sola `/` final.
     * Devuelve `""` para "sin prefijo" y `null` si es inválido. Solo se aceptan segmentos
     * `[A-Za-z0-9._-]`: nada de `*`, `?`, espacios, `..` ni segmentos vacíos, porque el prefijo
     * se interpola en políticas IAM donde `*` y `?` serían comodines.
     */
    fun normalizePrefix(raw: String): String? {
        val trimmed = raw.trim().trim('/')
        if (trimmed.isEmpty()) return ""
        if (trimmed.length > MAX_PREFIX_LENGTH) return null
        val segments = trimmed.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." || !SEGMENT.matches(it) }) return null
        return segments.joinToString("/") + "/"
    }
}
