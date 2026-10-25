package com.s3vault.app.sync

import com.s3vault.app.data.local.JobNames
import com.s3vault.app.data.local.JobState
import com.s3vault.app.data.local.MediaItemEntity
import com.s3vault.app.data.local.SemanticEmbeddingEntity
import com.s3vault.app.domain.MediaKind
import com.s3vault.app.domain.semantic.EmbedderProvider
import com.s3vault.app.domain.semantic.EmbeddingCodec
import com.s3vault.app.domain.semantic.ModelPack
import com.s3vault.app.domain.semantic.ModelPackStore
import com.s3vault.app.domain.semantic.PackFile
import com.s3vault.app.domain.semantic.PackStatus
import com.s3vault.app.domain.semantic.TextImageEmbedder
import com.s3vault.app.domain.semantic.TopK
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt

/** A stand-in model: vectors come from a table, so ranking, caching and failure handling are tested without a 150 MB model. */
class FakeEmbedder(val table: Map<String, FloatArray>, val texts: Map<String, FloatArray>, override val modelId: String = "fake-v1") : TextImageEmbedder {
    override val dimension = 8
    val embedded = java.util.Collections.synchronizedList(mutableListOf<String>())
    var closed = false
    var gate: (suspend () -> Unit)? = null
    override suspend fun embedText(text: String): FloatArray = EmbeddingCodec.normalize(texts.getValue(text.lowercase()))
    override suspend fun embedImage(uri: String): FloatArray? {
        gate?.invoke()
        embedded += uri
        return table[uri]?.let { EmbeddingCodec.normalize(it) }
    }
    override fun close() { closed = true }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SemanticTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var env: TestEnv

    @Before fun setUp() { env = TestEnv() }
    @After fun tearDown() { env.close() }

    private fun unit(i: Int, noise: Float = 0f) = FloatArray(8) { if (it == i) 1f else noise * ((it * 7 + i) % 5 - 2) / 10f }
    private val table = mutableMapOf<String, FloatArray>()
    private val texts = mapOf("cat" to unit(0), "dog" to unit(1), "beach" to unit(2), "receipt" to unit(3))
    private val library = LinkedHashMap<Long, com.s3vault.app.data.local.ScannedMedia>()

    private suspend fun add(id: Long, concept: Int?, mtime: Long = 100, taken: Long = id, kind: MediaKind = MediaKind.IMAGE) {
        library[id] = env.scanned(id, "IMG_$id.jpg", "photo-$id".toByteArray(), mtime, kind).copy(dateTakenMs = taken)
        env.indexer.reconcile(library.values.toList(), true, env.now)
        if (concept != null) table["content://media/$id"] = unit(concept, noise = 0.3f)
    }

    private fun indexer(e: FakeEmbedder) = SemanticIndexer(env.db, EmbedderProvider { e }, JobRecorder(env.db) { env.now }, nowMs = { env.now })

    // ------------------------------------------------------------------ vectors

    @Test fun `quantised vectors keep their direction and their ranking`() {
        val rnd = java.util.Random(3)
        val v = EmbeddingCodec.normalize(FloatArray(512) { rnd.nextGaussian().toFloat() })
        val q = EmbeddingCodec.quantize(v)
        assertEquals(512, q.bytes.size)
        val back = FloatArray(512) { q.bytes[it] * q.scale }
        val cos = back.indices.sumOf { (back[it] * v[it]).toDouble() } / sqrt(back.sumOf { (it * it).toDouble() })
        assertTrue("cosine after quantisation $cos", cos > 0.9998)
        val other = EmbeddingCodec.normalize(FloatArray(512) { rnd.nextGaussian().toFloat() })
        val exact = v.indices.sumOf { (v[it] * other[it]).toDouble() }.toFloat()
        assertTrue("dot error ${abs(exact - EmbeddingCodec.dot(other, q.bytes, q.scale))}", abs(exact - EmbeddingCodec.dot(other, q.bytes, q.scale)) < 0.01)
        assertEquals("a zero vector stays harmless", 0f, EmbeddingCodec.dot(other, EmbeddingCodec.quantize(FloatArray(512)).bytes, 1f), 0f)
    }

    @Test fun `top-k keeps the best hits whatever the order they arrive in`() {
        val top = TopK(3)
        listOf(1L to 0.1f, 2L to 0.9f, 3L to 0.5f, 4L to 0.7f, 5L to 0.2f, 6L to 0.95f).forEach { (id, s) -> top.offer(id, s) }
        assertEquals(listOf(6L, 2L, 4L), top.best().map { it.id })
    }

    // ------------------------------------------------------------------ indexing

    @Test fun `the index fills newest first, once per unchanged picture, with bounded runs`() = runBlocking {
        (1L..10L).forEach { add(it, concept = (it % 4).toInt(), taken = it * 1000) }
        val e = FakeEmbedder(table, texts)

        val first = indexer(e).run(maxItems = 4, batch = 2) as IndexOutcome.Done
        assertEquals(4, first.indexed)
        assertEquals(6, first.left)
        assertEquals("newest pictures first", listOf(10L, 9L, 8L, 7L), e.embedded.map { it.substringAfterLast('/').toLong() })
        assertTrue("the model's memory is released when the run ends", e.closed)

        val second = indexer(FakeEmbedder(table, texts)).run(maxItems = 100)
        assertEquals(6, (second as IndexOutcome.Done).indexed)
        val third = FakeEmbedder(table, texts)
        indexer(third).run()
        assertTrue("everything is cached: nothing is embedded again", third.embedded.isEmpty())
    }

    @Test fun `a changed picture is embedded again and an unreadable one is remembered, not retried`() = runBlocking {
        add(1, 0); add(2, null) // 2 cannot be decoded
        val e1 = FakeEmbedder(table, texts)
        val r = indexer(e1).run() as IndexOutcome.Done
        assertEquals(1, r.indexed); assertEquals(1, r.unreadable)
        val again = FakeEmbedder(table, texts)
        indexer(again).run()
        assertTrue("the unreadable picture is not retried until it changes", again.embedded.isEmpty())

        add(2, 2, mtime = 999) // edited: now decodable and different
        val e3 = FakeEmbedder(table, texts)
        assertEquals(1, (indexer(e3).run() as IndexOutcome.Done).indexed)
        assertEquals(listOf("content://media/2"), e3.embedded)
    }

    @Test fun `videos are not embedded and vectors of another model are dropped`() = runBlocking {
        add(1, 0); add(2, 1, kind = MediaKind.VIDEO)
        indexer(FakeEmbedder(table, texts, "model-a")).run()
        assertEquals(1, env.db.semantic().page("model-a", Long.MIN_VALUE, 10).size)
        indexer(FakeEmbedder(table, texts, "model-b")).run()
        assertTrue("old model's vectors are gone", env.db.semantic().page("model-a", Long.MIN_VALUE, 10).isEmpty())
        assertEquals(1, env.db.semantic().page("model-b", Long.MIN_VALUE, 10).size)
    }

    @Test fun `without the model nothing runs and nothing is invented`() = runBlocking {
        add(1, 0)
        val out = SemanticIndexer(env.db, EmbedderProvider { null }, JobRecorder(env.db) { env.now }).run()
        assertEquals(IndexOutcome.ModelMissing, out)
        assertEquals(0, env.db.semantic().page("fake-v1", Long.MIN_VALUE, 10).size)
        assertNull(env.db.jobs().get(JobNames.SEMANTIC))
    }

    private fun assertNull(x: Any?) = assertTrue(x == null)

    @Test fun `stopping the indexer keeps the pictures already done and the next run finishes`() = runBlocking {
        (1L..6L).forEach { add(it, 0) }
        val e = FakeEmbedder(table, texts)
        val entered = CompletableDeferred<Unit>()
        var n = 0
        e.gate = { if (++n == 3) { entered.complete(Unit); kotlinx.coroutines.awaitCancellation() } }
        val job = async(start = CoroutineStart.UNDISPATCHED) { indexer(e).run(batch = 2) }
        entered.await()
        job.cancelAndJoin()
        assertEquals(JobState.CANCELLED, env.db.jobs().get(JobNames.SEMANTIC)!!.state)
        assertTrue("the batch finished before the stop was stored", env.db.semantic().page("fake-v1", Long.MIN_VALUE, 10).size >= 2)
        assertTrue(e.closed)
        val rest = indexer(FakeEmbedder(table, texts)).run() as IndexOutcome.Done
        assertEquals(0, rest.left)
    }

    // ------------------------------------------------------------------ searching

    private suspend fun searchable(): Int = env.db.semantic().observeSearchable("fake-v1").first()

    @Test fun `a sentence finds the pictures of that concept first`() = runBlocking {
        add(1, 0); add(2, 1); add(3, 2); add(4, 0); add(5, 3)
        val e = FakeEmbedder(table, texts)
        indexer(e).run()
        val r = SemanticSearcher(env.db).search(e, "cat")
        assertEquals(setOf(1L, 4L), r.hits.take(2).map { it.first.mediaStoreId }.toSet())
        assertTrue(r.hits.zipWithNext().all { (a, b) -> a.second >= b.second })
        assertEquals(5, r.searchable)
        assertEquals(0, r.pending)
    }

    @Test fun `an incomplete index still searches what it has and says how much is missing`() = runBlocking {
        (1L..8L).forEach { add(it, (it % 4).toInt(), taken = it) }
        val e = FakeEmbedder(table, texts)
        indexer(e).run(maxItems = 3)
        val r = SemanticSearcher(env.db).search(e, "beach")
        assertEquals(3, r.searchable)
        assertEquals(5, r.pending)
    }

    @Test fun `deleted or edited pictures disappear from the results`() = runBlocking {
        add(1, 0); add(2, 0)
        val e = FakeEmbedder(table, texts)
        indexer(e).run()
        env.db.media().deleteLocalRows(listOf(1)); library.remove(1)
        assertEquals(listOf(2L), SemanticSearcher(env.db).search(e, "cat").hits.map { it.first.mediaStoreId })
        add(2, 0, mtime = 777) // edited: its vector no longer describes the file
        assertEquals(emptyList<Long>(), SemanticSearcher(env.db).search(e, "cat").hits.map { it.first.mediaStoreId })
    }

    @Test fun `search streams the library in pages and finds the best across all of them`() = runBlocking {
        val rows = (1L..5_000L).map { i ->
            MediaItemEntity(i, "content://media/$i", MediaKind.IMAGE, "IMG_$i.jpg", "image/jpeg", 10, 1, i, 10, 10, "Camera", "%032x".format(i))
        }
        env.db.media().insertAll(rows)
        val vectors = rows.map { r ->
            val v = EmbeddingCodec.normalize(if (r.mediaStoreId == 4_321L) unit(0) else unit(1 + (r.mediaStoreId % 5).toInt(), noise = 0.2f))
            val q = EmbeddingCodec.quantize(v)
            SemanticEmbeddingEntity(r.mediaStoreId, "fake-v1", 10, 1, true, q.scale, q.bytes, 0)
        }
        env.db.semantic().upsert(vectors)
        val r = SemanticSearcher(env.db).search(FakeEmbedder(table, texts), "cat", k = 5)
        assertEquals(4_321L, r.hits.first().first.mediaStoreId)
        assertEquals(5_000, r.searchable)
        assertEquals(5, r.hits.size)
    }

    @Test fun `removing every vector and rebuilding gives the same index`() = runBlocking {
        (1L..5L).forEach { add(it, (it % 3).toInt()) }
        val e = FakeEmbedder(table, texts)
        indexer(e).run()
        val before = env.db.semantic().page("fake-v1", Long.MIN_VALUE, 100).map { it.mediaStoreId to it.vector.toList() }
        env.db.semantic().clear()
        assertEquals(5, env.db.semantic().needIndexCount("fake-v1"))
        indexer(FakeEmbedder(table, texts)).run()
        assertEquals(before, env.db.semantic().page("fake-v1", Long.MIN_VALUE, 100).map { it.mediaStoreId to it.vector.toList() })
    }

    // ------------------------------------------------------------------ the model pack

    private fun packOf(vararg contents: Pair<String, ByteArray>): ModelPack = ModelPack(
        "test-pack", "Test", "MIT", "test", 8,
        contents.map { (n, b) -> PackFile(n, "https://example.invalid/$n", b.size.toLong(), MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }) },
    )

    @Test fun `a pack is ready only when every file is complete and verified`() {
        val files = arrayOf("a.bin" to ByteArray(1000) { 1 }, "b.bin" to ByteArray(500) { 2 })
        val pack = packOf(*files)
        val store = ModelPackStore(tmp.newFolder("models"))
        assertEquals(PackStatus.MISSING, store.status(pack))
        store.dir(pack).mkdirs()
        File(store.dir(pack), "a.bin").writeBytes(files[0].second)
        assertEquals(PackStatus.PARTIAL, store.status(pack))
        File(store.dir(pack), "b.bin").writeBytes(files[1].second)
        assertEquals("right sizes but nobody checked the hashes (copied by hand)", PackStatus.UNVERIFIED, store.status(pack))
        assertEquals(null, store.verify(pack))
        assertEquals(PackStatus.READY, store.status(pack))
        // One byte changed, same length: the signature of the verified files no longer matches.
        File(store.dir(pack), "a.bin").also { it.writeBytes(ByteArray(1000) { 9 }); it.setLastModified(it.lastModified() + 5_000) }
        assertEquals(PackStatus.UNVERIFIED, store.status(pack))
        assertEquals("a.bin", store.verify(pack)!!.name)
        assertEquals(PackStatus.UNVERIFIED, store.status(pack))
        store.delete(pack)
        assertEquals(PackStatus.MISSING, store.status(pack))
    }

    // ------------------------------------------------------------------ the download

    private class PackServer(val files: Map<String, ByteArray>) : Dispatcher() {
        val ranges = java.util.Collections.synchronizedList(mutableListOf<String?>())
        @Volatile var cutFirstResponseAt: Int? = null
        @Volatile var notFound: String? = null
        override fun dispatch(request: RecordedRequest): MockResponse {
            val name = request.url.pathSegments.last()
            if (name == notFound) return MockResponse.Builder().code(404).build()
            val body = files[name] ?: return MockResponse.Builder().code(404).build()
            val range = request.headers["Range"]
            ranges += range
            val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull() ?: 0
            val slice = body.copyOfRange(from, body.size)
            val cut = cutFirstResponseAt
            if (cut != null && from == 0) {
                cutFirstResponseAt = null
                return MockResponse.Builder().code(200).setHeader("Content-Length", body.size.toString()).body(okio.Buffer().write(body, 0, cut)).onResponseEnd(SocketEffect.ShutdownConnection).build()
            }
            val b = MockResponse.Builder().code(if (range != null) 206 else 200).body(okio.Buffer().write(slice))
            if (range != null) b.setHeader("Content-Range", "bytes $from-${body.size - 1}/${body.size}")
            return b.build()
        }
    }

    private fun downloader(store: ModelPackStore, server: MockWebServer) = ModelDownloader(store, baseOverride = server.url("/").toString().trimEnd('/'), minFreeMargin = 0)

    @Test fun `the download verifies every file, reports progress and marks the pack ready`() = runBlocking {
        val files = arrayOf("a.bin" to ByteArray(300_000) { (it % 251).toByte() }, "b.bin" to ByteArray(1_000) { 3 })
        val pack = packOf(*files)
        val server = MockWebServer().also { it.dispatcher = PackServer(files.toMap()); it.start() }
        try {
            val store = ModelPackStore(tmp.newFolder("m1"))
            val progress = mutableListOf<Long>()
            val r = downloader(store, server).download(pack) { done, _ -> progress += done }
            assertEquals(DownloadResult.Done, r)
            assertEquals(PackStatus.READY, store.status(pack))
            assertEquals(pack.totalBytes, progress.last())
            assertTrue("progress only goes up", progress.zipWithNext().all { (a, b) -> b >= a })
            assertTrue("no partial file is left behind", store.dir(pack).listFiles()!!.none { it.name.endsWith(".part") })
        } finally { server.close() }
    }

    @Test fun `a cut connection resumes with a range request and never restarts the file`() = runBlocking {
        val files = arrayOf("a.bin" to ByteArray(400_000) { (it % 241).toByte() })
        val pack = packOf(*files)
        val dispatcher = PackServer(files.toMap()).also { it.cutFirstResponseAt = 150_000 }
        val server = MockWebServer().also { it.dispatcher = dispatcher; it.start() }
        try {
            val store = ModelPackStore(tmp.newFolder("m2"))
            val d = downloader(store, server)
            val first = d.download(pack) { _, _ -> }
            assertTrue("the cut is a network error, not a corrupt file: $first", first is DownloadResult.NetworkError)
            assertEquals(PackStatus.PARTIAL, store.status(pack))
            val part = File(store.dir(pack), "a.bin.part")
            assertTrue("what arrived is kept", part.length() in 1 until 400_000)
            val kept = part.length()

            val second = d.download(pack) { _, _ -> }
            assertEquals(DownloadResult.Done, second)
            assertEquals("bytes=$kept-", dispatcher.ranges.last())
            assertEquals(PackStatus.READY, store.status(pack))
        } finally { server.close() }
    }

    @Test fun `bytes that do not match the pinned hash are discarded and the model is not used`() = runBlocking {
        val good = "a.bin" to ByteArray(2_000) { 5 }
        val pack = packOf(good)
        val served = mapOf("a.bin" to ByteArray(2_000) { 6 }) // same size, different content
        val server = MockWebServer().also { it.dispatcher = PackServer(served); it.start() }
        try {
            val store = ModelPackStore(tmp.newFolder("m3"))
            val r = downloader(store, server).download(pack) { _, _ -> }
            assertEquals(DownloadResult.HashMismatch("a.bin"), r)
            assertFalse(File(store.dir(pack), "a.bin").exists())
            assertNotEmpty(store.status(pack) != PackStatus.READY)
        } finally { server.close() }
    }

    private fun assertNotEmpty(b: Boolean) = assertTrue(b)

    @Test fun `a server error is reported with its code and nothing is kept as complete`() = runBlocking {
        val files = arrayOf("a.bin" to ByteArray(100) { 1 })
        val pack = packOf(*files)
        val server = MockWebServer().also { it.dispatcher = PackServer(files.toMap()).also { d -> d.notFound = "a.bin" }; it.start() }
        try {
            val store = ModelPackStore(tmp.newFolder("m4"))
            assertEquals(DownloadResult.HttpError(404, "a.bin"), downloader(store, server).download(pack) { _, _ -> })
            assertTrue(store.status(pack) != PackStatus.READY)
        } finally { server.close() }
    }

    @Test fun `too little free space stops before any download`() = runBlocking {
        val files = arrayOf("a.bin" to ByteArray(100) { 1 })
        val pack = packOf(*files)
        val store = ModelPackStore(tmp.newFolder("m5"))
        val r = ModelDownloader(store, baseOverride = "http://127.0.0.1:1", minFreeMargin = Long.MAX_VALUE / 4).download(pack) { _, _ -> }
        assertTrue(r is DownloadResult.NotEnoughSpace)
    }

    @Test fun `the real pack is pinned to one revision with a hash for every file`() {
        val pack = com.s3vault.app.domain.semantic.ModelPacks.ClipB32
        assertTrue(pack.files.all { it.url.startsWith("https://huggingface.co/") && it.url.contains("/resolve/d15189d7028b43f1d3e65039190477f6af591c2a/") })
        assertTrue(pack.files.all { it.sha256.length == 64 && it.sizeBytes > 0 })
        assertEquals(4, pack.files.size)
        assertEquals("huggingface.co", pack.host)
        assertTrue("about 150 MB", pack.totalBytes in 150_000_000..160_000_000)
        assertEquals("MIT (OpenAI CLIP)", pack.license)
    }
}
