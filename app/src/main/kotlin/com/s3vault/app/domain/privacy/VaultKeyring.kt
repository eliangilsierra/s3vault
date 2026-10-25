package com.s3vault.app.domain.privacy

/** The vault key as stored: encrypted by a key that never leaves the device's secure hardware or Keystore. */
class WrappedKey(val cipherText: ByteArray, val iv: ByteArray)

class UnlockPrompt(val title: String, val subtitle: String)

sealed interface KeyringResult {
    /** [key] is the plain 32-byte vault key; [wrapped] is what to store (unchanged for an unwrap). */
    class Key(val key: ByteArray, val wrapped: WrappedKey) : KeyringResult
    data object Cancelled : KeyringResult
    data object NeedsScreenLock : KeyringResult
    /** The wrapping key no longer exists or no longer works. The sealed files cannot be opened again. */
    class KeyLost(val reason: String) : KeyringResult
    class Failed(val message: String) : KeyringResult
}

/**
 * Where the vault key is protected. The Android implementation keeps an AES key in the Keystore that can only be used after the
 * user authenticates with biometrics or the screen-lock credential, and uses it to wrap the vault key. The key is never
 * written anywhere in the clear.
 */
interface VaultKeyring {
    /** False when the device has no secure screen lock at all. */
    fun isSecure(): Boolean

    /** Creates a fresh wrapping key (replacing any previous one), asks the user to authenticate, and wraps a new random vault key with it. */
    suspend fun create(prompt: UnlockPrompt): KeyringResult

    /** Asks the user to authenticate and unwraps [wrapped]. */
    suspend fun unwrap(wrapped: WrappedKey, prompt: UnlockPrompt): KeyringResult

    /** Removes the wrapping key. The vault key, wherever it is wrapped, can no longer be recovered. */
    fun deleteKey()
}
