package app.s3vault

import android.content.Context
import app.s3vault.auth.AwsIdentityCenterApi
import app.s3vault.auth.CredentialVault
import app.s3vault.auth.CredentialsManager
import app.s3vault.auth.IdentityCenterSignIn
import app.s3vault.data.local.AppDatabase
import app.s3vault.data.local.RoomBackupStore
import app.s3vault.data.local.RoomRemoteCatalogStore
import app.s3vault.data.local.RoomTransferLog
import app.s3vault.data.local.media.MediaStoreRestoreDestination
import app.s3vault.data.local.media.MediaStoreScanner
import app.s3vault.data.local.media.MediaStoreSource
import app.s3vault.data.remote.AwsObjectStore
import app.s3vault.data.remote.ObjectStore
import app.s3vault.data.remote.S3ClientFactory
import app.s3vault.data.remote.ScopedObjectStore
import app.s3vault.data.repo.AlbumRepository
import app.s3vault.data.repo.BackupRepository
import app.s3vault.data.repo.ConnectionRepository
import app.s3vault.data.repo.GalleryRepository
import app.s3vault.data.repo.RemoteLibraryRepository
import app.s3vault.domain.model.S3Config
import app.s3vault.domain.remote.RemoteKeys
import app.s3vault.security.SecretStore
import app.s3vault.security.keystore.AndroidKeystoreSecretStore
import app.s3vault.settings.AppSettings
import app.s3vault.settings.SettingsRepository
import app.s3vault.sync.work.BackupCoordinator
import app.s3vault.sync.work.TransferProgressTracker
import app.s3vault.sync.work.WorkScheduler
import kotlinx.coroutines.flow.first

/** Inyección manual de dependencias: un único grafo, creado perezosamente. */
class AppContainer(val context: Context) {
    val db: AppDatabase by lazy { AppDatabase.create(context) }
    val settings = SettingsRepository(context)

    // Seguridad y autenticación
    val secretStore: SecretStore by lazy { AndroidKeystoreSecretStore(context) }
    private val identityCenterApi = AwsIdentityCenterApi()
    val credentials: CredentialsManager by lazy {
        CredentialsManager(CredentialVault(secretStore), identityCenter = identityCenterApi)
    }
    val identityCenterSignIn: IdentityCenterSignIn by lazy { IdentityCenterSignIn(identityCenterApi, credentials) }

    // Local
    val scanner by lazy { MediaStoreScanner(context) }
    val mediaSource by lazy { MediaStoreSource(context) }
    val restoreDestination by lazy { MediaStoreRestoreDestination(context) }
    val backupStore by lazy { RoomBackupStore(db) }
    val transferLog by lazy { RoomTransferLog(db) }
    val remoteCatalogStore by lazy { RoomRemoteCatalogStore(db) }

    // Sincronización
    val progress = TransferProgressTracker()
    val workScheduler by lazy { WorkScheduler(context) }
    val backupCoordinator by lazy { BackupCoordinator(this, context) }

    // Repositorios
    val galleryRepository by lazy { GalleryRepository(context, db, scanner, settings) }
    val albumRepository by lazy { AlbumRepository(db) }
    val backupRepository by lazy { BackupRepository(db, settings, workScheduler, credentials) }
    val remoteLibraryRepository by lazy { RemoteLibraryRepository(this) }
    val connectionRepository by lazy { ConnectionRepository(this) }

    suspend fun settingsSnapshot(): AppSettings = settings.settings.first()

    /**
     * Arranque de la app: no depende de la memoria del proceso; solo vuelve a asegurar que el trabajo
     * persistente esté programado. La cola en sí vive en Room y sobrevive a reinicios.
     */
    suspend fun bootstrap() {
        val s = settingsSnapshot()
        if (s.s3Config == null || s.paused) return
        if (s.autoBackup) workScheduler.configureAutoBackup(true, s.wifiOnly)
        if (backupRepository.hasQueued()) workScheduler.enqueueBackup(s.wifiOnly)
    }

    /** Cliente S3 acotado al prefijo configurado. Quien lo crea debe cerrarlo. */
    fun newObjectStore(config: S3Config): ObjectStore =
        ScopedObjectStore(AwsObjectStore(S3ClientFactory.create(config, credentials), config), RemoteKeys(config.prefix))
}
