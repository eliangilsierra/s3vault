package com.s3vault.app

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.s3vault.app.auth.CredentialsRepository
import com.s3vault.app.data.local.AppDatabase
import com.s3vault.app.data.local.MediaChangeTracker
import com.s3vault.app.data.local.MediaIndexer
import com.s3vault.app.data.local.ExifThumbnailReader
import com.s3vault.app.data.local.MediaStoreGateway
import com.s3vault.app.data.local.MediaStoreSource
import com.s3vault.app.data.local.MediaStoreThumbnailMaker
import com.s3vault.app.data.remote.S3RemoteStorage
import com.s3vault.app.domain.PermissionReport
import com.s3vault.app.domain.RemoteKeys
import com.s3vault.app.domain.S3Config
import com.s3vault.app.security.KeystoreSecretStore
import com.s3vault.app.settings.AppSettings
import com.s3vault.app.sync.AlbumController
import com.s3vault.app.sync.AlbumImporter
import com.s3vault.app.sync.BackupController
import com.s3vault.app.sync.BackupEngine
import com.s3vault.app.sync.CloudDatesEngine
import com.s3vault.app.sync.CloudDeletionController
import com.s3vault.app.sync.DayKeyMaintainer
import com.s3vault.app.sync.LocalDeletionController
import com.s3vault.app.sync.RemotePreviewRepository
import com.s3vault.app.sync.EventLog
import com.s3vault.app.sync.InventoryStore
import com.s3vault.app.sync.LibraryRepository
import com.s3vault.app.sync.MediaAccess
import com.s3vault.app.sync.RemoteCatalog
import com.s3vault.app.sync.RemoteContext
import com.s3vault.app.sync.RemoteContextProvider
import com.s3vault.app.sync.RemoteLibraryController
import com.s3vault.app.sync.RestoreEngine
import com.s3vault.app.sync.RestoreSink
import com.s3vault.app.sync.TransferProgress
import com.s3vault.app.sync.WorkManagerScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/** Manual dependency graph (no DI framework: the app is small enough that it would only add weight). */
class AppContainer(private val context: Context) {
    val appContext: Context get() = context
    val db: AppDatabase by lazy { AppDatabase.create(context) }
    val settings = AppSettings(context)
    val credentials: CredentialsRepository by lazy { CredentialsRepository(KeystoreSecretStore(context)) }
    val progress = TransferProgress()
    val events by lazy { EventLog(db) }
    val jobs by lazy { com.s3vault.app.sync.JobRecorder(db) }
    /** When this process began: a job that was RUNNING before that belongs to a process that died. */
    val processStartMs: Long = System.currentTimeMillis()
    val scheduler by lazy { WorkManagerScheduler(context) }
    private val mediaSource by lazy { MediaStoreSource(context) }

    /** Counts MediaStore changes, so returning to the app only rescans the library when something really changed. */
    val mediaChanges: MediaChangeTracker by lazy { MediaChangeTracker(context).also { it.start() } }

    val library: LibraryRepository by lazy {
        LibraryRepository(mediaSource, MediaIndexer(db), { mediaAccess() }, beforeScan = { dayKeys.ensureCurrentZone() }, changes = mediaChanges)
    }
    val catalog by lazy {
        RemoteCatalog(
            db,
            onListed = { settings.saveRemoteListing(it) },
            lastCompleteListing = { settings.remoteListing.first()?.takeIf { it.complete } },
            inventoryStore = InventoryStore { settings.saveInventory(it) },
        )
    }
    val dayKeys by lazy { DayKeyMaintainer(db, settings) }
    val libraryStore by lazy { com.s3vault.app.presentation.library.LibraryStore(settings) }
    val cloudDates by lazy { CloudDatesEngine(db, remoteProvider, credentials, catalog) }
    val backup by lazy { BackupController(db, settings, scheduler) }
    val albums by lazy { AlbumController(db) }
    val albumImporter by lazy { AlbumImporter(db, remoteProvider, refreshListing = { remoteLibrary.refresh() }) }

    /** Test-only (like [S3RemoteStorage]'s own parameter): points the app's S3 client at a local mock server. Never set in production. */
    @androidx.annotation.VisibleForTesting
    var endpointOverride: String? = null

    val remoteProvider: RemoteContextProvider = CachingRemoteProvider(settings, credentials) { endpointOverride }

    val remoteLibrary by lazy { RemoteLibraryController(db, remoteProvider, catalog, scheduler, settings) }

    /** Previews of S3 objects, cached under cacheDir (the system may clear it; it only costs a few KB to refill). */
    val previews by lazy { RemotePreviewRepository(File(context.cacheDir, "remote-previews"), remoteProvider, ExifThumbnailReader()) }

    /** Set while a deletion (device or S3) runs anywhere in the app, so two bulk deletions never overlap. */
    val deleteGate = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Two separate, explicit deletions: S3 objects, and the settling of files Android removed from the device. */
    val cloudDeletion by lazy { CloudDeletionController(db, remoteProvider, { previews.evict(it) }, events) }
    val mediaGateway by lazy { MediaStoreGateway(context) }
    val localDeletion by lazy { LocalDeletionController(db, mediaGateway) }

    val backupEngine by lazy {
        BackupEngine(
            db = db, remote = remoteProvider, credentials = credentials, reader = mediaSource,
            progress = progress, events = events, catalog = catalog,
            onTransferSuccess = { settings.markTransferSuccess(it) },
            thumbnails = MediaStoreThumbnailMaker(context),
            previewsEnabled = { settings.currentBackup().cloudPreviews },
        )
    }

    val restoreEngine by lazy {
        RestoreEngine(
            db = db, remote = remoteProvider, credentials = credentials,
            sink = MediaStoreRestoreSink(context), partDir = File(context.cacheDir, "restore"),
            progress = progress, events = events,
        )
    }

    val integrityAuditor by lazy {
        com.s3vault.app.sync.IntegrityAuditor(
            db, remoteProvider, credentials, catalog, jobs, lastListing = { settings.remoteListing.first() },
        )
    }
    /** Where the optional semantic-search model lives: the app's private storage, unreadable by other apps and removed with the app. */
    val modelStore by lazy { com.s3vault.app.domain.semantic.ModelPackStore(File(context.filesDir, "models")) }
    val modelDownloader by lazy { com.s3vault.app.sync.ModelDownloader(modelStore) }
    val embedderProvider by lazy {
        com.s3vault.app.data.local.ClipEmbedderProvider(context, modelStore, com.s3vault.app.domain.semantic.ModelPacks.ClipB32)
    }
    val semanticIndexer by lazy { com.s3vault.app.sync.SemanticIndexer(db, embedderProvider, jobs) }
    val semanticSearcher by lazy { com.s3vault.app.sync.SemanticSearcher(db) }
    val duplicateScanner by lazy {
        com.s3vault.app.sync.DuplicateScanner(db, mediaSource, com.s3vault.app.data.local.AndroidImageSignature(context), jobs)
    }
    val spaceRecovery by lazy {
        com.s3vault.app.sync.SpaceRecoveryController(db, integrityAuditor, refreshIndex = { library.refresh(force = true) })
    }
    val integrityRepair by lazy { com.s3vault.app.sync.IntegrityRepair(db, backup) }

    /** Private albums: the open/locked state and the vault key live in [privateSession] and only in memory. */
    val privateSession by lazy { com.s3vault.app.domain.privacy.PrivateSession() }
    /** Test-only: a keyring that replaces the Keystore one (set before the private vault is first used). Never set in production. */
    @androidx.annotation.VisibleForTesting
    var keyringOverride: com.s3vault.app.domain.privacy.VaultKeyring? = null
    private val keystoreKeyring by lazy { com.s3vault.app.data.privacy.KeystoreKeyring(context) }
    /** Forwards to the override or the Keystore keyring at call time, so the vault store (built early) sees a test's replacement. */
    val vaultKeyring: com.s3vault.app.domain.privacy.VaultKeyring = object : com.s3vault.app.domain.privacy.VaultKeyring {
        private val target get() = keyringOverride ?: keystoreKeyring
        override fun isSecure() = target.isSecure()
        override suspend fun create(prompt: com.s3vault.app.domain.privacy.UnlockPrompt) = target.create(prompt)
        override suspend fun unwrap(wrapped: com.s3vault.app.domain.privacy.WrappedKey, prompt: com.s3vault.app.domain.privacy.UnlockPrompt) = target.unwrap(wrapped, prompt)
        override fun deleteKey() = target.deleteKey()
    }

    /** Private screens are kept out of screenshots and the recents preview (always true in the app; instrumented tests that photograph them turn it off). */
    var secureWindows: Boolean = true
    val privateVault by lazy {
        com.s3vault.app.data.privacy.PrivateVaultStore(db.privateVault(), vaultKeyring, privateSession, File(context.filesDir, "vault"))
    }
    val privateMover by lazy { com.s3vault.app.data.privacy.PrivateMover(privateVault, mediaSource, MediaStoreThumbnailMaker(context)) }
    val privateImages by lazy { com.s3vault.app.data.privacy.PrivateImageCache(privateVault).also { c -> privateSession.addLockListener { c.clear() } } }

    val connectionTester = ConnectionTester(credentials) { endpointOverride }

    fun mediaAccess(): MediaAccess {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 33 -> when {
                granted(Manifest.permission.READ_MEDIA_IMAGES) -> MediaAccess.FULL
                Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.PARTIAL
                else -> MediaAccess.NONE
            }
            granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> MediaAccess.FULL
            else -> MediaAccess.NONE
        }
    }
}

/** Builds (and caches per configuration) the S3 client. Credentials are resolved by the SDK on every request. */
private class CachingRemoteProvider(
    private val settings: AppSettings,
    private val credentials: CredentialsRepository,
    private val endpoint: () -> String?,
) : RemoteContextProvider {
    private val mutex = Mutex()
    private var cachedConfig: S3Config? = null
    private var cachedEndpoint: String? = null
    private var cached: RemoteContext? = null

    override suspend fun get(): RemoteContext? = mutex.withLock {
        val config = settings.currentS3Config() ?: return null
        val ep = endpoint()
        if (config != cachedConfig || ep != cachedEndpoint) {
            (cached?.storage as? S3RemoteStorage)?.close()
            cached = RemoteContext(S3RemoteStorage(config, credentials, ep), RemoteKeys(config.prefix), config)
            cachedConfig = config
            cachedEndpoint = ep
        }
        cached
    }
}

/** Runs connection/permission checks for a config that may not be saved yet. */
class ConnectionTester(private val credentials: CredentialsRepository, private val endpoint: () -> String? = { null }) {
    suspend fun checkConnection(config: S3Config) = withClient(config) { it.checkConnection() }
    suspend fun testPermissions(config: S3Config): PermissionReport = withClient(config) { it.testPermissions() }

    private suspend fun <T> withClient(config: S3Config, block: suspend (S3RemoteStorage) -> T): T {
        val client = S3RemoteStorage(config, credentials, endpoint())
        try {
            return block(client)
        } finally {
            client.close()
        }
    }
}

/** Restores into MediaStore (Pictures/S3Vault or Movies/S3Vault). MediaStore renames on collisions: no silent overwrite. */
class MediaStoreRestoreSink(private val context: Context) : RestoreSink {
    override suspend fun create(displayName: String, mimeType: String): RestoreSink.Target = withContext(Dispatchers.IO) {
        val isVideo = mimeType.startsWith("video/")
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (isVideo) "Movies" else "Pictures") + "/S3Vault")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: throw java.io.IOException("Could not create destination")
        val out = resolver.openOutputStream(uri, "w") ?: run {
            resolver.delete(uri, null, null)
            throw java.io.IOException("Could not open destination")
        }
        object : RestoreSink.Target {
            override val output: OutputStream = out
            override suspend fun commit(): String = withContext(Dispatchers.IO) {
                out.flush(); out.close()
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                uri.toString()
            }
            override suspend fun abort() = withContext(Dispatchers.IO) {
                runCatching { out.close() }
                resolver.delete(uri, null, null)
                Unit
            }
        }
    }
}
