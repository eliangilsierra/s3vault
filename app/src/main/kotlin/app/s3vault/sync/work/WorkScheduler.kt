package app.s3vault.sync.work

import android.content.Context
import android.provider.MediaStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Programa el trabajo persistente con WorkManager. El sistema operativo decide cuándo ejecutar:
 * no hay garantía de inmediatez (Doze, ahorro de batería, restricciones de red).
 */
class WorkScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val wm get() = WorkManager.getInstance(appContext)

    private fun network(wifiOnly: Boolean) =
        if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

    /** Procesa la cola persistente. APPEND_OR_REPLACE: lo encolado durante una pasada se atiende en la siguiente. */
    fun enqueueBackup(wifiOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network(wifiOnly)).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniqueWork(BACKUP, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Detiene la pasada en curso: el motor devuelve lo que estaba subiéndose a la cola. */
    fun cancelBackup() {
        wm.cancelUniqueWork(BACKUP)
    }

    /** Respaldo automático: revisión periódica + disparo por cambios en MediaStore (con retardo para agrupar). */
    fun configureAutoBackup(enabled: Boolean, wifiOnly: Boolean) {
        if (!enabled) {
            wm.cancelUniqueWork(AUTO_PERIODIC)
            wm.cancelUniqueWork(AUTO_TRIGGER)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(network(wifiOnly))
            .setRequiresBatteryNotLow(true)
            .build()
        val periodic = PeriodicWorkRequestBuilder<AutoBackupWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork(AUTO_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodic)
        scheduleContentTrigger(wifiOnly)
    }

    /** Una sola ejecución que se dispara cuando cambian las fotos/vídeos; el worker se reprograma al terminar. */
    fun scheduleContentTrigger(wifiOnly: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(network(wifiOnly))
            .setRequiresBatteryNotLow(true)
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(Duration.ofSeconds(30))
            .setTriggerContentMaxDelay(Duration.ofMinutes(10))
            .build()
        val request = OneTimeWorkRequestBuilder<AutoBackupWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(AutoBackupWorker.KEY_FROM_TRIGGER to true))
            .build()
        wm.enqueueUniqueWork(AUTO_TRIGGER, ExistingWorkPolicy.REPLACE, request)
    }

    fun enqueueRestore(remoteKey: String, wifiOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<RestoreWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network(wifiOnly)).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(RestoreWorker.KEY_REMOTE_KEY to remoteKey))
            .addTag(RESTORE_TAG)
            .build()
        wm.enqueueUniqueWork("restore-" + hash(remoteKey), ExistingWorkPolicy.KEEP, request)
    }

    fun cancelAll() {
        wm.cancelUniqueWork(BACKUP)
        wm.cancelUniqueWork(AUTO_PERIODIC)
        wm.cancelUniqueWork(AUTO_TRIGGER)
        wm.cancelAllWorkByTag(RESTORE_TAG)
    }

    private fun hash(s: String) =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    companion object {
        const val BACKUP = "backup-queue"
        const val AUTO_PERIODIC = "auto-backup-periodic"
        const val AUTO_TRIGGER = "auto-backup-trigger"
        const val RESTORE_TAG = "restore"
    }
}
