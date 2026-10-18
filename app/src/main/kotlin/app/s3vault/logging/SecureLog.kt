package app.s3vault.logging

/** Destino de los logs. En Android escribe en Logcat; en pruebas, en memoria. */
fun interface LogSink {
    fun log(level: Level, tag: String, message: String)
    enum class Level { DEBUG, INFO, WARN, ERROR }
}

/**
 * Elimina de cualquier texto lo que parezca un secreto de AWS antes de que llegue a un log,
 * un mensaje de error o una captura de diagnóstico. Es una red de seguridad: el código no debe
 * pasar secretos aquí en primer lugar.
 */
object Redactor {
    private val ACCESS_KEY_ID = Regex("\\b(?:AKIA|ASIA|AIDA|AROA|ANPA)[A-Z0-9]{12,}\\b")
    private val LONG_TOKEN = Regex("[A-Za-z0-9/+=_-]{40,}")
    private val HEADER = Regex("(?i)(x-amz-security-token|authorization|x-amz-credential|x-amz-signature|aws_secret_access_key|aws_session_token|secretaccesskey|sessiontoken|refreshtoken|accesstoken|clientsecret)(\"?\\s*[:=]\\s*\"?)[^\\s\",;]+")

    private val known = java.util.concurrent.CopyOnWriteArraySet<String>()

    /** Registra un secreto concreto (p. ej. el que acaba de introducir el usuario) para enmascararlo siempre. */
    fun registerSecret(secret: String) {
        if (secret.length >= 6) known += secret
    }

    fun clearKnownSecrets() = known.clear()

    fun redact(text: String): String {
        var out = text
        for (s in known) out = out.replace(s, "***")
        out = HEADER.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}***" }
        out = ACCESS_KEY_ID.replace(out) { it.value.take(4) + "…***" }
        out = LONG_TOKEN.replace(out) { "***" }
        return out
    }

    /** Mensaje de una excepción ya saneado (y su cadena de causas, truncada). */
    fun describe(t: Throwable): String =
        generateSequence(t) { it.cause }.take(4).joinToString(" <- ") {
            it.javaClass.simpleName + (it.message?.let { m -> ": ${redact(m).take(200)}" } ?: "")
        }
}

object SecureLog {
    @Volatile
    var sink: LogSink = LogSink { _, _, _ -> }

    fun d(tag: String, msg: String) = sink.log(LogSink.Level.DEBUG, tag, Redactor.redact(msg))
    fun i(tag: String, msg: String) = sink.log(LogSink.Level.INFO, tag, Redactor.redact(msg))
    fun w(tag: String, msg: String, t: Throwable? = null) =
        sink.log(LogSink.Level.WARN, tag, Redactor.redact(msg) + (t?.let { " | " + Redactor.describe(it) } ?: ""))

    fun e(tag: String, msg: String, t: Throwable? = null) =
        sink.log(LogSink.Level.ERROR, tag, Redactor.redact(msg) + (t?.let { " | " + Redactor.describe(it) } ?: ""))
}
