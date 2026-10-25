package com.s3vault.app.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.s3vault.app.data.local.AppDatabase
import com.s3vault.app.data.local.MediaIndexer
import com.s3vault.app.data.local.ScannedMedia
import com.s3vault.app.domain.AwsCredentials
import com.s3vault.app.domain.AwsCredentialsProvider
import com.s3vault.app.domain.DeleteReport
import com.s3vault.app.domain.LocalFileReader
import com.s3vault.app.domain.MediaKind
import com.s3vault.app.domain.PagedListing
import com.s3vault.app.domain.PermissionReport
import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
import com.s3vault.app.domain.RemoteKeys
import com.s3vault.app.domain.RemoteListing
import com.s3vault.app.domain.RemoteObjectMetadata
import com.s3vault.app.domain.RemoteStorage
import com.s3vault.app.domain.UploadRequest
import com.s3vault.app.domain.UploadResult
import com.s3vault.app.settings.BackupSettings
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

class FakeReader : LocalFileReader {
    val files = mutableMapOf<String, ByteArray>()
    /** Every file opened, in order: tests prove what was (not) read. */
    val opens: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())
    override fun open(uri: String): InputStream {
        opens += uri
        val b = files[uri] ?: throw java.io.FileNotFoundException(uri)
        return ByteArrayInputStream(b)
    }
}

class FakeCredentials : AwsCredentialsProvider {
    var failure: RemoteException? = null
    override val canRenewAutomatically = false
    override suspend fun getCredentials(): AwsCredentials {
        failure?.let { throw it }
        return AwsCredentials("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", null, null)
    }
}

/** In-memory S3 stand-in with failure injection. [deleteCalls] records every delete so tests can prove what was (not) deleted. */
class FakeRemote(val keys: RemoteKeys) : RemoteStorage {
    class Obj(val bytes: ByteArray, val sha256B64: String, val etag: String, val storageClass: String = "STANDARD")

    val objects = linkedMapOf<String, Obj>()
    val uploadedKeys = mutableListOf<String>()
    var uploadCalls = 0
    var uploadFailures = ArrayDeque<Throwable>()
    var headChecksumOverride: String? = null
    val headFailures = ArrayDeque<RemoteException>()
    var uploadGate: (suspend () -> Unit)? = null
    var listFailure: RemoteException? = null
    val downloadOffsets = mutableListOf<Long>()
    var corruptDownload = false
    var downloadFailureAfterBytes: Int? = null

    /** Every key passed to delete(), in order, and the size of each request. */
    val deleteCalls = mutableListOf<List<String>>()
    val deletedKeys: List<String> get() = deleteCalls.flatten()
    val deleteKeyFailures = mutableMapOf<String, RemoteErrorKind>()
    val deleteRequestFailures = ArrayDeque<RemoteException>()

    /** Runs inside every delete request, after it was recorded (a test can cancel, slow down or change what S3 will answer). */
    var onDelete: suspend () -> Unit = {}

    /** Mimics S3 with a prefix-scoped ListBucket policy: a missing key answers 403 instead of 404. */
    var missingKeysAnswerForbidden = false
    val readFailures = ArrayDeque<RemoteException>()
    val readRequests = mutableListOf<Pair<String, Long?>>()
    val putThumbnailFailures = ArrayDeque<RemoteException>()
    var putThumbnailCalls = 0

    override suspend fun checkConnection() {}
    override suspend fun testPermissions() = PermissionReport(emptyList())

    override suspend fun listPhotos(): List<RemoteListing> {
        listFailure?.let { throw it }
        // Like S3: everything under photos/, recognised photo keys or not.
        return PagedListing(
            objects.filterKeys { it.startsWith(keys.photosPrefix) }.map { (k, o) -> RemoteListing(k, o.bytes.size.toLong(), uploadTimes[k] ?: 1_000L, o.storageClass, o.etag) },
            requests = 1,
        )
    }

    /** Upload time reported per key (defaults to 1000). */
    val uploadTimes = mutableMapOf<String, Long>()
    var listPageSize = 1000
    var pagesDelivered = 0
    /** When set, the listing fails after this many pages (the earlier pages were delivered). */
    var failListingAfterPages: Int? = null
    var listCalls = 0

    override suspend fun listPhotosPaged(onPage: suspend (List<RemoteListing>) -> Unit) {
        listCalls++
        listFailure?.let { throw it }
        val all = listPhotos()
        var delivered = 0
        all.chunked(listPageSize.coerceAtLeast(1)).ifEmpty { listOf(emptyList()) }.forEach { page ->
            if (failListingAfterPages != null && delivered >= failListingAfterPages!!) throw RemoteException(RemoteErrorKind.NETWORK)
            onPage(page); delivered++; pagesDelivered++
        }
    }

    /** User metadata returned by head() per key (for example captured-at-ms). */
    val metadata = mutableMapOf<String, Map<String, String>>()
    var headCalls = 0
    /** Runs inside every head(): a test can hold a request (to cancel it) or slow it. */
    var headGate: (suspend () -> Unit)? = null

    override suspend fun head(key: String): RemoteObjectMetadata? {
        require(keys.isWithinPrefix(key))
        headCalls++
        headGate?.invoke()
        headFailures.removeFirstOrNull()?.let { throw it }
        val o = objects[key] ?: return null
        return RemoteObjectMetadata(key, o.bytes.size.toLong(), 1_000L, o.etag, headChecksumOverride ?: o.sha256B64, "image/jpeg", metadata[key] ?: emptyMap())
    }

    override suspend fun upload(request: UploadRequest, onProgress: (Long) -> Unit): UploadResult {
        require(keys.isWithinPrefix(request.key)) { "outside prefix" }
        uploadCalls++
        uploadGate?.invoke()
        uploadFailures.removeFirstOrNull()?.let { throw it }
        val bytes = request.openStream().use { it.readBytes() }
        onProgress(bytes.size.toLong())
        val actual = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
        if (actual != request.sha256Base64) throw RemoteException(RemoteErrorKind.INTEGRITY_MISMATCH, detail = "BadDigest")
        objects[request.key] = Obj(bytes, actual, "etag-${objects.size}")
        uploadedKeys += request.key
        return UploadResult("etag", actual)
    }

    override suspend fun readBytes(key: String, maxBytes: Long?): ByteArray? {
        require(keys.isWithinPrefix(key))
        readRequests += key to maxBytes
        readFailures.removeFirstOrNull()?.let { throw it }
        val o = objects[key]
        if (o == null) {
            if (missingKeysAnswerForbidden) throw RemoteException(RemoteErrorKind.ACCESS_DENIED)
            return null
        }
        return if (maxBytes == null) o.bytes else o.bytes.copyOf(minOf(maxBytes.toInt(), o.bytes.size))
    }

    override suspend fun putThumbnail(key: String, bytes: ByteArray) {
        require(keys.isThumbnailKey(key)) { "not a thumbnail key" }
        putThumbnailCalls++
        putThumbnailFailures.removeFirstOrNull()?.let { throw it }
        objects[key] = Obj(bytes, "", "etag-thumb-${objects.size}")
    }

    override suspend fun delete(keys: List<String>): DeleteReport {
        require(keys.size <= com.s3vault.app.domain.RemoteStorage.MAX_DELETE_BATCH)
        keys.forEach { require(this.keys.isWithinPrefix(it)) }
        deleteCalls += keys
        onDelete()
        deleteRequestFailures.removeFirstOrNull()?.let { throw it }
        val failed = keys.filter { it in deleteKeyFailures }.associateWith { deleteKeyFailures.getValue(it) }
        val ok = keys.filter { it !in failed }
        ok.forEach { objects.remove(it) } // deleting a missing key is a success, as in S3
        return DeleteReport(ok, failed)
    }

    override suspend fun download(key: String, sink: OutputStream, offset: Long, ifMatchEtag: String?, onProgress: (Long) -> Unit): Long {
        require(keys.isWithinPrefix(key))
        downloadOffsets += offset
        val o = objects[key] ?: throw RemoteException(RemoteErrorKind.OBJECT_NOT_FOUND)
        var data = o.bytes.copyOfRange(offset.toInt(), o.bytes.size)
        if (corruptDownload) data = data.copyOf().also { if (it.isNotEmpty()) it[0] = (it[0] + 1).toByte() }
        val limit = downloadFailureAfterBytes
        if (limit != null) {
            sink.write(data, 0, minOf(limit, data.size))
            downloadFailureAfterBytes = null
            throw RemoteException(RemoteErrorKind.NETWORK)
        }
        sink.write(data)
        onProgress(offset + data.size)
        return data.size.toLong()
    }
}

class FakeScheduler : WorkScheduler {
    var backupKicks = 0
    var backupCancels = 0
    var restoreKicks = 0
    var restoreCancels = 0
    var lastAuto: BackupSettings? = null
    override fun kickBackup(wifiOnly: Boolean) { backupKicks++ }
    override fun cancelBackup() { backupCancels++ }
    override fun configureAuto(settings: BackupSettings) { lastAuto = settings }
    override fun kickRestore(wifiOnly: Boolean) { restoreKicks++ }
    override fun cancelRestore() { restoreCancels++ }
    var datesKicks = 0
    var datesCancels = 0
    override fun kickCloudDates(wifiOnly: Boolean) { datesKicks++ }
    override fun cancelCloudDates() { datesCancels++ }
    val semanticKicks = mutableListOf<Boolean>()
    var semanticCancels = 0
    var semanticSchedule: Boolean? = null
    var modelKicks = 0
    var modelCancels = 0
    override fun kickSemanticIndex(onlyCharging: Boolean, continuation: Boolean) { semanticKicks += onlyCharging }
    override fun cancelSemanticIndex() { semanticCancels++ }
    override fun configureSemanticSchedule(enabled: Boolean, onlyCharging: Boolean) { semanticSchedule = enabled }
    override fun kickModelDownload(wifiOnly: Boolean) { modelKicks++ }
    override fun cancelModelDownload() { modelCancels++ }
    var duplicateKicks = 0
    var duplicateCancels = 0
    override fun kickDuplicateScan(continuation: Boolean) { duplicateKicks++ }
    override fun cancelDuplicateScan() { duplicateCancels++ }
    var auditKicks = mutableListOf<Boolean>()
    var auditCancels = 0
    var auditDays: Int? = null
    override fun kickAudit(deep: Boolean, wifiOnly: Boolean, reuseListing: Boolean) { auditKicks += deep }
    override fun cancelAudit() { auditCancels++ }
    override fun configureAuditSchedule(days: Int, wifiOnly: Boolean) { auditDays = days }
}

class FakeSink : RestoreSink {
    class Written(val name: String, val mime: String, val bytes: java.io.ByteArrayOutputStream, var committed: Boolean = false, var aborted: Boolean = false)

    val results = mutableListOf<Written>()
    override suspend fun create(displayName: String, mimeType: String): RestoreSink.Target {
        val w = Written(displayName, mimeType, java.io.ByteArrayOutputStream())
        results += w
        return object : RestoreSink.Target {
            override val output: OutputStream = w.bytes
            override suspend fun commit(): String { w.committed = true; return "content://restored/${results.size}" }
            override suspend fun abort() { w.aborted = true }
        }
    }
}

class TestEnv(val prefix: String = "mi/prefijo/") {
    val context: Context = ApplicationProvider.getApplicationContext()
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    val keys = RemoteKeys(prefix)
    val remote = FakeRemote(keys)
    val reader = FakeReader()
    val creds = FakeCredentials()
    val progress = TransferProgress()
    val events = EventLog(db)
    var catalog = RemoteCatalog(db)
    var now = 1_000_000L
    private var idCounter = 0
    val indexer = MediaIndexer(db, idGenerator = { "%032x".format(++idCounter) })
    val ctx = RemoteContext(remote, keys)
    var provider: RemoteContextProvider = RemoteContextProvider { ctx }

    fun engine(
        maxAttempts: Int = BackupEngine.DEFAULT_MAX_ATTEMPTS,
        thumbnails: com.s3vault.app.domain.ThumbnailMaker? = null,
        previewsEnabled: suspend () -> Boolean = { true },
    ) = BackupEngine(
        db, provider, creds, reader, progress, events, catalog, nowMs = { now }, maxAttempts = maxAttempts,
        thumbnails = thumbnails, previewsEnabled = previewsEnabled,
    )

    /** [batchSize] is small in tests that need several batches; retries do not wait (the pause is what is replaced). */
    fun cloudDeletion(
        onVersionsDeleted: (Collection<String>) -> Unit = {},
        batchSize: Int = com.s3vault.app.domain.RemoteStorage.MAX_DELETE_BATCH,
        pauses: MutableList<Int>? = null,
    ) = CloudDeletionController(db, provider, onVersionsDeleted, events, batchSize) { attempt -> pauses?.add(attempt) }

    fun restoreEngine(sink: RestoreSink, partDir: File) =
        RestoreEngine(db, provider, creds, sink, partDir, progress, events)

    fun scanned(
        id: Long, name: String = "IMG_$id.jpg", bytes: ByteArray = "photo-$id".toByteArray(), mtime: Long = 100, kind: MediaKind = MediaKind.IMAGE,
    ): ScannedMedia {
        reader.files["content://media/$id"] = bytes
        return ScannedMedia(id, "content://media/$id", kind, name, "image/jpeg", bytes.size.toLong(), mtime, 5_000, 640, 480, "Camera")
    }

    suspend fun index(vararg items: ScannedMedia, fullAccess: Boolean = true) = indexer.reconcile(items.toList(), fullAccess, now)

    suspend fun enqueue(vararg ids: Long) = ids.forEach { db.media().enqueue(it, now) }

    fun close() = db.close()
}
