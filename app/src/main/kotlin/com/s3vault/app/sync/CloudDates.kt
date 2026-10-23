package com.s3vault.app.sync

import com.s3vault.app.data.local.AppDatabase
import com.s3vault.app.domain.AwsCredentialsProvider
import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
import com.s3vault.app.domain.library.DateKeys
import com.s3vault.app.domain.library.DateSource
import com.s3vault.app.settings.AppSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.ZoneId

sealed interface DatesOutcome {
    /** [found] objects got a capture date; [withoutDate] have no `captured-at-ms` metadata and stay "sin fecha de captura". */
    data class Done(val found: Int, val withoutDate: Int) : DatesOutcome
    data class NeedsCredentials(val reason: RemoteErrorKind) : DatesOutcome
    data class RetryLater(val reason: RemoteErrorKind) : DatesOutcome
    data class Blocked(val reason: RemoteErrorKind) : DatesOutcome
    data object NotConfigured : DatesOutcome
}

/**
 * Reads the `captured-at-ms` metadata of the S3 objects whose date is not a capture date (uploaded by another tool or
 * device that is not this one). It is a user action, never automatic: one HEAD per object, 0.0004 USD per 1000.
 *
 * Every object is asked about exactly once: the answer, including "this object has no such metadata", is stored with
 * `metaChecked`, so a second run never repeats a request and an interrupted run resumes where it stopped.
 */
class CloudDatesEngine(
    private val db: AppDatabase,
    private val remote: RemoteContextProvider,
    private val credentials: AwsCredentialsProvider,
    private val catalog: RemoteCatalog,
    private val concurrency: Int = 4,
) {
    suspend fun run(batchSize: Int = 200): DatesOutcome {
        val ctx = remote.get() ?: return DatesOutcome.NotConfigured
        try {
            credentials.getCredentials()
        } catch (e: RemoteException) {
            return if (e.needsCredentials) DatesOutcome.NeedsCredentials(e.kind) else DatesOutcome.Blocked(e.kind)
        }
        var found = 0
        var withoutDate = 0
        val gate = Semaphore(concurrency.coerceIn(1, 8))
        while (true) {
            currentCoroutineContext().ensureActive()
            val batch = db.remote().pendingMeta(batchSize)
            if (batch.isEmpty()) return DatesOutcome.Done(found, withoutDate)
            val results = try {
                coroutineScope {
                    batch.map { obj ->
                        async {
                            gate.withPermit {
                                try {
                                    val meta = ctx.storage.head(obj.key)
                                    val captured = meta?.userMetadata?.get("captured-at-ms")?.toLongOrNull()
                                    catalog.recordCaptureDate(obj.key, captured)
                                    if (captured != null && DateKeys.isPlausible(captured, System.currentTimeMillis())) 1 else 0
                                } catch (e: RemoteException) {
                                    // Final answer: the object is gone. Anything else is transient and leaves it pending.
                                    if (e.kind == RemoteErrorKind.OBJECT_NOT_FOUND) { catalog.recordCaptureDate(obj.key, null); 0 } else throw e
                                }
                            }
                        }
                    }.awaitAll()
                }
            } catch (e: RemoteException) {
                return outcomeOf(e)
            }
            results.forEach { if (it == 1) found++ else withoutDate++ }
        }
    }

    private fun outcomeOf(e: RemoteException): DatesOutcome = when {
        e.needsCredentials -> DatesOutcome.NeedsCredentials(e.kind)
        e.retryable -> DatesOutcome.RetryLater(e.kind)
        else -> DatesOutcome.Blocked(e.kind)
    }
}

/**
 * Day keys are computed in the device time zone. If that zone changes (travel, a manual change), every key is rewritten so
 * the same instant is not filed under the wrong day. Nothing else is recomputed: dates themselves are UTC and never change.
 */
class DayKeyMaintainer(
    private val db: AppDatabase,
    private val settings: AppSettings,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    /** Returns true when keys were rewritten. */
    suspend fun ensureCurrentZone(): Boolean {
        val z = zone()
        if (settings.dayZone() == z.id) return false
        val media = db.media()
        for (r in media.datesForZoneChange()) media.setDayKey(r.mediaStoreId, DateKeys.dayKey(r.dateTakenMs, z))
        val remote = db.remote()
        for (r in remote.datesForZoneChange()) {
            remote.setUploadDayKey(r.key, DateKeys.dayKey(r.lastModifiedMs, z))
            remote.setDayKey(r.key, if (r.dateSource == DateSource.FALLBACK) 0 else DateKeys.dayKey(r.effectiveDateMs, z))
        }
        settings.setDayZone(z.id)
        return true
    }
}
