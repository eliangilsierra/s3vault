package com.s3vault.app

import android.os.Debug
import android.util.Log
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.s3vault.app.data.local.AppDatabase
import com.s3vault.app.data.local.MediaIndexer
import com.s3vault.app.data.local.PlatformMediaDelete
import com.s3vault.app.data.local.ScannedMedia
import com.s3vault.app.domain.AuthMethod
import com.s3vault.app.domain.MediaKind
import com.s3vault.app.domain.RemoteKeys
import com.s3vault.app.domain.S3Config
import com.s3vault.app.sync.DeleteEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The costs behind a bulk action, one at a time and without UI noise: what a 30/100/300 photo selection makes the app do
 * when it asks Android for permission to delete, when it settles the index afterwards, when it queues a backup, when it
 * reconciles a library with MediaStore and when it deletes objects from S3. Everything is logged under `BulkPerf`.
 */
class BulkOperationsBenchTest {
    @get:Rule val perms: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext
    private val container get() = (ctx.applicationContext as S3VaultApp).container
    private val prefix = "bo_"

    @Before fun setUp() { runBlocking(Dispatchers.IO) { BulkPerfSupport.deletePhotos(prefix); container.db.clearAllTables() } }

    @After fun tearDown() {
        runBlocking(Dispatchers.IO) {
            BulkPerfSupport.deletePhotos(prefix)
            container.db.clearAllTables()
            container.settings.clearS3Config()
            container.credentials.clear()
        }
        container.endpointOverride = null
    }

    private fun log(s: String) = Log.i("BulkPerf", s)

    private fun allocatedBytes() = Debug.getRuntimeStat("art.gc.bytes-allocated").toLongOrNull() ?: 0L

    /** The system delete request is built with a synchronous call to MediaProvider; where it runs decides whether the UI freezes. */
    @Test fun creatingTheSystemDeleteRequestOnTheMainThread() {
        val uris = BulkPerfSupport.insertPhotos(prefix, 300)
        for (n in listOf(30, 100, 300)) {
            var ms = 0L
            instrumentation.runOnMainSync {
                val t0 = System.nanoTime()
                PlatformMediaDelete.batchRequest(ctx, uris.take(n))
                ms = (System.nanoTime() - t0) / 1_000_000
            }
            log("createDeleteRequest n=$n on the MAIN thread: $ms ms")
        }
    }

    /** What happens after Android deleted the files: one existence check per file, then one transaction. */
    @Test fun settlingTheIndexAfterALocalDeletion(): Unit = runBlocking(Dispatchers.IO) {
        val uris = BulkPerfSupport.insertPhotos(prefix, 300)
        container.library.refresh()
        val rows = container.db.media().getEverything().filter { it.displayName.startsWith(prefix) }
        assertEquals(300, rows.size)
        for (n in listOf(30, 100, 300)) {
            val entries = rows.take(n).map { DeleteEntry(it.displayName, it.mediaStoreId, it.uri, it.remoteKey, true) }
            // Nothing deleted yet: pure cost of verifying that files still exist.
            val t0 = System.nanoTime()
            val none = container.localDeletion.settle(entries)
            val verify = (System.nanoTime() - t0) / 1_000_000
            assertEquals(0, none.deleted)
            log("settle n=$n (all files still there): $verify ms")
        }
        for (n in listOf(30, 100, 300)) {
            val gone = rows.drop(300 - n).also { r -> r.forEach { runCatching { ctx.contentResolver.delete(android.net.Uri.parse(it.uri), null, null) } } }
            val entries = gone.map { DeleteEntry(it.displayName, it.mediaStoreId, it.uri, it.remoteKey, true) }
            val t0 = System.nanoTime()
            val r = container.localDeletion.settle(entries)
            log("settle n=$n (files gone): ${(System.nanoTime() - t0) / 1_000_000} ms, deleted=${r.deleted}")
            break
        }
    }

    /** "Respaldar" on a selection: one write per photo today. */
    @Test fun queueingASelectionForBackup(): Unit = runBlocking(Dispatchers.IO) {
        BulkPerfSupport.insertPhotos(prefix, 300)
        container.library.refresh()
        val ids = container.db.media().getEverything().filter { it.displayName.startsWith(prefix) }.map { it.mediaStoreId }
        for (n in listOf(30, 100, 300)) {
            container.db.media().dequeueAll()
            val emissions = AtomicInteger()
            val watcher = container.db.media().observeCounts().onEach { emissions.incrementAndGet() }.launchIn(kotlinx.coroutines.CoroutineScope(Dispatchers.IO))
            kotlinx.coroutines.delay(300)
            val before = emissions.get()
            val t0 = System.nanoTime()
            val queued = container.backup.enqueue(ids.take(n))
            val ms = (System.nanoTime() - t0) / 1_000_000
            kotlinx.coroutines.delay(500)
            watcher.cancel()
            log("enqueue n=$n: $ms ms, queued=$queued, observers re-queried ${emissions.get() - before} times")
        }
        container.scheduler.cancelBackup()
    }

    /** What every ON_RESUME costs: reading the whole table and diffing it against a MediaStore scan. */
    @Test fun reconcilingALargeLibrary(): Unit = runBlocking(Dispatchers.IO) {
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        try {
            val scan = (0 until 20_000).map { i ->
                ScannedMedia(
                    i + 1L, "content://m/$i", MediaKind.IMAGE, "IMG_$i.jpg", "image/jpeg", 1000L + i, 1_700_000_000L + i, 1_700_000_000_000L + i * 1000L,
                    10, 10, "Camera",
                )
            }
            val indexer = MediaIndexer(db)
            val first = measure { indexer.reconcile(scan, fullAccess = true) }
            log("reconcile 20000 new rows: ${first.first} ms, allocated ${first.second / 1024} KiB")
            val again = measure { indexer.reconcile(scan, fullAccess = true) }
            log("reconcile 20000 unchanged rows (what every resume does): ${again.first} ms, allocated ${again.second / 1024} KiB")
        } finally {
            db.close()
        }
    }

    private inline fun <T> measure(block: () -> T): Pair<Long, Long> {
        val a0 = allocatedBytes()
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / 1_000_000 to (allocatedBytes() - a0)
    }

    /** Cold-cache cost of one grid thumbnail from a camera-sized original: Coil's own decode versus the platform's thumbnail cache. */
    @Test fun gridThumbnailDecodeCost(): Unit = runBlocking(Dispatchers.IO) {
        val uris = (1..8).map { BulkPerfSupport.insertLargePhoto("${prefix}big$it.jpg") }
        val loader = coil3.SingletonImageLoader.get(ctx)
        val px = 350
        fun coil(uri: android.net.Uri): Long {
            val req = coil3.request.ImageRequest.Builder(ctx).data(uri.toString()).size(px, px)
                .memoryCachePolicy(coil3.request.CachePolicy.DISABLED).diskCachePolicy(coil3.request.CachePolicy.DISABLED).build()
            val t0 = System.nanoTime()
            val r = runBlocking { loader.execute(req) }
            assertTrue(r.toString(), r is coil3.request.SuccessResult)
            return (System.nanoTime() - t0) / 1_000_000
        }
        fun platform(uri: android.net.Uri): Long {
            val t0 = System.nanoTime()
            ctx.contentResolver.loadThumbnail(uri, android.util.Size(px, px), null).recycle()
            return (System.nanoTime() - t0) / 1_000_000
        }
        coil(uris[0]); platform(uris[0]) // warm up code paths, not the thumbnail cache of the others
        val c = uris.drop(1).map { coil(it) }
        val pl = uris.drop(1).map { platform(it) }
        val pl2 = uris.drop(1).map { platform(it) }
        log("grid thumbnail 4000x3000 -> ${px}px, cold: coil median=${c.sorted()[c.size / 2]} ms (all=$c), platform first=${pl.sorted()[pl.size / 2]} ms, platform cached=${pl2.sorted()[pl2.size / 2]} ms")
    }

    /** DeleteObjects against the in-memory S3: requests sent, time, and that each object is accounted for. */
    @Test fun deletingManyObjectsFromS3() = runBlocking(Dispatchers.IO) {
        val s3 = FakeS3Dispatcher("test-bucket", allowedPrefix = "bo/")
        val server = MockWebServer().also { it.dispatcher = s3; it.start() }
        try {
            container.credentials.saveStatic("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
            container.settings.saveS3Config(S3Config("eu-west-1", "test-bucket", "bo/", AuthMethod.STATIC))
            container.endpointOverride = server.url("/").toString().trimEnd('/')
            val keys = RemoteKeys("bo/")
            for (n in listOf(30, 300, 2500)) {
                val names = (0 until n).map { keys.photoKey("%032x".format(it), "%064x".format(it), "IMG_$it.jpg") }
                names.forEach { s3.objects[it] = FakeS3Dispatcher.Obj(ByteArray(8), "x", "image/jpeg", emptyMap()) }
                s3.deleteBatches.clear()
                val progress = ArrayList<Int>()
                val t0 = System.nanoTime()
                val r = container.cloudDeletion.delete(names) { done, _ -> progress += done }
                val ms = (System.nanoTime() - t0) / 1_000_000
                log("cloud delete n=$n: $ms ms, requests=${s3.deleteBatches.size}, deleted=${r.deleted.size}, failed=${r.failed.size}, progress updates=${progress.size}")
                assertTrue(s3.objects.isEmpty() || s3.objects.size < n)
            }
        } finally {
            server.close()
        }
    }
}
