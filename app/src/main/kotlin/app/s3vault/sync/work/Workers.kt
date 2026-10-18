package app.s3vault.sync.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.s3vault.S3VaultApp

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as S3VaultApp).container
        return when (container.backupCoordinator.runQueue { isStopped }) {
            RunResult.DONE -> Result.success()
            // Errores temporales: WorkManager reintenta con backoff exponencial.
            RunResult.RETRY -> Result.retry()
        }
    }
}

class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as S3VaultApp).container
        container.backupCoordinator.autoBackupPass()
        // El disparo por contenido es de un solo uso: se vuelve a programar para el siguiente cambio.
        if (inputData.getBoolean(KEY_FROM_TRIGGER, false)) {
            val s = container.settingsSnapshot()
            if (s.autoBackup) container.workScheduler.scheduleContentTrigger(s.wifiOnly)
        }
        return Result.success()
    }

    companion object {
        const val KEY_FROM_TRIGGER = "from_trigger"
    }
}

class RestoreWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val key = inputData.getString(KEY_REMOTE_KEY) ?: return Result.failure()
        val container = (applicationContext as S3VaultApp).container
        return when (container.backupCoordinator.runRestore(key)) {
            RunResult.DONE -> Result.success()
            RunResult.RETRY -> Result.retry()
        }
    }

    companion object {
        const val KEY_REMOTE_KEY = "remote_key"
    }
}
