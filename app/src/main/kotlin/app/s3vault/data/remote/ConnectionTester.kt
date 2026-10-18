package app.s3vault.data.remote

import app.s3vault.auth.AwsCredentialsProvider
import app.s3vault.auth.CredentialStatus
import app.s3vault.domain.remote.RemoteException
import app.s3vault.domain.remote.RemoteFailure
import app.s3vault.domain.remote.RemoteKeys
import app.s3vault.sync.Hashing
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayInputStream
import java.io.InputStream

sealed interface Check {
    data object NotRun : Check
    data object Ok : Check
    data class Failed(val failure: RemoteFailure) : Check
}

data class ConnectionReport(
    val credentials: CredentialStatus,
    /** `s3:ListBucket` limitado al prefijo configurado. */
    val list: Check,
    /** `s3:PutObject` sobre la sonda `.s3vault/connection-check.txt`. */
    val put: Check = Check.NotRun,
    /** `s3:GetObject` (HEAD) sobre la misma sonda. */
    val get: Check = Check.NotRun,
) {
    val connected: Boolean get() = list is Check.Ok
    val allPermissionsOk: Boolean get() = list is Check.Ok && put is Check.Ok && get is Check.Ok
}

/**
 * Comprobaciones iniciadas por el usuario (nunca en segundo plano ni periódicas).
 *  * [testConnection]: una sola solicitud LIST de 1 clave sobre el prefijo configurado.
 *  * [testPermissions]: además escribe y lee una sonda fija de pocos bytes, que se sobrescribe
 *    en cada prueba. La app no tiene permiso de borrado, así que el objeto permanece (≈ 30 B).
 */
class ConnectionTester(
    private val store: ObjectStore,
    private val keys: RemoteKeys,
    private val credentials: AwsCredentialsProvider,
) {
    suspend fun testConnection(): ConnectionReport {
        val status = credentials.status()
        return ConnectionReport(status, list = attempt { store.list(keys.photosRoot, null, 1) })
    }

    suspend fun testPermissions(): ConnectionReport {
        val status = credentials.status()
        val list = attempt { store.list(keys.photosRoot, null, 1) }
        val body = PROBE_BODY
        val put = attempt {
            val hash = Hashing.sha256(ByteArrayInputStream(body))
            store.put(
                key = keys.probeKey(),
                source = object : UploadSource {
                    override val length = body.size.toLong()
                    override fun open(): InputStream = ByteArrayInputStream(body)
                },
                contentType = "text/plain",
                metadata = mapOf("app" to "s3vault/1"),
                sha256Base64 = hash.sha256Base64,
            )
        }
        val get = if (put is Check.Ok) attempt { store.head(keys.probeKey()) ?: error("sonda no encontrada") } else Check.NotRun
        return ConnectionReport(status, list, put, get)
    }

    private suspend fun attempt(block: suspend () -> Unit): Check = try {
        block(); Check.Ok
    } catch (e: CancellationException) {
        throw e
    } catch (e: RemoteException) {
        Check.Failed(e.failure)
    } catch (e: IllegalStateException) {
        Check.Failed(RemoteFailure(app.s3vault.domain.remote.FailureType.UNKNOWN, e.message))
    }

    private companion object {
        val PROBE_BODY = "S3Vault connection check\n".toByteArray()
    }
}
