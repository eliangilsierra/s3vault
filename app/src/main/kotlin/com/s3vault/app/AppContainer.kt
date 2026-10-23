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
import com.s3vault.app.sync.CloudDeletionController
import com.s3vault.app.sync.LocalDeletionController
import com.s3vault.app.sync.RemotePreviewRepository
import com.s3vault.app.sync.EventLog
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
    val scheduler by lazy { WorkManagerScheduler(context) }
    private val mediaSource by lazy { MediaStoreSource(context) }

    val library: LibraryRepository by lazy {
        LibraryRepository(mediaSource, MediaIndexer(db)) { mediaAccess() }
    }
    val catalog by lazy { RemoteCatalog(db) }
    val backup by lazy { BackupController(db, settings, scheduler) }
    val albums by lazy { AlbumController(db) }
    val albumImporter by lazy { AlbumImporter(db, remoteProvider) }

    /** Test-only (like [S3RemoteStorage]'s own parameter): points the app's S3 client at a local mock server. Never set in production. */
    @androidx.annotation.VisibleForTesting
    var endpointOverride: String? = null

    val remoteProvider: RemoteContextProvider = CachingRemoteProvider(settings, credentials) { endpointOverride }

    val remoteLibrary by lazy { RemoteLibraryController(db, remoteProvider, catalog, scheduler, settings) }

    /** Previews of S3 objects, cached under cacheDir (the system may clear it; it only costs a few KB to refill). */
    val previews by lazy { RemotePreviewRepository(File(context.cacheDir, "remote-previews"), remoteProvider, ExifThumbnailReader()) }

    /** Two separate, explicit deletions: S3 objects, and the settling of files Android removed from the device. */
    val cloudDeletion by lazy { CloudDeletionController(db, remoteProvider) { previews.evict(it) } }
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

    val connectionTester = ConnectionTester(credentials)

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
            cached = RemoteContext(S3RemoteStorage(config, credentials, ep), RemoteKeys(config.prefix))
            cachedConfig = config
            cachedEndpoint = ep
        }
        cached
    }
}

/** Runs connection/permission checks for a config that may not be saved yet. */
class ConnectionTester(private val credentials: CredentialsRepository) {
    suspend fun checkConnection(config: S3Config) = withClient(config) { it.checkConnection() }
    suspend fun testPermissions(config: S3Config): PermissionReport = withClient(config) { it.testPermissions() }

    private suspend fun <T> withClient(config: S3Config, block: suspend (S3RemoteStorage) -> T): T {
        val client = S3RemoteStorage(config, credentials)
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
