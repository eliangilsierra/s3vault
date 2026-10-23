package com.s3vault.app

import android.app.Application
import com.s3vault.app.security.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class S3VaultApp : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        appScope.launch {
            try {
                // Re-register the periodic job (idempotent) so auto-backup survives reboots and app updates.
                container.scheduler.configureAuto(container.settings.currentBackup())
                // Anything left QUEUED from a previous process is picked up again.
                val pending = container.db.media().queuedCount()
                val s = container.settings.currentBackup()
                if (pending > 0 && !s.paused) container.scheduler.kickBackup(s.wifiOnly)
            } catch (e: Exception) {
                SafeLog.w("S3VaultApp", "Startup scheduling failed", e)
            }
        }
    }
}
