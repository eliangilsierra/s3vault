package com.s3vault.app.data.faces

import com.s3vault.app.domain.faces.DataKeyStore
import com.s3vault.app.security.SecretStore
import java.security.SecureRandom

/**
 * The face index's data key, kept by the app's Keystore-backed [SecretStore] (AES-256-GCM, key in the Android Keystore, file in
 * the no-backup directory). A key that exists but cannot be read is an error, not a reason to make a new one: replacing it would
 * silently orphan every sealed vector, so the caller is told and the user decides to rebuild.
 */
class SecretDataKeyStore(private val secrets: SecretStore, private val name: String = "face_index_key") : DataKeyStore {
    @Synchronized
    override fun load(): ByteArray {
        secrets.get(name)?.let { return it }
        check(!secrets.exists(name)) { "The face index key cannot be read" }
        return ByteArray(32).also { SecureRandom().nextBytes(it); secrets.put(name, it) }
    }

    @Synchronized
    override fun destroy() { secrets.delete(name) }
}
