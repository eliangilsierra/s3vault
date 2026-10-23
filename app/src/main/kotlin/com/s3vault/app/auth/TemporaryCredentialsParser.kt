package com.s3vault.app.auth

import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** Credentials as pasted by the user, not yet validated or stored. */
data class ParsedCredentials(
    val accessKeyId: String,
    val secretAccessKey: String,
    val sessionToken: String?,
    val expiration: Instant?,
)

/**
 * Understands the output formats of the AWS CLI commands a user realistically runs to obtain temporary
 * credentials from an identity they already have configured:
 *
 *  - `aws sts get-session-token` / `assume-role` / `assume-role-with-web-identity` (JSON, "Credentials" object)
 *  - `aws configure export-credentials --format process` (flat JSON with Version/AccessKeyId/…)
 *  - `aws configure export-credentials --format env` or any shell snippet with AWS_ACCESS_KEY_ID=… lines
 */
object TemporaryCredentialsParser {

    fun parse(input: String): ParsedCredentials? {
        val text = input.trim()
        if (text.isEmpty()) return null
        return if (text.startsWith("{")) parseJson(text) else parseEnv(text)
    }

    private fun parseJson(text: String): ParsedCredentials? = try {
        val root = JSONObject(text)
        val obj = root.optJSONObject("Credentials") ?: root
        val access = obj.optString("AccessKeyId").ifBlank { return null }
        val secret = obj.optString("SecretAccessKey").ifBlank { return null }
        ParsedCredentials(
            accessKeyId = access.trim(),
            secretAccessKey = secret.trim(),
            sessionToken = obj.optString("SessionToken").trim().ifBlank { null },
            expiration = parseInstant(obj.optString("Expiration")),
        )
    } catch (_: JSONException) {
        null
    }

    private val envLine = Regex(
        "(?im)^\\s*(?:export\\s+|set\\s+|\\${'$'}env:)?(AWS_ACCESS_KEY_ID|AWS_SECRET_ACCESS_KEY|AWS_SESSION_TOKEN|AWS_CREDENTIAL_EXPIRATION)\\s*=\\s*[\"']?([^\"'\\s]+)[\"']?\\s*;?\\s*$",
    )

    private fun parseEnv(text: String): ParsedCredentials? {
        val values = envLine.findAll(text).associate { it.groupValues[1].uppercase() to it.groupValues[2] }
        val access = values["AWS_ACCESS_KEY_ID"] ?: return null
        val secret = values["AWS_SECRET_ACCESS_KEY"] ?: return null
        return ParsedCredentials(access, secret, values["AWS_SESSION_TOKEN"], parseInstant(values["AWS_CREDENTIAL_EXPIRATION"]))
    }

    /** Accepts ISO-8601 with 'Z' or an offset (the CLI emits both), or epoch seconds. */
    fun parseInstant(raw: String?): Instant? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        s.toLongOrNull()?.let { return Instant.ofEpochSecond(it) }
        return try {
            Instant.parse(s)
        } catch (_: DateTimeParseException) {
            try {
                OffsetDateTime.parse(s).toInstant()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}
