package com.s3vault.app

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.s3vault.app.data.local.ClipEmbedderProvider
import com.s3vault.app.domain.semantic.EmbeddingCodec
import com.s3vault.app.domain.semantic.ModelPacks
import com.s3vault.app.domain.semantic.PackStatus
import com.s3vault.app.sync.IndexOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The real model on a real Android runtime. It needs the 150 MB pack in the app's models folder (the app downloads it itself; this
 * test run copies it with `adb push` so it does not depend on the network) and skips otherwise.
 *
 * What it proves, against reference vectors made with the original Python pipeline (onnxruntime, Hugging Face tokenizer, PIL):
 *  - the Kotlin tokenizer and the platform image preprocessing feed the model the right input (cosine with the reference),
 *  - the whole indexing and search path ranks the right picture first for natural-language queries,
 *  - what it costs on this device (time per picture and per sentence, memory).
 * Numbers go to Logcat under `SemanticEval`.
 */
class SemanticModelDeviceTest {
    @get:Rule val perms: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as S3VaultApp
    private val container get() = app.container
    private val pack = ModelPacks.ClipB32
    private val uris = LinkedHashMap<String, android.net.Uri>()

    private fun asset(path: String) = instrumentation.context.assets.open("semantic/$path").readBytes()

    @Before fun setUp() {
        val status = container.modelStore.status(pack)
        Log.i("SemanticEval", "pack status $status in ${container.modelStore.dir(pack)}; files: ${container.modelStore.dir(pack).list()?.toList()}")
        assumeTrue("model pack not on the device ($status): push it to ${container.modelStore.dir(pack)}", status == PackStatus.READY || status == PackStatus.UNVERIFIED)
        runBlocking(Dispatchers.IO) { container.db.clearAllTables() }
        listOf("astronaut", "cat", "coffee", "rocket", "motorcycle", "page", "coins", "camera").forEach { n ->
            uris[n] = BulkPerfSupport.insertBytes("eval_$n.jpg", asset("$n.jpg"), "Pictures/S3VaultSemantic")
        }
        runBlocking(Dispatchers.IO) { container.library.refresh(force = true) }
    }

    @After fun tearDown() {
        uris.values.forEach { BulkPerfSupport.delete(it) }
        runBlocking(Dispatchers.IO) { container.db.clearAllTables() }
    }

    private fun cosine(a: FloatArray, b: List<Double>): Double = a.indices.sumOf { a[it] * b[it] }

    @Test fun theDeviceReproducesTheReferenceVectorsAndRanksTheRightPictureFirst() = runBlocking {
        // 1. The pack the user (or adb) put there is verified against the pinned hashes once.
        val t0 = System.nanoTime()
        if (container.modelStore.status(pack) == PackStatus.UNVERIFIED) assertEquals("every file hashes as pinned", null, container.modelStore.verify(pack))
        Log.i("SemanticEval", "pack verification ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertEquals(PackStatus.READY, container.modelStore.status(pack))

        val embedder = ClipEmbedderProvider(instrumentation.targetContext, container.modelStore, pack).open()!!
        try {
            val refImages = JSONObject(String(asset("image_embeddings.json")))
            val refTexts = JSONObject(String(asset("text_embeddings.json")))
            val rt = Runtime.getRuntime()
            val memBefore = android.os.Debug.getNativeHeapAllocatedSize() + (rt.totalMemory() - rt.freeMemory())

            // 2. Text: the tokenizer and the text model match the reference to float precision.
            val t1 = System.nanoTime()
            val first = embedder.embedText("a photo of a cat")
            Log.i("SemanticEval", "first text embedding (loads the text model) ${(System.nanoTime() - t1) / 1_000_000} ms")
            val textMs = ArrayList<Long>()
            for (key in refTexts.keys()) {
                val t = System.nanoTime()
                val v = embedder.embedText(key)
                textMs += (System.nanoTime() - t) / 1_000_000
                val ref = refTexts.getJSONArray(key).let { a -> List(a.length()) { a.getDouble(it) } }
                val c = cosine(v, ref)
                assertTrue("text '$key' cosine with the reference is $c", c > 0.999)
            }
            assertTrue(cosine(first, refTexts.getJSONArray("a photo of a cat").let { a -> List(a.length()) { a.getDouble(it) } }) > 0.999)

            // 3. Pictures: platform decoding and resizing differ slightly from PIL's bicubic, so the bar is a little lower.
            val imageMs = ArrayList<Long>()
            val own = HashMap<String, FloatArray>()
            val t2 = System.nanoTime()
            for ((name, uri) in uris) {
                val t = System.nanoTime()
                val v = embedder.embedImage(uri.toString())
                if (name == uris.keys.first()) Log.i("SemanticEval", "first picture (loads the vision model) ${(System.nanoTime() - t) / 1_000_000} ms")
                else imageMs += (System.nanoTime() - t) / 1_000_000
                assertNotNull("$name decoded", v)
                own[name] = v!!
                val ref = refImages.getJSONArray(name).let { a -> List(a.length()) { a.getDouble(it) } }
                val c = cosine(v, ref)
                Log.i("SemanticEval", "image $name cosine with the Python reference: %.4f".format(c))
                assertTrue("image $name cosine with the reference is $c (the platform resizes slightly differently from PIL bicubic)", c > 0.95)
            }
            Log.i("SemanticEval", "8 pictures in ${(System.nanoTime() - t2) / 1_000_000} ms; median per picture ${imageMs.sorted()[imageMs.size / 2]} ms; median per sentence ${textMs.sorted()[textMs.size / 2]} ms")
            val memAfter = android.os.Debug.getNativeHeapAllocatedSize() + (rt.totalMemory() - rt.freeMemory())
            Log.i("SemanticEval", "memory with both models loaded: +${(memAfter - memBefore) / (1024 * 1024)} MB (native + java heap)")

            // 4. The queries of the evaluation rank the right picture first, from our own vectors.
            var hits = 0
            for ((q, expected) in listOf(
                "a photo of a cat" to "cat", "an astronaut" to "astronaut", "a cup of coffee" to "coffee", "a rocket" to "rocket",
                "a motorcycle" to "motorcycle", "a page of text" to "page", "coins" to "coins", "a photographer with a camera" to "camera",
            )) {
                val qv = embedder.embedText(q)
                val best = own.maxByOrNull { (_, v) -> v.indices.sumOf { (v[it] * qv[it]).toDouble() } }!!.key
                if (best == expected) hits++ else Log.w("SemanticEval", "query '$q' ranked $best first, expected $expected")
            }
            Log.i("SemanticEval", "zero-shot top-1 among 8 pictures: $hits/8")
            assertTrue("$hits of 8 queries ranked the expected picture first", hits >= 7)
        } finally {
            embedder.close()
        }
    }

    @Test fun theWholeIndexingAndSearchPathWorksOnTheLibrary() = runBlocking {
        if (container.modelStore.status(pack) == PackStatus.UNVERIFIED) container.modelStore.verify(pack)
        val t0 = System.nanoTime()
        val out = container.semanticIndexer.run(maxItems = 500) as IndexOutcome.Done
        val ms = (System.nanoTime() - t0) / 1_000_000
        Log.i("SemanticEval", "indexed ${out.indexed} pictures in $ms ms (${if (out.indexed > 0) ms / out.indexed else 0} ms each, including loading the model); left ${out.left}")
        val mine = container.db.media().getEverything().filter { it.folderName == "S3VaultSemantic" }.map { it.mediaStoreId to it.displayName }.toMap()
        assertEquals(8, mine.size)

        val embedder = ClipEmbedderProvider(instrumentation.targetContext, container.modelStore, pack).open()!!
        try {
            for ((q, expected) in listOf("a photo of a cat" to "eval_cat.jpg", "a cup of coffee" to "eval_coffee.jpg", "a page of text" to "eval_page.jpg", "an astronaut" to "eval_astronaut.jpg")) {
                val t = System.nanoTime()
                val r = container.semanticSearcher.search(embedder, q, k = 8)
                Log.i("SemanticEval", "search '$q' took ${(System.nanoTime() - t) / 1_000_000} ms over ${r.searchable} pictures; top: ${r.hits.take(3).joinToString { "${it.first.displayName}=%.3f".format(it.second) }}")
                assertEquals("'$q' finds $expected first", expected, r.hits.first().first.displayName)
            }
            // A second pass costs nothing: every picture already has its vector.
            val again = container.semanticIndexer.run() as IndexOutcome.Done
            assertEquals(0, again.indexed)
        } finally {
            embedder.close()
        }
    }
}
