package com.s3vault.app.security

import android.util.Log

/**
 * Removes anything that looks like an AWS secret from text before it can reach logs, error fields in Room
 * or the UI. Defense in depth: the app never intentionally logs credentials, and this catches accidents
 * (e.g. an SDK exception message that echoes a header).
 */
object Redactor {
    private val accessKey = Regex("\\b(AKIA|ASIA|AIDA|AROA|AGPA|ANPA)[A-Z0-9]{12,}\\b")
    private val secretAssignment = Regex(
        "(?i)(secret[_ -]?(access[_ -]?)?key|session[_ -]?token|x-amz-security-token|authorization|signature|aws_secret_access_key|aws_session_token)(\"?\\s*[:=]\\s*\"?)([^\\s\",;&]+)",
    )
    private val credentialScope = Regex("(?i)(Credential=)([^/\\s,]+)")
    private val longBase64 = Regex("\\b[A-Za-z0-9+/=_-]{40,}\\b")

    fun redact(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        var out = text
        out = secretAssignment.replace(out) { "${it.groupValues[1]}${it.groupValues[3]}***" }
        out = credentialScope.replace(out) { "${it.groupValues[1]}***" }
        out = accessKey.replace(out, "AKIA****")
        out = longBase64.replace(out, "***")
        return out
    }

    /** Short, redacted description of a throwable for storing as `lastError`. */
    fun describe(t: Throwable): String = redact("${t::class.java.simpleName}: ${t.message.orEmpty()}").take(300)
}

/** The only logging entry point of the app. Always redacts. */
object SafeLog {
    fun d(tag: String, message: String) { Log.d(tag, Redactor.redact(message)) }
    fun w(tag: String, message: String, t: Throwable? = null) {
        Log.w(tag, Redactor.redact(message + (t?.let { " — " + Redactor.describe(it) } ?: "")))
    }
    fun e(tag: String, message: String, t: Throwable? = null) {
        Log.e(tag, Redactor.redact(message + (t?.let { " — " + Redactor.describe(it) } ?: "")))
    }
}
