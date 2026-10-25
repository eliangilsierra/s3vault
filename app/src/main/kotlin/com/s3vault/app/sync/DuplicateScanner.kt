package com.s3vault.app.sync

import androidx.room.withTransaction
import com.s3vault.app.data.local.AnalysisStatus
import com.s3vault.app.data.local.AppDatabase
import com.s3vault.app.data.local.DuplicateGroupEntity
import com.s3vault.app.data.local.DuplicateKind
import com.s3vault.app.data.local.DuplicateMemberEntity
import com.s3vault.app.data.local.JobNames
import com.s3vault.app.data.local.JobState
import com.s3vault.app.data.local.MediaAnalysisEntity
import com.s3vault.app.data.local.ScanCandidate
import com.s3vault.app.domain.LocalFileReader
import com.s3vault.app.domain.duplicates.Fingerprint
import com.s3vault.app.domain.duplicates.PerceptualHash
import com.s3vault.app.domain.duplicates.SimilarityClusterer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest

/** Decodes a picture to a 32x32 greyscale thumbnail (values 0..255) without loading it at full size. Null when it cannot be decoded. */
fun interface ImageSignatureSource {
    suspend fun luma(uri: String): FloatArray?
}

data class ScanResult(
    val hashed: Int,
    val analysed: Int,
    /** Work still waiting after this run (the budget ran out); a new run continues from there. */
    val hashLeft: Int,
    val analysisLeft: Int,
    val exactGroups: Int,
    val similarGroups: Int,
)

/**
 * Finds identical and probably-identical pictures without reading more than it must.
 *
 *  - **Identical files**: only files that share their size with another file can be identical, so only those are hashed (SHA-256,
 *    streaming, off the main thread). The hash is the same cache the backup uses, valid while size and modification time match, so
 *    an unchanged file is hashed once in its life, whichever feature asked first.
 *  - **Similar pictures**: each picture is decoded at about thumbnail size once and reduced to a 64-bit perceptual hash, cached
 *    with the same validity rule. Videos are only compared by content hash.
 *
 * It is incremental and bounded: each run takes at most [maxItems] files (newest first), at most [concurrency] at a time, and can
 * be stopped at any moment; the next run continues. It reads only: deleting is a separate, confirmed user action.
 */
class DuplicateScanner(
    private val db: AppDatabase,
    private val reader: LocalFileReader,
    private val signatures: ImageSignatureSource,
    private val jobs: JobRecorder,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val concurrency: Int = 2,
) {
    private val dao get() = db.duplicates()

    suspend fun run(maxItems: Int = DEFAULT_BUDGET, threshold: Int = SimilarityClusterer.DEFAULT_THRESHOLD): ScanResult {
        val needHash = dao.needHashCount()
        val needLook = dao.needAnalysisCount()
        jobs.start(JobNames.DUPLICATES, needHash + needLook)
        var hashed = 0
        var analysed = 0
        try {
            val hashTodo = dao.needHash(maxItems)
            hashed = hashAll(hashTodo) { jobs.progress(JobNames.DUPLICATES, needHash + needLook, it, 0, it) }
            val lookBudget = (maxItems - hashTodo.size).coerceAtLeast(0)
            val lookTodo = if (lookBudget > 0) dao.needAnalysis(lookBudget) else emptyList()
            analysed = analyseAll(lookTodo) { jobs.progress(JobNames.DUPLICATES, needHash + needLook, hashed + it, 0, hashed + it) }
            val groups = rebuildGroups(threshold)
            val left = dao.needHashCount()
            val lookLeft = dao.needAnalysisCount()
            jobs.finish(JobNames.DUPLICATES, JobState.COMPLETED)
            return ScanResult(hashed, analysed, left, lookLeft, groups.first, groups.second)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { jobs.finish(JobNames.DUPLICATES, JobState.CANCELLED) }
            throw e
        }
    }

    private suspend fun hashAll(todo: List<ScanCandidate>, onProgress: suspend (Int) -> Unit): Int {
        var done = 0
        val gate = Semaphore(concurrency.coerceIn(1, 4))
        for (batch in todo.chunked(BATCH)) {
            currentCoroutineContext().ensureActive()
            val results = coroutineScope {
                batch.map { c ->
                    async {
                        gate.withPermit {
                            try {
                                val h = Hasher.sha256(reader, c.uri)
                                // The bytes read must be the bytes the row describes; a file edited meanwhile is left for the next run.
                                if (h.byteCount == c.sizeBytes) c to h.hexLower else null
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: FileNotFoundException) {
                                null
                            } catch (e: SecurityException) {
                                null
                            } catch (e: IOException) {
                                null
                            }
                        }
                    }
                }.awaitAll()
            }
            db.withTransaction { results.filterNotNull().forEach { (c, hex) -> db.media().saveHash(c.mediaStoreId, hex, c.sizeBytes, c.dateModifiedSec); done++ } }
            onProgress(done)
        }
        return done
    }

    private suspend fun analyseAll(todo: List<ScanCandidate>, onProgress: suspend (Int) -> Unit): Int {
        var done = 0
        val gate = Semaphore(concurrency.coerceIn(1, 4))
        for (batch in todo.chunked(BATCH)) {
            currentCoroutineContext().ensureActive()
            val rows = coroutineScope {
                batch.map { c ->
                    async {
                        gate.withPermit {
                            val luma = try { signatures.luma(c.uri) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                            val hash = luma?.let { PerceptualHash.compute(it) }
                            val status = when {
                                luma == null -> AnalysisStatus.UNREADABLE
                                hash == null -> AnalysisStatus.FLAT
                                else -> AnalysisStatus.OK
                            }
                            MediaAnalysisEntity(c.mediaStoreId, c.sizeBytes, c.dateModifiedSec, hash, status, nowMs())
                        }
                    }
                }.awaitAll()
            }
            dao.upsertAnalysis(rows)
            done += rows.size
            onProgress(done)
        }
        return done
    }

    /** Rewrites the stored groups from the current hashes and fingerprints. Returns (exact groups, similar groups). */
    suspend fun rebuildGroups(threshold: Int = SimilarityClusterer.DEFAULT_THRESHOLD): Pair<Int, Int> {
        dao.dropOrphanAnalysis()
        val exact = dao.exactMembers().groupBy({ it.sha }, { it.id })
        val prints = dao.fingerprints()
        val shaOf = prints.associate { it.id to it.sha }
        val clusters = SimilarityClusterer.cluster(prints.map { Fingerprint(it.id, it.hash, it.width, it.height, it.takenMs, it.sha) }, threshold)
            // A set that is wholly one identical-content group is already shown as such: it is not a "similar" finding.
            .filter { c -> c.ids.map { shaOf[it] }.let { shas -> shas.any { it == null } || shas.toSet().size > 1 } }
        val now = nowMs()
        db.withTransaction {
            dao.clearGroups()
            for ((sha, ids) in exact) {
                val g = dao.insertGroup(DuplicateGroupEntity(kind = DuplicateKind.EXACT, signature = "x:" + similarSignature(ids), createdAtMs = now))
                dao.insertMembers(ids.map { DuplicateMemberEntity(g, it, 0) })
            }
            for (c in clusters) {
                val g = dao.insertGroup(DuplicateGroupEntity(kind = DuplicateKind.SIMILAR, signature = "s:" + similarSignature(c.ids), createdAtMs = now))
                dao.insertMembers(c.ids.map { DuplicateMemberEntity(g, it, c.distances[it] ?: 0) })
            }
        }
        return exact.size to clusters.size
    }

    companion object {
        const val DEFAULT_BUDGET = 1200
        private const val BATCH = 24

        /** A short, stable name for a set of members: the same set is the same group after a rescan, a different set is a new one. */
        fun similarSignature(ids: List<Long>): String =
            MessageDigest.getInstance("SHA-1").digest(ids.sorted().joinToString(",").toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
