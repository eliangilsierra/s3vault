package app.s3vault.sync

import app.s3vault.domain.remote.RemoteFailure
import app.s3vault.domain.repo.BackupStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/** Resumen de una pasada de la cola. */
data class RunSummary(
    val uploaded: Int,
    val alreadyRemote: Int,
    val deferred: Int,
    val failed: Int,
    /** Si se detuvo por un problema global (credenciales, permisos…), cuál fue. */
    val blocked: RemoteFailure?,
) {
    /** Hay trabajo que merece un reintento con backoff (errores temporales). */
    val shouldRetry: Boolean get() = blocked == null && deferred > 0
}

/**
 * Recorre la cola persistente respetando el límite de concurrencia. Cada elemento se intenta
 * como máximo una vez por pasada: los reintentos con backoff los programa WorkManager.
 */
class BackupRunner(
    private val store: BackupStore,
    private val engine: BackupEngine,
    private val concurrency: Int,
    private val batchSize: Int = 50,
) {
    init {
        require(concurrency >= 1)
    }

    suspend fun run(isAllowedToContinue: () -> Boolean = { true }): RunSummary = coroutineScope {
        store.recoverInterrupted()
        val uploaded = AtomicInteger()
        val already = AtomicInteger()
        val deferred = AtomicInteger()
        val failed = AtomicInteger()
        val blocked = java.util.concurrent.atomic.AtomicReference<RemoteFailure?>(null)
        val stop = AtomicBoolean(false)
        val attempted = HashSet<String>()
        val semaphore = Semaphore(concurrency)

        while (!stop.get() && isAllowedToContinue()) {
            coroutineContext.ensureActive()
            val batch = store.queued(batchSize).filter { attempted.add(it.assetId) }
            if (batch.isEmpty()) break
            batch.map { item ->
                async {
                    semaphore.withPermit {
                        if (stop.get() || !isAllowedToContinue()) return@withPermit
                        when (val o = engine.process(item.assetId)) {
                            is ItemOutcome.BackedUp -> if (o.uploaded) uploaded.incrementAndGet() else already.incrementAndGet()
                            is ItemOutcome.Deferred -> deferred.incrementAndGet()
                            is ItemOutcome.Failed -> failed.incrementAndGet()
                            is ItemOutcome.Blocked -> {
                                blocked.compareAndSet(null, o.failure)
                                stop.set(true)
                            }

                            ItemOutcome.Skipped -> Unit
                        }
                    }
                }
            }.awaitAll()
        }
        RunSummary(uploaded.get(), already.get(), deferred.get(), failed.get(), blocked.get())
    }
}
