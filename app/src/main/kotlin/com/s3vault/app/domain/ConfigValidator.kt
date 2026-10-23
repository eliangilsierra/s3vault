package com.s3vault.app.domain

/** Pure validation of the S3 settings form. The S3 layer still enforces the prefix independently. */
object ConfigValidator {

    val knownRegions: List<String> = listOf(
        "us-east-1", "us-east-2", "us-west-1", "us-west-2",
        "ca-central-1", "ca-west-1", "sa-east-1", "mx-central-1",
        "eu-west-1", "eu-west-2", "eu-west-3", "eu-central-1", "eu-central-2",
        "eu-north-1", "eu-south-1", "eu-south-2",
        "ap-east-1", "ap-south-1", "ap-south-2", "ap-southeast-1", "ap-southeast-2",
        "ap-southeast-3", "ap-southeast-4", "ap-southeast-5", "ap-northeast-1",
        "ap-northeast-2", "ap-northeast-3",
        "me-south-1", "me-central-1", "il-central-1", "af-south-1",
    )

    private val regionRegex = Regex("^[a-z]{2}(-[a-z]+)+-\\d$")
    private val bucketRegex = Regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$")
    private val ipv4Regex = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val accessKeyRegex = Regex("^[A-Z0-9]{16,128}$")

    enum class Field { REGION, BUCKET, PREFIX, ACCESS_KEY_ID, SECRET_KEY, SESSION_TOKEN, EXPIRATION }

    data class Issue(val field: Field, val message: String)

    fun validateRegion(region: String): Issue? =
        if (!regionRegex.matches(region.trim())) Issue(Field.REGION, "Región no válida (ejemplo: eu-west-1).") else null

    fun validateBucket(bucket: String): Issue? {
        val b = bucket.trim()
        return when {
            b.length !in 3..63 -> Issue(Field.BUCKET, "El nombre del bucket debe tener entre 3 y 63 caracteres.")
            !bucketRegex.matches(b) -> Issue(Field.BUCKET, "Solo minúsculas, números, puntos y guiones; debe empezar y terminar con letra o número.")
            ".." in b || ".-" in b || "-." in b -> Issue(Field.BUCKET, "El nombre del bucket contiene una secuencia no permitida.")
            ipv4Regex.matches(b) -> Issue(Field.BUCKET, "El nombre del bucket no puede tener formato de dirección IP.")
            b.startsWith("xn--") || b.startsWith("sthree-") || b.endsWith("-s3alias") || b.endsWith("--ol-s3") ->
                Issue(Field.BUCKET, "El nombre del bucket usa un prefijo o sufijo reservado por AWS.")
            else -> null
        }
    }

    fun validatePrefix(rawPrefix: String): Issue? {
        val p = rawPrefix.trim()
        if (p.isEmpty()) return null
        return when {
            p.length > RemoteKeys.MAX_PREFIX_LENGTH -> Issue(Field.PREFIX, "El prefijo es demasiado largo.")
            p.any { it.isISOControl() } -> Issue(Field.PREFIX, "El prefijo contiene caracteres de control.")
            p.split('/').any { it.trim() == "." || it.trim() == ".." } -> Issue(Field.PREFIX, "El prefijo no puede contener '.' ni '..' como segmento.")
            p.contains('\\') || p.contains('*') || p.contains('?') -> Issue(Field.PREFIX, "El prefijo contiene caracteres no permitidos (\\ * ?).")
            else -> null
        }
    }

    fun validateConfig(region: String, bucket: String, prefix: String): List<Issue> =
        listOfNotNull(validateRegion(region), validateBucket(bucket), validatePrefix(prefix))

    fun validateAccessKeyId(value: String): Issue? =
        if (!accessKeyRegex.matches(value.trim())) Issue(Field.ACCESS_KEY_ID, "El Access key ID debe tener 16–128 caracteres en mayúsculas/dígitos (por ejemplo AKIA… o ASIA…).") else null

    fun validateSecret(value: String): Issue? =
        if (value.trim().length < 20 || value.any { it.isWhitespace() }) Issue(Field.SECRET_KEY, "El Secret access key no parece válido.") else null

    fun validateSessionToken(value: String): Issue? =
        if (value.trim().length < 16 || value.any { it.isWhitespace() }) Issue(Field.SESSION_TOKEN, "El session token es obligatorio en credenciales temporales.") else null
}
