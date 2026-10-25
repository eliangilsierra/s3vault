package com.s3vault.app.sync

import com.s3vault.app.data.local.AppDatabase
import com.s3vault.app.data.local.JobNames
import com.s3vault.app.data.local.JobState
import com.s3vault.app.data.local.ScanCandidate
import com.s3vault.app.data.local.SemanticEmbeddingEntity
import com.s3vault.app.domain.MediaItem
import com.s3vault.app.domain.semantic.EmbedderProvider
import com.s3vault.app.domain.semantic.EmbeddingCodec
import com.s3vault.app.domain.semantic.Scored
import com.s3vault.app.domain.semantic.TextImageEmbedder
import com.s3vault.app.domain.semantic.TopK
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

sealed interface IndexOutcome {
    /** [left] pictures still wait (the run's budget ended); a new run continues from there. */
    data class Done(val indexed: Int, val unreadable: Int, val left: Int) : IndexOutcome

    /** The model is not on the device (or failed verification): nothing was done and nothing is pretended. */
    data object ModelMissing : IndexOutcome
}

/**
 * Builds the on-device index of picture vectors, incrementally and with bounded work:
 *  - newest pictures first, so what the user just shot becomes searchable first;
 *  - one picture at a time through the model (the embedder serialises inference), a few hundred per run, then the next run goes on;
 *  - a picture with a valid vector for this model (same size and modification time) is never embedded again;
 *  - nothing is held beyond a batch, and the model's memory is released when the run ends.
 * Runs only from a worker with its battery constraint, or when the user asks for it; it never blocks the main thread.
 */
class SemanticIndexer(
    private val db: AppDatabase,
    private val provider: EmbedderProvider,
    private val jobs: JobRecorder,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val dao get() = db.semantic()

    suspend fun run(maxItems: Int = DEFAULT_BUDGET, batch: Int = 16): IndexOutcome {
        val embedder = provider.open() ?: return IndexOutcome.ModelMissing
        try {
            val model = embedder.modelId
            dao.dropOtherModels(model) // vectors of an older model are meaningless next to the new one
            dao.dropOrphans()
            val total = dao.needIndexCount(model)
            jobs.start(JobNames.SEMANTIC, total)
            var indexed = 0
            var unreadable = 0
            try {
                while (indexed + unreadable < maxItems) {
                    currentCoroutineContext().ensureActive()
                    val todo = dao.needIndex(model, minOf(batch, maxItems - indexed - unreadable))
                    if (todo.isEmpty()) break
                    val rows = ArrayList<SemanticEmbeddingEntity>(todo.size)
                    for (c in todo) {
                        currentCoroutineContext().ensureActive()
                        rows += embed(embedder, c)
                    }
                    dao.upsert(rows)
                    indexed += rows.count { it.ok }
                    unreadable += rows.count { !it.ok }
                    jobs.progress(JobNames.SEMANTIC, total, indexed, unreadable, indexed + unreadable)
                }
                jobs.finish(JobNames.SEMANTIC, JobState.COMPLETED)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { jobs.finish(JobNames.SEMANTIC, JobState.CANCELLED) }
                throw e
            }
            return IndexOutcome.Done(indexed, unreadable, dao.needIndexCount(model))
        } finally {
            embedder.close()
        }
    }

    private suspend fun embed(embedder: TextImageEmbedder, c: ScanCandidate): SemanticEmbeddingEntity {
        val v = try { embedder.embedImage(c.uri) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        return if (v == null) {
            SemanticEmbeddingEntity(c.mediaStoreId, embedder.modelId, c.sizeBytes, c.dateModifiedSec, ok = false, scale = 1f, vector = ByteArray(0), indexedAtMs = nowMs())
        } else {
            val q = EmbeddingCodec.quantize(v)
            SemanticEmbeddingEntity(c.mediaStoreId, embedder.modelId, c.sizeBytes, c.dateModifiedSec, ok = true, scale = q.scale, vector = q.bytes, indexedAtMs = nowMs())
        }
    }

    companion object { const val DEFAULT_BUDGET = 400 }
}

/** What a content search found, and how much of the library it could look at. */
data class SemanticResult(val hits: List<Pair<MediaItem, Float>>, val searchable: Int, val pending: Int)

/**
 * Ranks the indexed pictures against a sentence. The sentence is embedded once; the stored vectors stream past in pages of a
 * couple of thousand (never the whole library at once) and only the best [k] are kept. Pure arithmetic over local data.
 */
class SemanticSearcher(private val db: AppDatabase) {
    suspend fun search(embedder: TextImageEmbedder, query: String, k: Int = 120): SemanticResult {
        val q = embedder.embedText(query)
        val model = embedder.modelId
        val top = TopK(k)
        var after = Long.MIN_VALUE
        var seen = 0
        while (true) {
            val page = db.semantic().page(model, after, PAGE)
            if (page.isEmpty()) break
            for (r in page) top.offer(r.mediaStoreId, EmbeddingCodec.dot(q, r.vector, r.scale))
            seen += page.size
            after = page.last().mediaStoreId
            if (page.size < PAGE) break
        }
        val best = top.best()
        val items = best.map { it.id }.chunked(400).flatMap { db.media().getAll(it) }.associateBy { it.mediaStoreId }
        val hits = best.mapNotNull { s: Scored -> items[s.id]?.let { it.toDomain() to s.score } }
        return SemanticResult(hits, searchable = seen, pending = db.semantic().needIndexCount(model))
    }

    private companion object { const val PAGE = 2000 }
}
