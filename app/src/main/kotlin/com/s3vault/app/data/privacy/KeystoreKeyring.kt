package com.s3vault.app.data.privacy

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import com.s3vault.app.domain.privacy.KeyringResult
import com.s3vault.app.domain.privacy.UnlockPrompt
import com.s3vault.app.domain.privacy.VaultCrypto
import com.s3vault.app.domain.privacy.VaultKeyring
import com.s3vault.app.domain.privacy.WrappedKey
import com.s3vault.app.security.SafeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume

/**
 * The vault key is wrapped by an AES-256-GCM key of the Android Keystore (hardware-backed where the device has it, StrongBox when
 * available) that is only usable after the user authenticates with biometrics or the screen-lock PIN, pattern or password:
 *  - Android 11 and later: every use of the key needs its own authentication, tied to the operation through a CryptoObject.
 *  - Android 10: the framework cannot tie a credential to one operation, so the key works for a few seconds after authenticating.
 *
 * The key is created without "invalidate on new fingerprint": enrolling a finger does not destroy the vault. Removing the screen
 * lock does (Android deletes keys that require it), as does a factory reset or a restore on another device: see the docs.
 */
class KeystoreKeyring(private val context: Context, private val alias: String = ALIAS) : VaultKeyring {
    private val keyguard get() = context.getSystemService(KeyguardManager::class.java)

    override fun isSecure(): Boolean = keyguard?.isDeviceSecure == true

    override suspend fun create(prompt: UnlockPrompt): KeyringResult {
        if (!isSecure()) return KeyringResult.NeedsScreenLock
        val master = VaultCrypto.newKey()
        return try {
            deleteKey()
            generateKey()
            when (val r = authorizedCipher(prompt, Cipher.ENCRYPT_MODE, null)) {
                is Authorized.Ok -> KeyringResult.Key(master.copyOf(), WrappedKey(r.cipher.doFinal(master), r.cipher.iv))
                is Authorized.Stopped -> r.result
            }
        } catch (e: GeneralSecurityException) {
            SafeLog.e("KeystoreKeyring", "Could not create the vault key", e)
            KeyringResult.Failed("No se pudo crear la clave segura de este dispositivo.")
        } finally {
            master.fill(0)
        }
    }

    override suspend fun unwrap(wrapped: WrappedKey, prompt: UnlockPrompt): KeyringResult {
        if (!keyExists()) return KeyringResult.KeyLost(lostReason())
        return try {
            when (val r = authorizedCipher(prompt, Cipher.DECRYPT_MODE, wrapped.iv)) {
                is Authorized.Ok -> KeyringResult.Key(r.cipher.doFinal(wrapped.cipherText), wrapped)
                is Authorized.Stopped -> r.result
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            KeyringResult.KeyLost(lostReason())
        } catch (e: GeneralSecurityException) {
            SafeLog.e("KeystoreKeyring", "Could not unwrap the vault key", e)
            KeyringResult.KeyLost("La clave guardada en este dispositivo ya no abre el álbum privado.")
        }
    }

    override fun deleteKey() {
        runCatching { store().deleteEntry(alias) }
    }

    private fun lostReason() =
        if (isSecure()) "La clave del álbum privado ya no existe en este dispositivo."
        else "Se quitó el bloqueo de pantalla y Android borró la clave del álbum privado."

    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun keyExists(): Boolean = runCatching { store().containsAlias(alias) }.getOrDefault(false)

    private fun generateKey() {
        fun spec(strongBox: Boolean): KeyGenParameterSpec {
            val b = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(false)
            if (Build.VERSION.SDK_INT >= 30) {
                b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            } else {
                @Suppress("DEPRECATION") b.setUserAuthenticationValidityDurationSeconds(AUTH_WINDOW_S)
            }
            if (strongBox) b.setIsStrongBoxBacked(true)
            return b.build()
        }
        try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run { init(spec(strongBox = Build.VERSION.SDK_INT >= 28)); generateKey() }
        } catch (e: Exception) {
            // No StrongBox on this device (or it refused): the normal Keystore is used.
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run { init(spec(strongBox = false)); generateKey() }
        }
    }

    private fun secretKey(): SecretKey = store().getKey(alias, null) as SecretKey

    private fun newCipher(mode: Int, iv: ByteArray?): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        if (iv == null) init(mode, secretKey()) else init(mode, secretKey(), GCMParameterSpec(128, iv))
    }

    private sealed interface Authorized {
        class Ok(val cipher: Cipher) : Authorized
        class Stopped(val result: KeyringResult) : Authorized
    }

    private sealed interface Prompt {
        class Success(val cipher: Cipher?) : Prompt
        class Stop(val result: KeyringResult) : Prompt
    }

    /** Shows the system prompt and returns a cipher the Keystore will let run. */
    private suspend fun authorizedCipher(prompt: UnlockPrompt, mode: Int, iv: ByteArray?): Authorized = withContext(Dispatchers.Main) {
        val early: Cipher? = if (Build.VERSION.SDK_INT >= 30) newCipher(mode, iv) else null
        when (val outcome = authenticate(prompt, early)) {
            is Prompt.Success -> {
                // Android 10 has no per-operation credential: the key is usable now, so the cipher is made after the prompt.
                val cipher = outcome.cipher ?: try {
                    newCipher(mode, iv)
                } catch (e: UserNotAuthenticatedException) {
                    return@withContext Authorized.Stopped(KeyringResult.Failed("La verificación caducó. Inténtalo de nuevo."))
                }
                Authorized.Ok(cipher)
            }
            is Prompt.Stop -> Authorized.Stopped(outcome.result)
        }
    }

    private suspend fun authenticate(prompt: UnlockPrompt, crypto: Cipher?): Prompt = suspendCancellableCoroutine { cont ->
        val signal = CancellationSignal()
        cont.invokeOnCancellation { signal.cancel() }
        val builder = BiometricPrompt.Builder(context).setTitle(prompt.title).setSubtitle(prompt.subtitle)
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION") builder.setDeviceCredentialAllowed(true)
        }
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (cont.isActive) cont.resume(Prompt.Success(result.cryptoObject?.cipher))
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                if (!cont.isActive) return
                cont.resume(
                    Prompt.Stop(
                        when (errorCode) {
                            ERR_USER_CANCELED, ERR_CANCELED, ERR_NEGATIVE_BUTTON -> KeyringResult.Cancelled
                            ERR_NO_DEVICE_CREDENTIAL, ERR_NO_BIOMETRICS, ERR_HW_NOT_PRESENT -> KeyringResult.NeedsScreenLock
                            ERR_LOCKOUT, ERR_LOCKOUT_PERMANENT -> KeyringResult.Failed("Demasiados intentos. Espera un momento o usa el PIN del dispositivo.")
                            else -> KeyringResult.Failed(errString?.toString() ?: "No se pudo verificar tu identidad.")
                        },
                    ),
                )
            }
        }
        try {
            val p = builder.build()
            if (crypto != null) p.authenticate(BiometricPrompt.CryptoObject(crypto), signal, context.mainExecutor, callback)
            else p.authenticate(signal, context.mainExecutor, callback)
        } catch (e: Exception) {
            SafeLog.e("KeystoreKeyring", "The authentication prompt could not be shown", e)
            if (cont.isActive) cont.resume(Prompt.Stop(KeyringResult.Failed("No se pudo mostrar la verificación de identidad.")))
        }
    }

    companion object {
        const val ALIAS = "s3vault.private.v1"
        private const val AUTH_WINDOW_S = 10
        private const val ERR_CANCELED = 5
        private const val ERR_LOCKOUT = 7
        private const val ERR_LOCKOUT_PERMANENT = 9
        private const val ERR_USER_CANCELED = 10
        private const val ERR_NO_BIOMETRICS = 11
        private const val ERR_HW_NOT_PRESENT = 12
        private const val ERR_NEGATIVE_BUTTON = 13
        private const val ERR_NO_DEVICE_CREDENTIAL = 14
    }
}
