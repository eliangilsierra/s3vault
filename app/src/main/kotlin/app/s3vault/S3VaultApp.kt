package app.s3vault

import android.app.Application
import android.util.Log
import app.s3vault.logging.LogSink
import app.s3vault.logging.SecureLog

class S3VaultApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Todo log pasa por SecureLog, que enmascara claves, tokens y cabeceras de autorización.
        SecureLog.sink = LogSink { level, tag, message ->
            when (level) {
                LogSink.Level.DEBUG -> if (BuildConfig.DEBUG) Log.d(tag, message)
                LogSink.Level.INFO -> Log.i(tag, message)
                LogSink.Level.WARN -> Log.w(tag, message)
                LogSink.Level.ERROR -> Log.e(tag, message)
            }
        }
        container = AppContainer(this)
    }
}
