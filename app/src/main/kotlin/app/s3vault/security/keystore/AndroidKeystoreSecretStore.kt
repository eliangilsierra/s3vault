package app.s3vault.security.keystore

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import app.s3vault.logging.SecureLog
import app.s3vault.security.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Guarda secretos cifrados con AES-256-GCM. La clave vive en Android Keystore (respaldada por
 * hardware cuando el dispositivo lo permite, con StrongBox si está disponible) y no es exportable;
 * los datos cifrados van a `noBackupFilesDir`, fuera de cualquier copia de seguridad.
 *
 * Límites honestos: el Keystore protege la clave de extracción simple del almacenamiento, pero no
 * impide que un dispositivo comprometido (root, malware con acceso a la app en ejecución) use la
 * clave para descifrar. Por eso se recomiendan credenciales temporales.
 */
class AndroidKeystoreSecretStore(context: Context) : SecretStore {
    private val dir = File(context.applicationContext.noBackupFilesDir, "secrets").apply { mkdirs() }
    private val mutex = Mutex()

    override suspend fun read(name: String): ByteArray? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = fileFor(name)
            if (!file.exists()) return@withLock null
            try {
                val blob = file.readBytes()
                if (blob.size < 1 + IV_BYTES + TAG_BYTES || blob[0] != VERSION) throw GeneralSecurityException("formato")
                val iv = blob.copyOfRange(1, 1 + IV_BYTES)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BYTES * 8, iv))
                cipher.updateAAD(name.toByteArray())
                cipher.doFinal(blob, 1 + IV_BYTES, blob.size - 1 - IV_BYTES)
            } catch (e: GeneralSecurityException) {
                // Clave invalidada o dato corrupto: se descarta, el usuario deberá volver a autenticarse.
                SecureLog.w(TAG, "No se pudo descifrar un secreto; se elimina", e)
                file.delete()
                null
            }
        }
    }

    override suspend fun write(name: String, value: ByteArray) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(name.toByteArray())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(value)
            val blob = ByteArray(1 + iv.size + encrypted.size)
            blob[0] = VERSION
            iv.copyInto(blob, 1)
            encrypted.copyInto(blob, 1 + iv.size)
            val target = fileFor(name)
            val tmp = File(dir, target.name + ".tmp")
            tmp.writeBytes(blob)
            if (!tmp.renameTo(target)) { // escritura atómica; evita dejar un fichero a medias
                tmp.delete()
                throw java.io.IOException("No se pudo guardar el secreto")
            }
        }
    }

    override suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        mutex.withLock { fileFor(name).delete(); Unit }
    }

    /** Borra los ficheros y también la clave del Keystore: lo cifrado queda irrecuperable. */
    override suspend fun deleteAll() = withContext(Dispatchers.IO) {
        mutex.withLock {
            dir.listFiles()?.forEach { it.delete() }
            runCatching { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS) }
            Unit
        }
    }

    private fun fileFor(name: String) = File(dir, name.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' } + ".bin")

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return try {
            generate(strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply { init(spec) }.generateKey()
    }

    private companion object {
        const val TAG = "Keystore"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "s3vault_secrets_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
    }
}
