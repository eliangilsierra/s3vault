package app.s3vault.pure

import app.s3vault.domain.model.ErrorKind
import app.s3vault.domain.model.SyncState
import app.s3vault.domain.remote.FailureType
import app.s3vault.domain.remote.RemoteFailure
import app.s3vault.domain.remote.RemoteKeys
import app.s3vault.data.remote.RemoteCatalog
import app.s3vault.data.remote.RemoteCatalogStore
import app.s3vault.data.remote.RemoteEntry
import app.s3vault.data.remote.ScopedObjectStore
import app.s3vault.sync.BackupEngine
import app.s3vault.sync.MediaReconciler
import app.s3vault.sync.RestoreEngine
import app.s3vault.sync.RestoreOutcome
import app.s3vault.sync.ScannedMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.time.Clock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RestoreEngineTest {
    private val keys = RemoteKeys("backup/")
    private val remote = FakeObjectStore()
    private val store = FakeBackupStore()
    private val dest = FakeDestination()
    private val log = FakeLog()
    private val dir: File = Files.createTempDirectory("restore").toFile()
    private val photo = ByteArray(10_000) { (it * 7 % 253).toByte() }
    private val asset = "c".repeat(32)

    @AfterTest fun cleanup() { dir.deleteRecursively() }

    private fun engine() = RestoreEngine(ScopedObjectStore(remote, keys), keys, store, dest, dir, log, Clock.systemUTC(), Dispatchers.Unconfined)

    private fun seed(bytes: ByteArray = photo, name: String = "IMG_9.jpg", withMeta: Boolean = true): String {
        val sha = sha256Hex(bytes)
        val key = keys.objectKey(asset, sha.take(32), name)
        remote.putDirect(key, bytes, if (withMeta) mapOf("sha256" to sha, "original-name" to name, "captured-at" to "1700000000000") else emptyMap())
        return key
    }

    @Test
    fun `descarga, verifica, publica y enlaza el elemento como respaldado`() = runTest {
        val key = seed()
        val out = assertIs<RestoreOutcome.Restored>(engine().restore(key))
        assertTrue(out.verified)
        assertContentEquals(photo, dest.published.single().second)
        val item = store.rows.getValue(asset)
        assertEquals(SyncState.BACKED_UP, item.state)
        assertEquals(key, item.remoteKey)
        assertEquals(1_700_000_000_000, item.dateTakenMs)
        assertTrue(dir.listFiles().orEmpty().isEmpty(), "no quedan temporales")
    }

    @Test
    fun `un archivo corrupto no se publica`() = runTest {
        val key = seed()
        // el objeto en S3 fue alterado: el metadato dice otro hash
        val tampered = photo.copyOf().also { it[10] = 0 }
        remote.putDirect(key, tampered, mapOf("sha256" to sha256Hex(photo)))
        val out = engine().restore(key)
        assertIs<RestoreOutcome.Deferred>(out)
        assertEquals(FailureType.CHECKSUM_MISMATCH, out.failure.type)
        assertTrue(dest.published.isEmpty())
        assertNull(store.rows[asset])
        assertTrue(dir.listFiles().orEmpty().isEmpty(), "se descarta lo corrupto")
    }

    @Test
    fun `nunca sobrescribe - conflicto de nombre genera otro nombre`() = runTest {
        dest.taken += "IMG_9.jpg"
        val key = seed()
        val out = assertIs<RestoreOutcome.Restored>(engine().restore(key))
        assertEquals("IMG_9 (1).jpg", out.item.displayName)
        assertEquals("IMG_9 (1).jpg", dest.published.single().first)
    }

    @Test
    fun `objetos sin metadatos propios se verifican contra el id de version`() = runTest {
        val key = seed(withMeta = false)
        val out = assertIs<RestoreOutcome.Restored>(engine().restore(key))
        assertEquals(false, out.verified)
        // y un objeto cuyo contenido no coincide con su id de version se rechaza
        val bad = keys.objectKey("d".repeat(32), "0".repeat(32), "x.jpg")
        remote.putDirect(bad, photo)
        assertIs<RestoreOutcome.Deferred>(engine().restore(bad))
    }

    @Test
    fun `reanuda una descarga interrumpida con Range e If-Match`() = runTest {
        val key = seed()
        val head = remote.head(key)!!
        // simulamos una descarga previa interrumpida a mitad
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        val part = File(dir, digest.take(12).joinToString("") { "%02x".format(it) } + ".part")
        part.writeBytes(photo.copyOfRange(0, 4000))
        File(part.path + ".etag").writeText(head.etag!!)
        remote.rangeRequests.clear()
        assertIs<RestoreOutcome.Restored>(engine().restore(key))
        assertEquals(4000L to head.etag, remote.rangeRequests.single())
        assertContentEquals(photo, dest.published.single().second)
    }

    @Test
    fun `si el objeto cambio en S3 se descarta lo parcial y se descarga completo`() = runTest {
        val key = seed()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        val part = File(dir, digest.take(12).joinToString("") { "%02x".format(it) } + ".part")
        part.writeBytes(photo.copyOfRange(0, 4000))
        File(part.path + ".etag").writeText("\"etag-viejo\"")
        remote.rangeRequests.clear()
        assertIs<RestoreOutcome.Restored>(engine().restore(key))
        assertEquals(0L, remote.rangeRequests.single().first)
    }

    @Test
    fun `claves fuera del prefijo se rechazan antes de cualquier solicitud`() = runTest {
        val out = engine().restore("otro/photos/${asset}/${"1".repeat(32)}/a.jpg")
        assertIs<RestoreOutcome.Failed>(out)
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `sin credenciales la restauracion queda bloqueada`() = runTest {
        val key = seed()
        remote.failures += RemoteFailure(FailureType.CREDENTIALS_EXPIRED)
        assertIs<RestoreOutcome.Blocked>(engine().restore(key))
        assertTrue(dest.published.isEmpty())
    }

    @Test
    fun `restaurar tras borrar localmente deja la fila enlazada`() = runTest {
        // flujo completo: respaldar -> borrar local (REMOTE_ONLY) -> restaurar
        val src = FakeMediaSource(); val index = FakeIndex()
        src.files["content://media/1"] = photo to 100L
        store.rows[asset] = media(asset, "content://media/1", size = photo.size.toLong())
        val backup = BackupEngine(store, ScopedObjectStore(remote, keys), keys, src, index, log, { _, _, _ -> }, Clock.systemUTC(), Dispatchers.Unconfined)
        backup.process(asset)
        val key = store.rows.getValue(asset).remoteKey!!
        val rec = MediaReconciler.reconcile(store.rows.values.toList(), emptyList(), { "x" })
        rec.upserts.forEach { store.upsert(it) }
        assertEquals(SyncState.REMOTE_ONLY, store.rows.getValue(asset).state)
        assertTrue(remote.objects.containsKey(key), "la copia en S3 sigue ahi")
        assertIs<RestoreOutcome.Restored>(engine().restore(key))
        assertEquals(SyncState.BACKED_UP, store.rows.getValue(asset).state)
    }
}

class MediaReconcilerTest {
    private var n = 0
    private fun newId() = (++n).toString().padStart(32, '0')
    private fun scan(uri: String, size: Long = 10, modified: Long = 100, name: String = "a.jpg") =
        ScannedMedia(uri, name, "image/jpeg", size, modified, null, 10, 10, false, "Camera")

    @Test
    fun `nuevos elementos entran como LOCAL_ONLY con id estable propio`() {
        val r = MediaReconciler.reconcile(emptyList(), listOf(scan("u1"), scan("u2")), ::newId)
        assertEquals(2, r.added)
        assertTrue(r.upserts.all { it.state == SyncState.LOCAL_ONLY && it.sha256 == null })
        assertEquals(2, r.upserts.map { it.assetId }.toSet().size)
    }

    @Test
    fun `sin cambios no hay escrituras ni se invalida el hash`() {
        val existing = media("a".repeat(32), "u1", name = "a.jpg", size = 10, modified = 100, state = SyncState.BACKED_UP)
            .copy(sha256 = "f".repeat(64), hashedSizeBytes = 10, hashedModifiedMs = 100, dateTakenMs = null, width = 10, height = 10, folder = "Camera", versionId = "f".repeat(32), remoteKey = "k", backedUpVersionId = "f".repeat(32))
        val r = MediaReconciler.reconcile(listOf(existing), listOf(scan("u1")), ::newId)
        assertTrue(r.upserts.isEmpty() && r.deletes.isEmpty())
    }

    @Test
    fun `contenido modificado invalida el hash y deja de estar respaldado, conservando la copia previa`() {
        val existing = media("a".repeat(32), "u1", size = 10, modified = 100, state = SyncState.BACKED_UP)
            .copy(sha256 = "f".repeat(64), hashedSizeBytes = 10, hashedModifiedMs = 100, versionId = "f".repeat(32), remoteKey = "k", backedUpVersionId = "f".repeat(32))
        val r = MediaReconciler.reconcile(listOf(existing), listOf(scan("u1", size = 12, modified = 200)), ::newId)
        val u = r.upserts.single()
        assertEquals(SyncState.LOCAL_ONLY, u.state)
        assertTrue(!u.isHashCurrent)
        assertEquals("k", u.remoteKey)
        assertEquals(1, r.changed)
    }

    @Test
    fun `borrar localmente un respaldado lo conserva como REMOTE_ONLY y no toca S3`() {
        val backed = media("a".repeat(32), "u1", size = 10, state = SyncState.BACKED_UP).copy(remoteKey = "k", backedUpVersionId = "v")
        val notBacked = media("b".repeat(32), "u2", size = 10, state = SyncState.LOCAL_ONLY)
        val r = MediaReconciler.reconcile(listOf(backed, notBacked), emptyList(), ::newId)
        assertEquals(SyncState.REMOTE_ONLY, r.upserts.single().state)
        assertEquals(listOf("b".repeat(32)), r.deletes, "solo se elimina del indice lo que nunca se respaldo")
    }

    @Test
    fun `un escaneo parcial no marca elementos como desaparecidos`() {
        val backed = media("a".repeat(32), "u1", size = 10, state = SyncState.BACKED_UP).copy(remoteKey = "k", backedUpVersionId = "v")
        val r = MediaReconciler.reconcile(listOf(backed), emptyList(), ::newId, trustAbsence = false)
        assertTrue(r.upserts.isEmpty() && r.deletes.isEmpty())
    }

    @Test
    fun `un REMOTE_ONLY que reaparece vuelve a BACKED_UP si no cambio`() {
        val ro = media("a".repeat(32), "u1", size = 10, modified = 100, state = SyncState.REMOTE_ONLY).copy(remoteKey = "k", backedUpVersionId = "v")
        assertEquals(SyncState.BACKED_UP, MediaReconciler.reconcile(listOf(ro), listOf(scan("u1")), ::newId).upserts.single().state)
        assertEquals(SyncState.LOCAL_ONLY, MediaReconciler.reconcile(listOf(ro), listOf(scan("u1", size = 99)), ::newId).upserts.single().state)
    }
}

class CatalogTest {
    private class MemCatalog : RemoteCatalogStore {
        var entries: List<RemoteEntry> = emptyList(); var at: Long? = null
        override suspend fun replaceAll(entries: List<RemoteEntry>, refreshedAtMs: Long) { this.entries = entries; at = refreshedAtMs }
        override suspend fun lastRefreshMs() = at
        override suspend fun findKeyByVersion(versionId: String) = entries.firstOrNull { it.versionId == versionId }?.key
        override suspend fun remember(key: String, sizeBytes: Long, lastModifiedMs: Long?, etag: String?) = Unit
    }

    @Test
    fun `lista solo objetos de la app dentro del prefijo y pagina`() = runTest {
        val keys = RemoteKeys("backup/")
        val remote = FakeObjectStore()
        for (i in 1..5) remote.putDirect(keys.objectKey(i.toString().padStart(32, '0'), "a".repeat(32), "f$i.jpg"), ByteArray(i))
        remote.putDirect("backup/.s3vault/connection-check.txt", ByteArray(3))
        remote.putDirect("backup/otros/archivo.txt", ByteArray(3))
        remote.putDirect("fuera/photos/${"1".repeat(32)}/${"a".repeat(32)}/x.jpg", ByteArray(3))
        val cache = MemCatalog()
        val entries = RemoteCatalog(object : app.s3vault.data.remote.ObjectStore by remote {
            override suspend fun list(prefix: String, continuationToken: String?, maxKeys: Int) = remote.list(prefix, continuationToken, 2)
        }, keys, cache, { 42L }).refresh()
        assertEquals(5, entries.size)
        assertEquals(42L, cache.lastRefreshMs())
        assertEquals(3, remote.calls.count { it.startsWith("list:backup/photos/") }, "3 paginas de 2")
        assertNotNull(cache.findKeyByVersion("a".repeat(32)))
    }
}
