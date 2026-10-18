package app.s3vault.pure

import app.s3vault.data.remote.DownloadResult
import app.s3vault.data.remote.ObjectStore
import app.s3vault.data.remote.RemoteHead
import app.s3vault.data.remote.RemoteObjectInfo
import app.s3vault.data.remote.RemotePage
import app.s3vault.data.remote.UploadSource
import app.s3vault.domain.model.ErrorKind
import app.s3vault.domain.model.MediaItem
import app.s3vault.domain.model.SyncState
import app.s3vault.domain.remote.FailureType
import app.s3vault.domain.remote.RemoteException
import app.s3vault.domain.remote.RemoteFailure
import app.s3vault.domain.repo.BackupStore
import app.s3vault.domain.repo.MediaSource
import app.s3vault.domain.repo.MediaStat
import app.s3vault.domain.repo.PublishedMedia
import app.s3vault.domain.repo.RemoteIndex
import app.s3vault.domain.repo.RestoreDestination
import app.s3vault.domain.repo.TransferKind
import app.s3vault.domain.repo.TransferLog
import app.s3vault.sync.Hashing
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

class MutableClock(var now: Instant) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?) = this
    override fun instant(): Instant = now
    fun advance(seconds: Long) { now = now.plusSeconds(seconds) }
}

fun sha256Hex(bytes: ByteArray) = Hashing.sha256(ByteArrayInputStream(bytes)).sha256Hex

/** Simula S3 en memoria, incluida la validación del checksum de un PUT, sin borrado. */
class FakeObjectStore : ObjectStore {
    class Stored(val bytes: ByteArray, val contentType: String, val metadata: Map<String, String>, val sha256Base64: String, val etag: String)

    val objects = ConcurrentHashMap<String, Stored>()
    val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
    val failures = ArrayDeque<RemoteFailure>()
    var onPut: (suspend (String) -> Unit)? = null
    var corruptOnPut = false
    var dropChecksumOnHead = false
    var rangeRequests = mutableListOf<Pair<Long, String?>>()
    var etagCounter = 0

    private fun maybeFail() {
        synchronized(failures) { failures.removeFirstOrNull() }?.let { throw RemoteException(it) }
    }

    fun putDirect(key: String, bytes: ByteArray, metadata: Map<String, String> = emptyMap()) {
        val hash = Hashing.sha256(ByteArrayInputStream(bytes))
        objects[key] = Stored(bytes, "image/jpeg", metadata, hash.sha256Base64, "\"etag-${++etagCounter}\"")
    }

    override suspend fun list(prefix: String, continuationToken: String?, maxKeys: Int): RemotePage {
        calls += "list:$prefix"; maybeFail()
        val keys = objects.keys.filter { it.startsWith(prefix) }.sorted()
        val start = continuationToken?.toInt() ?: 0
        val slice = keys.drop(start).take(maxKeys)
        val next = if (start + slice.size < keys.size) (start + slice.size).toString() else null
        return RemotePage(slice.map { RemoteObjectInfo(it, objects[it]!!.bytes.size.toLong(), 1_000L, objects[it]!!.etag) }, next)
    }

    override suspend fun head(key: String): RemoteHead? {
        calls += "head:$key"; maybeFail()
        val o = objects[key] ?: return null
        return RemoteHead(key, o.bytes.size.toLong(), 1_000L, o.etag, if (dropChecksumOnHead) null else o.sha256Base64,
            if (dropChecksumOnHead) emptyMap() else o.metadata, o.contentType)
    }

    override suspend fun put(key: String, source: UploadSource, contentType: String, metadata: Map<String, String>, sha256Base64: String, onProgress: (Long) -> Unit) {
        calls += "put:$key"; maybeFail()
        onPut?.invoke(key)
        val bytes = source.open().use { it.readBytes() }
        val actual = Hashing.sha256(ByteArrayInputStream(bytes)).sha256Base64
        if (corruptOnPut || actual != sha256Base64) {
            throw RemoteException(RemoteFailure(FailureType.CHECKSUM_MISMATCH, "BadDigest"))
        }
        onProgress(bytes.size.toLong())
        objects[key] = Stored(bytes, contentType, metadata, sha256Base64, "\"etag-${++etagCounter}\"")
    }

    override suspend fun get(key: String, sink: OutputStream, offset: Long, ifMatchEtag: String?, onProgress: (Long) -> Unit): DownloadResult {
        calls += "get:$key"; maybeFail()
        rangeRequests += offset to ifMatchEtag
        val o = objects[key] ?: throw RemoteException(RemoteFailure(FailureType.OBJECT_NOT_FOUND))
        if (ifMatchEtag != null && ifMatchEtag != o.etag) throw RemoteException(RemoteFailure(FailureType.OBJECT_CHANGED))
        val part = o.bytes.copyOfRange(offset.toInt(), o.bytes.size)
        sink.write(part)
        onProgress(part.size.toLong())
        return DownloadResult(part.size.toLong(), o.bytes.size.toLong(), o.etag)
    }

    override fun close() = Unit
    fun count(prefix: String) = calls.count { it.startsWith(prefix) }
}

class FakeBackupStore : BackupStore {
    val rows = ConcurrentHashMap<String, MediaItem>()
    override suspend fun get(assetId: String) = rows[assetId]
    override suspend fun queued(limit: Int) = rows.values.filter { it.state == SyncState.QUEUED }.sortedBy { it.dateModifiedMs }.take(limit)
    override suspend fun compareAndSet(expected: SyncState, updated: MediaItem): Boolean = synchronized(rows) {
        val cur = rows[updated.assetId] ?: return@synchronized false
        if (cur.state != expected) return@synchronized false
        rows[updated.assetId] = updated
        true
    }
    override suspend fun recoverInterrupted(): Int {
        var n = 0
        rows.replaceAll { _, v -> if (v.state == SyncState.UPLOADING || v.state == SyncState.VERIFYING) { n++; v.copy(state = SyncState.QUEUED) } else v }
        return n
    }
    override suspend fun upsert(item: MediaItem) { rows[item.assetId] = item }
}

class FakeMediaSource : MediaSource {
    val files = ConcurrentHashMap<String, Pair<ByteArray, Long>>() // uri -> bytes, modified
    var openCount = 0
    override suspend fun stat(item: app.s3vault.domain.model.MediaItem): MediaStat? =
        item.uri?.let { files[it] }?.let { MediaStat(it.first.size.toLong(), it.second) }
    override fun open(item: app.s3vault.domain.model.MediaItem): InputStream {
        openCount++
        return ByteArrayInputStream(files[item.uri]?.first ?: throw java.io.FileNotFoundException(item.uri))
    }
}

class FakeIndex : RemoteIndex {
    val byVersion = ConcurrentHashMap<String, String>()
    override suspend fun findKeyByVersion(versionId: String) = byVersion[versionId]
    override suspend fun remember(key: String, sizeBytes: Long, lastModifiedMs: Long?, etag: String?) {
        key.split('/').getOrNull(key.split('/').size - 2)?.let { byVersion[it] = key }
    }
}

class FakeLog : TransferLog {
    data class Entry(val assetId: String?, val kind: TransferKind, val success: Boolean, val errorKind: ErrorKind?, val message: String?)
    val entries = java.util.Collections.synchronizedList(mutableListOf<Entry>())
    override suspend fun record(assetId: String?, kind: TransferKind, success: Boolean, errorKind: ErrorKind?, message: String?) {
        entries += Entry(assetId, kind, success, errorKind, message)
    }
}

/** Destino que nunca sobrescribe: si el nombre existe añade " (n)". */
class FakeDestination : RestoreDestination {
    val taken = mutableSetOf<String>()
    val published = mutableListOf<Pair<String, ByteArray>>()
    override suspend fun publish(file: File, displayName: String, mimeType: String, capturedAtMs: Long?): PublishedMedia {
        var name = displayName; var n = 1
        while (name in taken) { name = displayName.substringBeforeLast('.') + " ($n)." + displayName.substringAfterLast('.'); n++ }
        taken += name
        val bytes = file.readBytes()
        published += name to bytes
        return PublishedMedia("content://media/restored/$name", name, bytes.size.toLong(), 5_000L)
    }
}

fun media(
    id: String = "a".repeat(32),
    uri: String = "content://media/1",
    name: String = "IMG_0001.jpg",
    size: Long = 0, modified: Long = 100L, state: SyncState = SyncState.QUEUED,
) = MediaItem.newLocal(id, uri, name, "image/jpeg", size, modified, 1_700_000_000_000, 4000, 3000, false, "Camera").copy(state = state)
