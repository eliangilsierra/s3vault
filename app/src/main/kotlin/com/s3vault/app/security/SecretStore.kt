package com.s3vault.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted storage for small secrets. Never backed by Room or plain preferences. */
interface SecretStore {
    fun put(name: String, value: ByteArray)
    fun get(name: String): ByteArray?
    fun delete(name: String)
}

/**
 * Secrets are encrypted with AES-256-GCM using a non-exportable key kept in the Android Keystore
 * (hardware-backed where the device supports it) and written to the app's no-backup directory.
 *
 * This is the platform's standard authenticated encryption; there is no custom cryptography here.
 * It protects data at rest from other apps and from offline copies of the app data. It does NOT protect
 * against a rooted/compromised device or malware running as this app, which can ask the Keystore to decrypt.
 */
class KeystoreSecretStore(
    context: Context,
    private val keyAlias: String = "s3vault_secret_key_v1",
) : SecretStore {

    private val dir = File(context.noBackupFilesDir, "secrets").apply { mkdirs() }

    @Synchronized
    override fun put(name: String, value: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(value)
        val out = ByteArray(1 + 1 + iv.size + encrypted.size)
        out[0] = FORMAT_VERSION
        out[1] = iv.size.toByte()
        System.arraycopy(iv, 0, out, 2, iv.size)
        System.arraycopy(encrypted, 0, out, 2 + iv.size, encrypted.size)
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(out)
        if (!tmp.renameTo(file(name))) {
            file(name).delete()
            check(tmp.renameTo(file(name))) { "Could not persist secret" }
        }
    }

    @Synchronized
    override fun get(name: String): ByteArray? {
        val f = file(name)
        if (!f.exists()) return null
        return try {
            val data = f.readBytes()
            if (data.size < 3 || data[0] != FORMAT_VERSION) return null
            val ivLen = data[1].toInt()
            val iv = data.copyOfRange(2, 2 + ivLen)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            cipher.doFinal(data, 2 + ivLen, data.size - 2 - ivLen)
        } catch (e: Exception) {
            // Key invalidated or file corrupted: treat as absent. Never log content.
            null
        }
    }

    @Synchronized
    override fun delete(name: String) {
        file(name).delete()
        File(dir, "$name.tmp").delete()
    }

    private fun file(name: String): File {
        require(name.matches(Regex("[a-z0-9_]{1,64}"))) { "Invalid secret name" }
        return File(dir, "$name.bin")
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION: Byte = 1
    }
}

/** In-memory store for tests. */
class InMemorySecretStore : SecretStore {
    private val map = mutableMapOf<String, ByteArray>()
    override fun put(name: String, value: ByteArray) { map[name] = value.copyOf() }
    override fun get(name: String): ByteArray? = map[name]?.copyOf()
    override fun delete(name: String) { map.remove(name) }
}
