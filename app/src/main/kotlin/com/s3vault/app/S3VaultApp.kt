package com.s3vault.app

import android.app.Application
import android.os.StrictMode
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.s3vault.app.data.local.MediaStoreThumbnailFetcher
import com.s3vault.app.security.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class S3VaultApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) enableStrictMode()
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

    /** Grid thumbnails come from the platform's thumbnail cache; everything else uses Coil's default pipeline and caches. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context).components { add(MediaStoreThumbnailFetcher.Factory(this@S3VaultApp)) }.build()

    /**
     * Debug builds only: report (never crash on) disk or network work on the main thread and leaked resources, so a
     * regression shows up in Logcat under `StrictMode` while developing.
     */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectLeakedSqlLiteObjects().detectActivityLeaks().penaltyLog().build())
    }
}
