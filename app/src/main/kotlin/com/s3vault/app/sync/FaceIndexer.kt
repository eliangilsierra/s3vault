package com.s3vault.app.sync

import androidx.room.withTransaction
import com.s3vault.app.data.faces.FaceImageSource
import com.s3vault.app.data.faces.ImageLoad
import com.s3vault.app.data.local.AppDatabase
import com.s3vault.app.data.local.FaceCandidate
import com.s3vault.app.data.local.FaceEntity
import com.s3vault.app.data.local.FacePhotoEntity
import com.s3vault.app.data.local.JobNames
import com.s3vault.app.data.local.JobState
import com.s3vault.app.domain.faces.FaceAssignment
import com.s3vault.app.domain.faces.FaceAnalyzer
import com.s3vault.app.domain.faces.FaceModel
import com.s3vault.app.domain.faces.FacePhotoStatus
import com.s3vault.app.domain.faces.FaceReconciler
import com.s3vault.app.domain.faces.FaceVault
import com.s3vault.app.domain.faces.FaceVectorCodec
import com.s3vault.app.domain.faces.isUsable
import com.s3vault.app.domain.faces.PhotoAnalysis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Opens the analyzer, or says there is none (the model files are missing or unreadable): the feature then says so instead of guessing. */
fun interface FaceEngineProvider {
    suspend fun open(): FaceAnalyzer?
}

sealed interface FaceRunOutcome {
    /** [left] photos still wait (the run's budget ended); a new run continues from there. */
    data class Done(
        val analyzed: Int, val withFaces: Int, val unreadable: Int, val unavailable: Int, val left: Int, val newPeople: Int,
    ) : FaceRunOutcome

    data object ModelMissing : FaceRunOutcome

    /** The key that seals the index is gone or cannot be read: nothing stored can be opened, and the user must rebuild the index. */
    data object IndexUnreadable : FaceRunOutcome

    /** Another run is already working in this process. */
    data object Busy : FaceRunOutcome

    /** The index holds as many faces as it is allowed to: nothing more is analysed until the user erases or rebuilds it. */
    data object QuotaReached : FaceRunOutcome

    /** The device is short of free space: the run stops before it writes anything. */
    data object LowStorage : FaceRunOutcome
}

/**
 * Builds the face index incrementally and with bounded work, the same way the other local indexes do:
 *  - the queue is the database itself: a photo is "pending" when it has no analysis valid for this file (size, modification time)
 *    and this pipeline, so it survives a killed process, a restart and a reboot with nothing extra to persist;
 *  - newest photos first, at most [maxPhotos] and [maxMillis] per run, a few at a time, then the next run goes on;
 *  - a photo is analysed once in its life; unreadable files are retried only if they change;
 *  - faces are matched to groups in small steps, so people appear while the run goes on;
 *  - nothing is held beyond a batch, and the models are released when the run ends.
 * It needs no network and runs only from a worker with its battery constraint, or when the user asks for it.
 */
class FaceIndexer(
    private val db: AppDatabase,
    private val vault: FaceVault,
    private val provider: FaceEngineProvider,
    private val images: FaceImageSource,
    private val grouping: FaceGroupingStore,
    private val lock: FaceIndexLock,
    private val jobs: JobRecorder,
    /** Called once per run after the faces were placed, to let the backup rules look at what is new. */
    private val afterRun: suspend () -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Free bytes where the database lives; a run does not start below [MIN_FREE_BYTES]. */
    private val freeBytes: () -> Long = { Long.MAX_VALUE },
    private val maxFaces: Int = MAX_INDEX_FACES,
    private val elapsedMs: () -> Long = System::nanoTime.let { f -> { f() / 1_000_000 } },
) {
    private val dao get() = db.faces()
    private val running = Mutex()

    suspend fun run(maxPhotos: Int = DEFAULT_BUDGET, workers: Int = 1, maxMillis: Long = DEFAULT_MAX_MILLIS): FaceRunOutcome {
        if (!running.tryLock()) return FaceRunOutcome.Busy
        try {
            val analyzer = provider.open() ?: return FaceRunOutcome.ModelMissing
            try {
                return runWith(analyzer, maxPhotos, workers.coerceIn(1, 4), maxMillis)
            } finally {
                analyzer.close()
            }
        } finally {
            running.unlock()
        }
    }

    private suspend fun runWith(pipeline: FaceAnalyzer, maxPhotos: Int, workers: Int, maxMillis: Long): FaceRunOutcome {
        if (!indexReadable()) return FaceRunOutcome.IndexUnreadable
        if (dao.faceCount() >= maxFaces) return stop(FaceRunOutcome.QuotaReached, ATTENTION_QUOTA)
        if (freeBytes() < MIN_FREE_BYTES) return stop(FaceRunOutcome.LowStorage, ATTENTION_STORAGE)
        lock.mutex.withLock {
            dao.dropOrphanFaces(); dao.dropOrphanPhotos()
            dao.deleteEmptyProvisionalPeople()
        }
        val total = dao.needAnalysisCount(FaceModel.MODEL_ID, FaceModel.PIPELINE_VERSION)
        jobs.start(JobNames.FACES, total)
        val gate = Semaphore(workers)
        val started = elapsedMs()
        val pending = ArrayList<Long>()
        var analyzed = 0; var withFaces = 0; var unreadable = 0; var unavailable = 0; var newPeople = 0
        var cursor = Long.MAX_VALUE
        try {
            while (analyzed + unreadable < maxPhotos && elapsedMs() - started < maxMillis) {
                currentCoroutineContext().ensureActive()
                val batch = dao.needAnalysis(FaceModel.MODEL_ID, FaceModel.PIPELINE_VERSION, cursor, BATCH)
                if (batch.isEmpty()) break
                cursor = batch.last().mediaStoreId
                val results = coroutineScope {
                    batch.map { c -> async(Dispatchers.Default) { c to gate.withPermit { analyzeOne(pipeline, c) } } }.awaitAll()
                }
                val stored = lock.mutex.withLock { persist(results) }
                pending += stored.newFaces
                analyzed += stored.done; withFaces += stored.withFaces; unreadable += stored.unreadable; unavailable += stored.unavailable
                if (pending.size >= INTEGRATE_EVERY) newPeople += integratePending(pending)
                jobs.progress(JobNames.FACES, total, analyzed, unreadable, analyzed + unreadable)
            }
            newPeople += integratePending(pending)
            lock.mutex.withLock { grouping.autoMerge() }
            afterRun()
            jobs.finish(JobNames.FACES, JobState.COMPLETED)
        } catch (e: CancellationException) {
            // Whatever was analysed is already stored; place its faces so a stopped run leaves a consistent index.
            withContext(NonCancellable) {
                integratePending(pending)
                jobs.finish(JobNames.FACES, JobState.CANCELLED)
            }
            throw e
        }
        return FaceRunOutcome.Done(analyzed, withFaces, unreadable, unavailable, dao.needAnalysisCount(FaceModel.MODEL_ID, FaceModel.PIPELINE_VERSION), newPeople)
    }

    /** Records why the run did not start, as a code the screen turns into words. */
    private suspend fun stop(outcome: FaceRunOutcome, code: String): FaceRunOutcome {
        jobs.start(JobNames.FACES, 0)
        jobs.finish(JobNames.FACES, JobState.NEEDS_ATTENTION, code)
        return outcome
    }

    private suspend fun integratePending(pending: MutableList<Long>): Int {
        if (pending.isEmpty()) return 0
        val ids = pending.toList()
        pending.clear()
        return lock.mutex.withLock { grouping.integrate(ids).newPeople }
    }

    /** The key must open what is stored; if it cannot, every vector is unreadable and analysing more would only add to the pile. */
    private suspend fun indexReadable(): Boolean {
        val sample = dao.sampleSealed()
        if (sample.isEmpty()) return vault.isUsable()
        return sample.any { FaceVectorCodec.openVector(vault, it) != null }
    }

    private sealed interface One {
        class Faces(val result: PhotoAnalysis) : One
        data object Unreadable : One
        data object Unavailable : One
    }

    private suspend fun analyzeOne(pipeline: FaceAnalyzer, c: FaceCandidate): One {
        // A picture this small (an icon, a sticker) holds no face worth comparing: recorded as analysed, never decoded.
        if (c.width in 1 until MIN_SIDE || c.height in 1 until MIN_SIDE) return One.Faces(PhotoAnalysis(emptyList(), 0))
        return when (val l = images.load(c.uri)) {
            is ImageLoad.Ok -> try {
                One.Faces(pipeline.analyze(l.image))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                One.Unreadable
            } catch (e: OutOfMemoryError) {
                One.Unavailable
            }
            ImageLoad.Unreadable -> One.Unreadable
            ImageLoad.Unavailable -> One.Unavailable
        }
    }

    private class Stored(val newFaces: List<Long>, val done: Int, val withFaces: Int, val unreadable: Int, val unavailable: Int)

    /** Writes a batch. Faces found again in a photo take over the rows of the faces already stored there, so the user's work is kept. */
    private suspend fun persist(results: List<Pair<FaceCandidate, One>>): Stored {
        val newFaces = ArrayList<Long>()
        var done = 0; var withFaces = 0; var unreadable = 0; var unavailable = 0
        db.withTransaction {
            val touched = HashSet<Long>()
            val olds = results.map { it.first.mediaStoreId }.chunked(400).flatMap { ids -> ids.flatMap { dao.facesOfPhoto(it) } }.groupBy { it.mediaStoreId }
            val photoRows = ArrayList<FacePhotoEntity>()
            val now = nowMs()
            for ((c, r) in results) {
                val old = olds[c.mediaStoreId].orEmpty()
                when (r) {
                    One.Unavailable -> { unavailable++; continue }
                    One.Unreadable -> {
                        unreadable++
                        old.mapNotNullTo(touched) { it.personId }
                        if (old.isNotEmpty()) dao.deleteFaces(old.map { it.id })
                        photoRows += FacePhotoEntity(c.mediaStoreId, c.sizeBytes, c.dateModifiedSec, FaceModel.PIPELINE_VERSION, FaceModel.MODEL_ID, FacePhotoStatus.UNREADABLE, 0, 0, now)
                    }
                    is One.Faces -> {
                        done++
                        val found = r.result.faces
                        if (found.isNotEmpty()) withFaces++
                        val pairs = FaceReconciler.match(old.map { floatArrayOf(it.boxLeft, it.boxTop, it.boxRight, it.boxBottom) }, found.map { it.box })
                        val matchedOld = pairs.map { it.first }.toSet(); val matchedNew = pairs.map { it.second }.toSet()
                        val updates = ArrayList<FaceEntity>()
                        for ((oi, ni) in pairs) {
                            val o = old[oi]; val n = found[ni]
                            val modelChanged = o.modelId != FaceModel.MODEL_ID
                            // The user's own decisions survive a new analysis; automatic ones are made again in the new vector space.
                            val keep = o.assignment == FaceAssignment.CONFIRMED || !modelChanged
                            if (o.personId != null) touched += o.personId
                            val moved = FaceEntity(
                                id = o.id, mediaStoreId = o.mediaStoreId, boxLeft = n.box[0], boxTop = n.box[1], boxRight = n.box[2], boxBottom = n.box[3],
                                detScore = n.detScore, quality = n.quality, modelId = FaceModel.MODEL_ID, sealed = FaceVectorCodec.sealVector(vault, n.vector),
                                personId = if (keep) o.personId else null,
                                assignment = if (keep) o.assignment else FaceAssignment.NONE,
                                matchScore = if (keep) o.matchScore else 0f, margin = if (keep) o.margin else 0f,
                                suggestedPersonId = if (keep) o.suggestedPersonId else null, createdAtMs = o.createdAtMs,
                            )
                            updates += moved
                            if (!keep) newFaces += o.id
                        }
                        if (updates.isNotEmpty()) dao.updateFaces(updates)
                        val gone = old.filterIndexed { i, _ -> i !in matchedOld }
                        gone.mapNotNullTo(touched) { it.personId }
                        if (gone.isNotEmpty()) dao.deleteFaces(gone.map { it.id })
                        val inserts = found.filterIndexed { i, _ -> i !in matchedNew }.map { n ->
                            FaceEntity(
                                mediaStoreId = c.mediaStoreId, boxLeft = n.box[0], boxTop = n.box[1], boxRight = n.box[2], boxBottom = n.box[3],
                                detScore = n.detScore, quality = n.quality, modelId = FaceModel.MODEL_ID, sealed = FaceVectorCodec.sealVector(vault, n.vector), createdAtMs = now,
                            )
                        }
                        if (inserts.isNotEmpty()) newFaces += dao.insertFaces(inserts)
                        photoRows += FacePhotoEntity(c.mediaStoreId, c.sizeBytes, c.dateModifiedSec, FaceModel.PIPELINE_VERSION, FaceModel.MODEL_ID, FacePhotoStatus.DONE, found.size, r.result.skipped, now)
                    }
                }
            }
            if (photoRows.isNotEmpty()) dao.upsertPhotos(photoRows)
            if (touched.isNotEmpty()) grouping.refreshCenters(touched)
        }
        return Stored(newFaces, done, withFaces, unreadable, unavailable)
    }

    companion object {
        const val DEFAULT_BUDGET = 300

        /** About 60 MB of index (a quarter of a kilobyte per face); far above any personal library. */
        const val MAX_INDEX_FACES = 250_000
        const val MIN_FREE_BYTES = 200L * 1024 * 1024
        const val ATTENTION_QUOTA = "face:quota"
        const val ATTENTION_STORAGE = "face:low-storage"
        const val DEFAULT_MAX_MILLIS = 8L * 60 * 1000
        private const val BATCH = 8
        private const val INTEGRATE_EVERY = 60
        private const val MIN_SIDE = 96
    }
}
