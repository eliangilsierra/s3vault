package app.s3vault.pure

import app.s3vault.data.remote.ScopedObjectStore
import app.s3vault.domain.model.ErrorKind
import app.s3vault.domain.model.SyncState
import app.s3vault.domain.remote.FailureType
import app.s3vault.domain.remote.RemoteFailure
import app.s3vault.domain.remote.RemoteKeys
import app.s3vault.sync.BackupEngine
import app.s3vault.sync.BackupRunner
import app.s3vault.sync.ItemOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupEngineTest {
    private val keys = RemoteKeys("backup/")
    private val store = FakeBackupStore()
    private val remote = FakeObjectStore()
    private val source = FakeMediaSource()
    private val index = FakeIndex()
    private val log = FakeLog()
    private val progress = mutableListOf<Triple<String, Long, Long>>()
    private val photo = ByteArray(5000) { (it % 251).toByte() }

    private fun engine(maxAttempts: Int = 3) = BackupEngine(
        store, ScopedObjectStore(remote, keys), keys, source, index, log,
        { a, d, t -> progress += Triple(a, d, t) }, Clock.systemUTC(), Dispatchers.Unconfined, maxAttempts,
    )

    private fun queue(id: String = "a".repeat(32), uri: String = "content://media/1", bytes: ByteArray = photo, modified: Long = 100) {
        source.files[uri] = bytes to modified
        store.rows[id] = media(id, uri, size = bytes.size.toLong(), modified = modified)
    }

    @Test
    fun `sube, verifica y solo entonces marca BACKED_UP`() = runTest {
        queue()
        val outcome = engine().process("a".repeat(32))
        assertEquals(ItemOutcome.BackedUp(uploaded = true), outcome)
        val item = store.rows.getValue("a".repeat(32))
        assertEquals(SyncState.BACKED_UP, item.state)
        assertEquals(item.versionId, item.backedUpVersionId)
        assertNotNull(item.lastBackupAtMs)
        val key = assertNotNull(item.remoteKey)
        assertTrue(key.startsWith("backup/photos/${"a".repeat(32)}/${item.versionId}/"))
        assertTrue(key.endsWith("/IMG_0001.jpg"))
        // el objeto remoto guarda el checksum y los metadatos
        val stored = remote.objects.getValue(key)
        assertEquals(sha256Hex(photo), stored.metadata["sha256"])
        assertEquals("image/jpeg", stored.contentType)
        // se verificó con un HEAD después del PUT
        val calls = remote.calls
        assertTrue(calls.indexOfLast { it.startsWith("head:") } > calls.indexOfFirst { it.startsWith("put:") })
        assertEquals(photo.size.toLong(), progress.last().second)
    }

    @Test
    fun `no marca respaldado si la verificacion falla y reintenta despues`() = runTest {
        queue()
        remote.dropChecksumOnHead = true // S3 no devuelve checksum ni metadatos verificables
        val outcome = engine().process("a".repeat(32))
        assertIs<ItemOutcome.Deferred>(outcome)
        val item = store.rows.getValue("a".repeat(32))
        assertEquals(SyncState.QUEUED, item.state)
        assertNull(item.backedUpVersionId)
        assertEquals(ErrorKind.CHECKSUM_MISMATCH, item.lastErrorKind)
    }

    @Test
    fun `archivo ya respaldado con mismo contenido no se vuelve a subir`() = runTest {
        queue()
        assertIs<ItemOutcome.BackedUp>(engine().process("a".repeat(32)))
        val puts = remote.count("put:")
        // se vuelve a encolar el mismo contenido (p. ej. tras reinstalar y perder el estado local)
        store.rows["a".repeat(32)] = store.rows.getValue("a".repeat(32)).copy(state = SyncState.QUEUED, remoteKey = null, backedUpVersionId = null, sha256 = null)
        val outcome = engine().process("a".repeat(32))
        assertEquals(ItemOutcome.BackedUp(uploaded = false), outcome)
        assertEquals(puts, remote.count("put:"), "no debe haber otra transferencia")
        assertEquals(SyncState.BACKED_UP, store.rows.getValue("a".repeat(32)).state)
    }

    @Test
    fun `dos archivos con el mismo contenido y distinto assetId reutilizan la copia remota`() = runTest {
        queue("a".repeat(32), "content://media/1")
        queue("b".repeat(32), "content://media/2")
        assertEquals(ItemOutcome.BackedUp(true), engine().process("a".repeat(32)))
        assertEquals(ItemOutcome.BackedUp(false), engine().process("b".repeat(32)))
        assertEquals(1, remote.count("put:"))
        assertEquals(store.rows.getValue("a".repeat(32)).remoteKey, store.rows.getValue("b".repeat(32)).remoteKey)
    }

    @Test
    fun `archivo modificado crea una version nueva sin tocar la anterior`() = runTest {
        queue()
        engine().process("a".repeat(32))
        val v1 = store.rows.getValue("a".repeat(32))
        val oldKey = v1.remoteKey!!
        // el usuario edita la foto: cambia contenido y fecha
        val edited = photo.copyOf().also { it[0] = (it[0] + 1).toByte() }
        source.files["content://media/1"] = edited to 200L
        store.rows["a".repeat(32)] = v1.copy(state = SyncState.QUEUED, sizeBytes = edited.size.toLong(), dateModifiedMs = 200L)
        assertEquals(ItemOutcome.BackedUp(true), engine().process("a".repeat(32)))
        val v2 = store.rows.getValue("a".repeat(32))
        assertTrue(v2.versionId != v1.versionId)
        assertTrue(v2.remoteKey != oldKey)
        assertTrue(remote.objects.containsKey(oldKey), "la copia anterior sigue en S3")
        assertEquals(2, remote.objects.size)
    }

    @Test
    fun `el hash no se recalcula si la huella no cambio`() = runTest {
        queue()
        // primera pasada falla por red tras calcular el hash
        remote.failures += RemoteFailure(FailureType.NETWORK)
        assertIs<ItemOutcome.Deferred>(engine().process("a".repeat(32)))
        val hashedOnce = store.rows.getValue("a".repeat(32))
        assertTrue(hashedOnce.isHashCurrent)
        val opens = source.openCount
        assertIs<ItemOutcome.BackedUp>(engine().process("a".repeat(32)))
        // solo se abrió el archivo para subirlo, no para volver a hashearlo
        assertEquals(opens + 1, source.openCount)
    }

    @Test
    fun `error de red es temporal y conserva el trabajo en cola`() = runTest {
        queue()
        remote.failures += RemoteFailure(FailureType.NETWORK)
        assertIs<ItemOutcome.Deferred>(engine().process("a".repeat(32)))
        val item = store.rows.getValue("a".repeat(32))
        assertEquals(SyncState.QUEUED, item.state)
        assertEquals(ErrorKind.NETWORK, item.lastErrorKind)
        assertEquals(1, item.attempts)
        // vuelve la red
        assertIs<ItemOutcome.BackedUp>(engine().process("a".repeat(32)))
    }

    @Test
    fun `credenciales caducadas bloquean la cola sin marcar respaldado ni fallido`() = runTest {
        queue()
        remote.failures += RemoteFailure(FailureType.CREDENTIALS_EXPIRED)
        val outcome = engine().process("a".repeat(32))
        assertIs<ItemOutcome.Blocked>(outcome)
        val item = store.rows.getValue("a".repeat(32))
        assertEquals(SyncState.QUEUED, item.state)
        assertNull(item.backedUpVersionId)
        assertEquals(ErrorKind.CREDENTIALS_EXPIRED, item.lastErrorKind)
        assertTrue(remote.objects.isEmpty())
    }

    @Test
    fun `falta de permisos se comunica y detiene la cola`() = runTest {
        queue()
        remote.failures += RemoteFailure(FailureType.ACCESS_DENIED, "AccessDenied")
        val outcome = engine().process("a".repeat(32))
        assertIs<ItemOutcome.Blocked>(outcome)
        assertEquals(FailureType.ACCESS_DENIED, outcome.failure.type)
        assertEquals(SyncState.QUEUED, store.rows.getValue("a".repeat(32)).state)
    }

    @Test
    fun `tras agotar reintentos pasa a FAILED`() = runTest {
        queue()
        val e = engine(maxAttempts = 2)
        repeat(2) { remote.failures += RemoteFailure(FailureType.SERVER, "HTTP 503") }
        assertIs<ItemOutcome.Deferred>(e.process("a".repeat(32)))
        assertIs<ItemOutcome.Failed>(e.process("a".repeat(32)))
        assertEquals(SyncState.FAILED, store.rows.getValue("a".repeat(32)).state)
    }

    @Test
    fun `archivo local desaparecido es un fallo permanente de ese elemento`() = runTest {
        store.rows["a".repeat(32)] = media(size = 10)
        val o = engine().process("a".repeat(32))
        assertEquals(ItemOutcome.Failed(ErrorKind.SOURCE_MISSING, "El archivo local ya no existe"), o)
    }

    @Test
    fun `cancelar la subida devuelve el elemento a la cola`() = runTest {
        queue()
        val reached = CompletableDeferred<Unit>()
        remote.onPut = { reached.complete(Unit); kotlinx.coroutines.awaitCancellation() }
        val job = launch { engine().process("a".repeat(32)) }
        reached.await()
        assertEquals(SyncState.UPLOADING, store.rows.getValue("a".repeat(32)).state)
        job.cancel()
        job.join()
        assertEquals(SyncState.QUEUED, store.rows.getValue("a".repeat(32)).state)
        assertTrue(remote.objects.isEmpty())
    }

    @Test
    fun `tras un reinicio del proceso los elementos a medias vuelven a la cola y se completan`() = runTest {
        queue()
        // el proceso murió con el elemento en UPLOADING
        store.rows["a".repeat(32)] = store.rows.getValue("a".repeat(32)).copy(state = SyncState.UPLOADING, attempts = 1)
        val summary = BackupRunner(store, engine(), concurrency = 2).run()
        assertEquals(1, summary.uploaded)
        assertEquals(SyncState.BACKED_UP, store.rows.getValue("a".repeat(32)).state)
    }

    @Test
    fun `una subida interrumpida tras el PUT se detecta en el siguiente intento sin transferir de nuevo`() = runTest {
        queue()
        assertIs<ItemOutcome.BackedUp>(engine().process("a".repeat(32)))
        val item = store.rows.getValue("a".repeat(32))
        // simulamos: el PUT terminó pero el proceso murió antes de registrar BACKED_UP
        store.rows["a".repeat(32)] = item.copy(state = SyncState.VERIFYING, backedUpVersionId = null, remoteKey = null)
        store.recoverInterrupted()
        val puts = remote.count("put:")
        assertEquals(ItemOutcome.BackedUp(false), engine().process("a".repeat(32)))
        assertEquals(puts, remote.count("put:"))
    }

    @Test
    fun `el runner no reintenta en bucle y respeta bloqueos globales`() = runTest {
        queue("a".repeat(32), "content://media/1")
        queue("b".repeat(32), "content://media/2", ByteArray(3000) { 7 })
        remote.failures += RemoteFailure(FailureType.ACCESS_DENIED)
        val summary = BackupRunner(store, engine(), concurrency = 1).run()
        assertNotNull(summary.blocked)
        assertEquals(0, summary.uploaded)
        assertEquals(2, store.queued(10).size, "el trabajo pendiente se conserva")
    }

    @Test
    fun `el runner con errores temporales pide reintento con backoff`() = runTest {
        queue()
        remote.failures += RemoteFailure(FailureType.NETWORK)
        val summary = BackupRunner(store, engine(), concurrency = 2).run()
        assertTrue(summary.shouldRetry)
        assertEquals(1, remote.calls.count { it.startsWith("head:") }.coerceAtLeast(1))
    }

    @Test
    fun `respeta el limite de concurrencia`() = runTest {
        for (i in 1..6) queue(i.toString().padStart(32, '0'), "content://media/$i", ByteArray(100 + i) { i.toByte() })
        var running = 0; var maxRunning = 0
        remote.onPut = {
            synchronized(this) { running++; maxRunning = maxOf(maxRunning, running) }
            kotlinx.coroutines.yield()
            synchronized(this) { running-- }
        }
        val summary = BackupRunner(store, engine(), concurrency = 2).run()
        assertEquals(6, summary.uploaded)
        assertTrue(maxRunning <= 2, "maxRunning=$maxRunning")
    }

    @Test
    fun `aislamiento de prefijo - ninguna operacion sale del prefijo configurado`() = runTest {
        val scoped = ScopedObjectStore(remote, keys)
        assertFailsWith<IllegalArgumentException> { scoped.head("otro/photos/x") }
        assertFailsWith<IllegalArgumentException> { scoped.head("backup/../secreto") }
        assertFailsWith<IllegalArgumentException> { scoped.list("otro/", null, 10) }
        assertFailsWith<IllegalArgumentException> { scoped.list("", null, 10) }
        assertTrue(remote.calls.isEmpty(), "no debe salir ninguna solicitud")
        scoped.list(keys.photosRoot, null, 10) // dentro del prefijo: permitido
    }

    @Test
    fun `la interfaz de almacenamiento remoto no ofrece borrado`() {
        val names = app.s3vault.data.remote.ObjectStore::class.java.methods.map { it.name.lowercase() }
        assertTrue(names.none { it.contains("delete") || it.contains("remove") })
    }
}
