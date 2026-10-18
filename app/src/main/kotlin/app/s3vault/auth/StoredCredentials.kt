package app.s3vault.auth

import app.s3vault.security.SecretStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant

/** Material de autenticación persistido (siempre cifrado por el [SecretStore]). */
sealed interface StoredCredentials {
    val method: AuthMethod

    data class StaticKeys(val accessKeyId: String, val secretAccessKey: String) : StoredCredentials {
        override val method get() = AuthMethod.STATIC_KEYS
        override fun toString() = "StaticKeys(accessKeyId=${AwsCredentials.maskKeyId(accessKeyId)}, secret=***)"
    }

    data class Temporary(val credentials: AwsCredentials) : StoredCredentials {
        override val method get() = AuthMethod.TEMPORARY_MANUAL
        override fun toString() = "Temporary($credentials)"
    }

    /**
     * Sesión de IAM Identity Center. Guarda lo necesario para renovar sin volver a abrir el
     * navegador mientras el proveedor lo permita.
     */
    data class IdentityCenter(
        val startUrl: String,
        val ssoRegion: String,
        val accountId: String,
        val roleName: String,
        val clientId: String,
        val clientSecret: String,
        val clientExpiresAt: Instant,
        val accessToken: String?,
        val accessTokenExpiresAt: Instant?,
        val refreshToken: String?,
        val roleCredentials: AwsCredentials?,
    ) : StoredCredentials {
        override val method get() = AuthMethod.IDENTITY_CENTER
        override fun toString() = "IdentityCenter(account=$accountId, role=$roleName, tokens=***)"
    }
}

/** Serialización binaria sencilla (sin dependencias) para el contenido que cifra el Keystore. */
internal object CredentialCodec {
    private const val VERSION = 1

    fun encode(c: StoredCredentials): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(VERSION)
            out.writeUTF(c.method.name)
            when (c) {
                is StoredCredentials.StaticKeys -> {
                    out.writeUTF(c.accessKeyId)
                    out.writeUTF(c.secretAccessKey)
                }

                is StoredCredentials.Temporary -> writeCreds(out, c.credentials)
                is StoredCredentials.IdentityCenter -> {
                    out.writeUTF(c.startUrl); out.writeUTF(c.ssoRegion)
                    out.writeUTF(c.accountId); out.writeUTF(c.roleName)
                    out.writeUTF(c.clientId); out.writeUTF(c.clientSecret)
                    out.writeLong(c.clientExpiresAt.toEpochMilli())
                    writeOpt(out, c.accessToken)
                    out.writeLong(c.accessTokenExpiresAt?.toEpochMilli() ?: -1)
                    writeOpt(out, c.refreshToken)
                    out.writeBoolean(c.roleCredentials != null)
                    c.roleCredentials?.let { writeCreds(out, it) }
                }
            }
        }
        return bytes.toByteArray()
    }

    fun decode(data: ByteArray): StoredCredentials? = runCatching {
        DataInputStream(ByteArrayInputStream(data)).use { input ->
            if (input.readInt() != VERSION) return@runCatching null
            when (AuthMethod.valueOf(input.readUTF())) {
                AuthMethod.STATIC_KEYS -> StoredCredentials.StaticKeys(input.readUTF(), input.readUTF())
                AuthMethod.TEMPORARY_MANUAL -> StoredCredentials.Temporary(readCreds(input))
                AuthMethod.IDENTITY_CENTER -> StoredCredentials.IdentityCenter(
                    startUrl = input.readUTF(), ssoRegion = input.readUTF(),
                    accountId = input.readUTF(), roleName = input.readUTF(),
                    clientId = input.readUTF(), clientSecret = input.readUTF(),
                    clientExpiresAt = Instant.ofEpochMilli(input.readLong()),
                    accessToken = readOpt(input),
                    accessTokenExpiresAt = input.readLong().takeIf { it >= 0 }?.let(Instant::ofEpochMilli),
                    refreshToken = readOpt(input),
                    roleCredentials = if (input.readBoolean()) readCreds(input) else null,
                )
            }
        }
    }.getOrNull()

    private fun writeOpt(out: DataOutputStream, v: String?) {
        out.writeBoolean(v != null)
        if (v != null) out.writeUTF(v)
    }

    private fun readOpt(input: DataInputStream): String? = if (input.readBoolean()) input.readUTF() else null

    private fun writeCreds(out: DataOutputStream, c: AwsCredentials) {
        out.writeUTF(c.accessKeyId)
        out.writeUTF(c.secretAccessKey)
        writeOpt(out, c.sessionToken)
        out.writeLong(c.expiration?.toEpochMilli() ?: -1)
    }

    private fun readCreds(input: DataInputStream): AwsCredentials = AwsCredentials(
        accessKeyId = input.readUTF(),
        secretAccessKey = input.readUTF(),
        sessionToken = readOpt(input),
        expiration = input.readLong().takeIf { it >= 0 }?.let(Instant::ofEpochMilli),
    )
}

/** Guarda y lee el único registro de credenciales activo. Sustituir uno borra el anterior. */
class CredentialVault(private val store: SecretStore) {
    suspend fun load(): StoredCredentials? = store.read(NAME)?.let(CredentialCodec::decode)

    suspend fun save(credentials: StoredCredentials) {
        store.write(NAME, CredentialCodec.encode(credentials))
    }

    /** Elimina todo el material de autenticación del dispositivo. No toca objetos remotos. */
    suspend fun clear() = store.deleteAll()

    private companion object {
        const val NAME = "credentials.v1"
    }
}
