package app.s3vault.data.repo

import app.s3vault.AppContainer
import app.s3vault.data.remote.ConnectionReport
import app.s3vault.data.remote.ConnectionTester
import app.s3vault.domain.model.S3Config
import app.s3vault.domain.remote.RemoteKeys
import app.s3vault.settings.destinationOf

class ConnectionRepository(private val c: AppContainer) {

    /** Comprobación iniciada por el usuario: no se ejecuta nunca en segundo plano. */
    suspend fun test(config: S3Config, permissions: Boolean): ConnectionReport {
        val store = c.newObjectStore(config)
        try {
            val tester = ConnectionTester(store, RemoteKeys(config.prefix), c.credentials)
            val report = if (permissions) tester.testPermissions() else tester.testConnection()
            if (report.connected && c.settingsSnapshot().s3Config == config) {
                c.settings.setConnectionVerified(System.currentTimeMillis())
                c.settings.setQueueBlock(null)
            }
            return report
        } finally {
            store.close()
        }
    }

    /**
     * Guarda la configuración. Si el destino (bucket/prefijo) cambió, los estados de respaldo ya no son
     * ciertos y se reinician (solo metadatos locales; los objetos del destino anterior no se tocan).
     */
    suspend fun save(config: S3Config) {
        val previous = c.settingsSnapshot().lastDestination
        if (previous != null && previous != destinationOf(config)) {
            c.workScheduler.cancelBackup()
            c.db.media().resetForNewDestination()
            c.db.remoteObjects().clear()
        }
        c.settings.saveS3Config(config)
    }

    /**
     * Desconecta: elimina credenciales y configuración locales y detiene el trabajo programado.
     * No elimina ningún objeto en S3 ni fotos del dispositivo.
     */
    suspend fun disconnect() {
        c.workScheduler.cancelAll()
        c.db.media().cancelQueued()
        c.credentials.clear()
        c.settings.clearS3Config()
        c.progress.clear()
    }
}
