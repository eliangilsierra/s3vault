package app.s3vault.security

/**
 * Almacenamiento cifrado de secretos. La implementación de Android usa una clave AES-GCM
 * que vive en Android Keystore; los datos nunca se guardan en texto plano ni en Room.
 */
interface SecretStore {
    suspend fun read(name: String): ByteArray?
    suspend fun write(name: String, value: ByteArray)
    suspend fun delete(name: String)
    suspend fun deleteAll()
}

/** Almacén en memoria para pruebas. */
class InMemorySecretStore : SecretStore {
    private val map = HashMap<String, ByteArray>()
    val names: Set<String> get() = synchronized(map) { map.keys.toSet() }
    override suspend fun read(name: String): ByteArray? = synchronized(map) { map[name]?.copyOf() }
    override suspend fun write(name: String, value: ByteArray) {
        synchronized(map) { map[name] = value.copyOf() }
    }

    override suspend fun delete(name: String) {
        synchronized(map) { map.remove(name) }
    }

    override suspend fun deleteAll() {
        synchronized(map) { map.clear() }
    }
}
