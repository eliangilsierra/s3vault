package com.s3vault.app.data.privacy

import com.s3vault.app.data.local.PrivateAlbumEntity
import com.s3vault.app.data.local.PrivateDao
import com.s3vault.app.data.local.PrivateItemEntity
import com.s3vault.app.data.local.PrivateVaultEntity
import com.s3vault.app.domain.privacy.KeyringResult
import com.s3vault.app.domain.privacy.PrivateAlbum
import com.s3vault.app.domain.privacy.PrivateItem
import com.s3vault.app.domain.privacy.PrivateMeta
import com.s3vault.app.domain.privacy.PrivateSession
import com.s3vault.app.domain.privacy.UnlockPrompt
import com.s3vault.app.domain.privacy.VaultAvailability
import com.s3vault.app.domain.privacy.VaultCrypto
import com.s3vault.app.domain.privacy.VaultKeyring
import com.s3vault.app.domain.privacy.VaultLockedException
import com.s3vault.app.domain.privacy.WrappedKey
import com.s3vault.app.security.SafeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The encrypted store behind the private albums: where the sealed files live, the database rows that describe them, and the
 * rules that keep the two in step.
 *
 *  - A file is written next to its final place under a temporary name, read back and checked against what was read from the
 *    source, flushed to disk, and only then renamed; the database row is inserted last. A crash leaves at most a stray file,
 *    never a row that points at nothing.
 *  - Nothing here ever touches an original picture. Putting a picture in the vault copies it; removing the original is a
 *    separate, explicit step of the caller.
 *  - Every read or write needs the session to be unlocked; a lock in the middle of a batch stops it at the next picture.
 */
class PrivateVaultStore(
    private val dao: PrivateDao,
    private val keyring: VaultKeyring,
    val session: PrivateSession,
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val random = SecureRandom()
    private val keyLost = MutableStateFlow(false)
    private val fileLock = Mutex()

    sealed interface UnlockOutcome {
        data object Unlocked : UnlockOutcome
        data object Cancelled : UnlockOutcome
        data object NeedsScreenLock : UnlockOutcome
        class KeyLost(val reason: String) : UnlockOutcome
        class Failed(val message: String) : UnlockOutcome
    }

    sealed interface AddResult {
        class Added(val itemId: Long, val plainBytes: Long) : AddResult
        class Failed(val reason: String) : AddResult
    }

    /** What happened to one picture taken out of the vault. */
    sealed interface ExportResult {
        class Done(val uri: String) : ExportResult
        class Failed(val reason: String) : ExportResult
    }

    // ------------------------------------------------------------------ the vault itself

    suspend fun availability(): VaultAvailability = when {
        dao.vault() != null -> if (keyLost.value || !keyring.isSecure()) VaultAvailability.KEY_LOST else VaultAvailability.READY
        !keyring.isSecure() -> VaultAvailability.NEEDS_SCREEN_LOCK
        else -> VaultAvailability.NOT_CREATED
    }

    /** Asks for the user's identity (creating the vault the first time) and opens the session. */
    suspend fun unlock(prompt: UnlockPrompt): UnlockOutcome {
        val row = dao.vault()
        val result = if (row == null) keyring.create(prompt) else keyring.unwrap(WrappedKey(row.wrappedKey, row.wrapIv), prompt)
        return when (result) {
            is KeyringResult.Key -> {
                try {
                    if (row == null) {
                        dao.insertVault(PrivateVaultEntity(wrappedKey = result.wrapped.cipherText, wrapIv = result.wrapped.iv, keyCheck = seal(result.key, CANARY), createdAtMs = clock()))
                    } else if (!checkCanary(result.key, row.keyCheck)) {
                        keyLost.value = true
                        return UnlockOutcome.KeyLost("La clave de este dispositivo no abre los archivos protegidos.")
                    }
                    session.unlock(result.key)
                    keyLost.value = false
                    UnlockOutcome.Unlocked
                } finally {
                    result.key.fill(0)
                }
            }
            KeyringResult.Cancelled -> UnlockOutcome.Cancelled
            KeyringResult.NeedsScreenLock -> UnlockOutcome.NeedsScreenLock
            is KeyringResult.KeyLost -> { keyLost.value = true; UnlockOutcome.KeyLost(result.reason) }
            is KeyringResult.Failed -> UnlockOutcome.Failed(result.message)
        }
    }

    fun lock() = session.lock()

    /**
     * For a vault whose key is gone: deletes every sealed file and row (they cannot be read any more) and the leftover Keystore
     * entry, so a new vault can be created. Irreversible, and only offered in that state.
     */
    suspend fun resetLostVault() = withContext(Dispatchers.IO) {
        fileLock.withLock {
            session.lock()
            dir.listFiles()?.forEach { it.delete() }
            dao.albums().forEach { dao.deleteAlbum(it.id) }
            dao.deleteVault()
            keyring.deleteKey()
            keyLost.value = false
        }
    }

    // ------------------------------------------------------------------ albums

    fun observeAlbums(): Flow<List<PrivateAlbum>> = combine(dao.observeAlbums(), dao.observeCounts(), session.unlocked) { albums, counts, unlocked ->
        if (!unlocked) return@combine emptyList()
        val byAlbum = counts.associateBy { it.albumId }
        withKey { key ->
            albums.map { a ->
                PrivateAlbum(a.id, runCatching { String(open(key, a.nameSealed), Charsets.UTF_8) }.getOrDefault("(álbum ilegible)"), byAlbum[a.id]?.items ?: 0, byAlbum[a.id]?.bytes ?: 0, a.createdAtMs)
            }
        } ?: emptyList()
    }.flowOn(Dispatchers.Default)

    suspend fun createAlbum(name: String): Long {
        val clean = name.trim().take(MAX_ALBUM_NAME)
        require(clean.isNotEmpty()) { "El álbum necesita un nombre" }
        return withKey { key -> dao.insertAlbum(PrivateAlbumEntity(nameSealed = seal(key, clean.toByteArray(Charsets.UTF_8)), createdAtMs = clock())) } ?: throw VaultLockedException()
    }

    suspend fun renameAlbum(id: Long, name: String) {
        val clean = name.trim().take(MAX_ALBUM_NAME)
        require(clean.isNotEmpty()) { "El álbum necesita un nombre" }
        withKey { key -> dao.renameAlbum(id, seal(key, clean.toByteArray(Charsets.UTF_8))) } ?: throw VaultLockedException()
    }

    /** Deletes the album and every picture in it, permanently. The caller asks the user first. */
    suspend fun deleteAlbum(id: Long) = withContext(Dispatchers.IO) {
        session.keyCopy().fill(0) // refuses when locked
        fileLock.withLock {
            val files = dao.items(id).map { it.fileName }
            dao.deleteAlbum(id)
            files.forEach { deleteFiles(it) }
        }
    }

    // ------------------------------------------------------------------ pictures

    fun observeItems(albumId: Long): Flow<List<PrivateItem>> = combine(dao.observeItems(albumId), session.unlocked) { rows, unlocked ->
        if (!unlocked) return@combine emptyList()
        withKey { key -> rows.mapNotNull { r -> metaOf(key, r)?.let { PrivateItem(r.id, r.albumId, r.fileName, r.sealedBytes, r.addedAtMs, it) } } }
            ?.sortedWith(compareByDescending<PrivateItem> { it.meta.dateTakenMs }.thenByDescending { it.id }) ?: emptyList()
    }.flowOn(Dispatchers.Default)

    suspend fun item(id: Long): PrivateItem? = withKey { key -> dao.item(id)?.let { r -> metaOf(key, r)?.let { PrivateItem(r.id, r.albumId, r.fileName, r.sealedBytes, r.addedAtMs, it) } } }

    /**
     * Seals one picture into [albumId]. [open] is called once and read to its end; [thumbnail] is the small JPEG shown in grids
     * (sealed too). [base] describes the picture; its size must match what is read, otherwise the copy is refused.
     */
    suspend fun add(albumId: Long, open: () -> InputStream, thumbnail: ByteArray?, base: PrivateMeta): AddResult = withContext(Dispatchers.IO) {
        val key = try { session.keyCopy() } catch (e: VaultLockedException) { return@withContext AddResult.Failed("El álbum privado está bloqueado.") }
        val name = randomName()
        val vTmp = File(dir, "$name.v.tmp"); val tTmp = File(dir, "$name.t.tmp")
        val vFinal = File(dir, "$name.v"); val tFinal = File(dir, "$name.t")
        try {
            dir.mkdirs()
            val written = open().use { input ->
                FileOutputStream(vTmp).use { fos ->
                    val w = VaultCrypto.encrypt(key, input, fos.buffered(BUFFER))
                    fos.flush(); fos.fd.sync()
                    w
                }
            }
            if (written.plainBytes != base.sizeBytes) return@withContext AddResult.Failed("El archivo cambió mientras se copiaba. Actualiza la biblioteca y vuelve a intentarlo.")
            // Read it back: the copy must decrypt to exactly what was read from the original.
            val check = sha256Of(FileInputStream(vTmp).buffered(BUFFER)) { s -> VaultCrypto.DecryptingStream(key, s) }
            if (check != written.sha256Hex) return@withContext AddResult.Failed("La copia cifrada no coincide con el original. No se ha guardado nada.")
            if (thumbnail != null) writeSealed(tTmp, key, thumbnail)
            if (!vTmp.renameTo(vFinal)) return@withContext AddResult.Failed("No se pudo guardar el archivo cifrado.")
            if (thumbnail != null && !tTmp.renameTo(tFinal)) { vFinal.delete(); return@withContext AddResult.Failed("No se pudo guardar la vista previa cifrada.") }
            val meta = base.copy(sha256 = written.sha256Hex, sizeBytes = written.plainBytes)
            val id = try {
                fileLock.withLock {
                    dao.insertItem(PrivateItemEntity(albumId = albumId, fileName = name, sealedBytes = vFinal.length(), addedAtMs = clock(), metaSealed = seal(key, meta.encode())))
                }
            } catch (e: Exception) {
                deleteFiles(name)
                throw e
            }
            AddResult.Added(id, written.plainBytes)
        } catch (e: java.io.IOException) {
            SafeLog.e("PrivateVaultStore", "Could not seal a picture", e)
            AddResult.Failed("No se pudo escribir el archivo cifrado (¿falta espacio?).")
        } catch (e: Exception) {
            SafeLog.e("PrivateVaultStore", "Unexpected failure sealing a picture", e)
            AddResult.Failed("Error inesperado al cifrar la foto.")
        } finally {
            key.fill(0)
            vTmp.delete(); tTmp.delete()
        }
    }

    /** The small preview of [item], decrypted in memory. Null when it has none or cannot be read. */
    suspend fun thumbnailBytes(item: PrivateItem): ByteArray? = withContext(Dispatchers.IO) {
        val f = File(dir, "${item.fileName}.t")
        if (!f.exists()) return@withContext null
        withKey { key -> runCatching { FileInputStream(f).use { VaultCrypto.decryptToBytes(key, it) } }.getOrNull() }
    }

    /** The whole picture, decrypted in memory (never written anywhere in the clear). Throws if it was altered or the vault is locked. */
    suspend fun imageBytes(item: PrivateItem): ByteArray = withContext(Dispatchers.IO) {
        val key = session.keyCopy()
        try {
            FileInputStream(File(dir, "${item.fileName}.v")).buffered(BUFFER).use { VaultCrypto.decryptToBytes(key, it) }
        } finally { key.fill(0) }
    }

    /** Streams the decrypted picture to [out] and returns the SHA-256 of what was written. */
    suspend fun decryptTo(item: PrivateItem, out: OutputStream): String = withContext(Dispatchers.IO) {
        val key = session.keyCopy()
        try {
            val md = MessageDigest.getInstance("SHA-256")
            VaultCrypto.DecryptingStream(key, FileInputStream(File(dir, "${item.fileName}.v")).buffered(BUFFER)).use { s ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n); out.write(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } finally { key.fill(0) }
    }

    /** Deletes these pictures for good (database rows first, then the files). */
    suspend fun remove(ids: List<Long>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        fileLock.withLock {
            ids.chunked(500).forEach { chunk ->
                val rows = dao.itemsById(chunk)
                dao.deleteItems(chunk)
                rows.forEach { deleteFiles(it.fileName) }
            }
        }
    }

    suspend fun move(ids: List<Long>, toAlbum: Long) { ids.chunked(500).forEach { dao.moveItems(it, toAlbum) } }

    /** Removes temporary files and sealed files that no row refers to (left by a crash). Files younger than [graceMs] are left alone. */
    suspend fun sweepStrays(graceMs: Long = 10 * 60_000L): Int = withContext(Dispatchers.IO) {
        fileLock.withLock {
            val known = dao.allFileNames().toHashSet()
            var n = 0
            dir.listFiles()?.forEach { f ->
                val base = f.name.substringBefore('.')
                val stray = f.name.endsWith(".tmp") || base !in known
                if (stray && clock() - f.lastModified() >= graceMs && f.delete()) n++
            }
            n
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun deleteFiles(name: String) { File(dir, "$name.v").delete(); File(dir, "$name.t").delete() }

    private fun randomName(): String = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun writeSealed(file: File, key: ByteArray, bytes: ByteArray) {
        FileOutputStream(file).use { fos ->
            VaultCrypto.encrypt(key, ByteArrayInputStream(bytes), fos.buffered(BUFFER))
            fos.flush(); fos.fd.sync()
        }
    }

    private fun metaOf(key: ByteArray, r: PrivateItemEntity): PrivateMeta? =
        runCatching { PrivateMeta.decode(open(key, r.metaSealed)) }.getOrNull()

    private inline fun <T> withKey(block: (ByteArray) -> T): T? {
        val key = try { session.keyCopy() } catch (e: VaultLockedException) { return null }
        try { return block(key) } finally { key.fill(0) }
    }

    private inline fun sha256Of(stream: InputStream, wrap: (InputStream) -> InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        wrap(stream).use { s ->
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun checkCanary(key: ByteArray, sealed: ByteArray): Boolean =
        runCatching { open(key, sealed).contentEquals(CANARY) }.getOrDefault(false)

    private fun seal(key: ByteArray, plain: ByteArray): ByteArray =
        ByteArrayOutputStream().also { VaultCrypto.encrypt(key, ByteArrayInputStream(plain), it) }.toByteArray()

    private fun open(key: ByteArray, sealed: ByteArray): ByteArray = VaultCrypto.decryptToBytes(key, ByteArrayInputStream(sealed))

    companion object {
        private const val BUFFER = 64 * 1024
        const val MAX_ALBUM_NAME = 60
        private val CANARY = "s3vault.private.vault.v1".toByteArray()
    }
}
