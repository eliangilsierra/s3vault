package com.s3vault.app.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.s3vault.app.AppContainer
import com.s3vault.app.auth.AuthStatus
import com.s3vault.app.auth.InvalidCredentialsInput
import com.s3vault.app.data.local.MediaItemEntity
import com.s3vault.app.domain.Album
import com.s3vault.app.domain.AuthMethod
import com.s3vault.app.domain.ConfigValidator
import com.s3vault.app.domain.MediaItem
import com.s3vault.app.domain.PermissionReport
import com.s3vault.app.domain.RemoteErrorKind
import com.s3vault.app.domain.RemoteException
import com.s3vault.app.domain.RemoteObject
import com.s3vault.app.domain.RemoteKeys
import com.s3vault.app.domain.S3Config
import com.s3vault.app.domain.SyncState
import com.s3vault.app.domain.TransferEvent
import com.s3vault.app.security.Redactor
import com.s3vault.app.settings.BackupSettings
import com.s3vault.app.settings.ConnectionStatus
import com.s3vault.app.settings.ThemeMode
import com.s3vault.app.sync.MediaAccess
import com.s3vault.app.sync.Progress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

class AppViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        GalleryViewModel::class.java -> GalleryViewModel(container)
        AlbumsViewModel::class.java -> AlbumsViewModel(container)
        BackupViewModel::class.java -> BackupViewModel(container)
        RemoteViewModel::class.java -> RemoteViewModel(container)
        S3SettingsViewModel::class.java -> S3SettingsViewModel(container)
        SecurityViewModel::class.java -> SecurityViewModel(container)
        GeneralSettingsViewModel::class.java -> GeneralSettingsViewModel(container)
        else -> throw IllegalArgumentException("Unknown ViewModel ${modelClass.name}")
    } as T
}

private fun <T> Flow<T>.eagerly(scope: kotlinx.coroutines.CoroutineScope, initial: T): StateFlow<T> =
    stateIn(scope, SharingStarted.WhileSubscribed(5_000), initial)

// ---------------------------------------------------------------- Gallery / viewer

data class GalleryUiState(
    val access: MediaAccess,
    val items: List<MediaItem>?, // null = still loading
    val selected: Set<Long> = emptySet(),
    val refreshing: Boolean = false,
    val error: String? = null,
    val albums: List<Album> = emptyList(),
    val message: String? = null,
)

class GalleryViewModel(private val c: AppContainer) : ViewModel() {
    private val access = MutableStateFlow(c.mediaAccess())
    private val selected = MutableStateFlow<Set<Long>>(emptySet())
    private val refreshing = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val message = MutableStateFlow<String?>(null)

    val uploads: StateFlow<Map<Long, Progress>> = c.progress.uploads

    private val items: Flow<List<MediaItem>> = c.db.media().observeGallery(true).map { l -> l.map(MediaItemEntity::toDomain) }
    private val albums = c.db.albums().observeAlbums().map { l -> l.map { it.toDomain() } }

    val state: StateFlow<GalleryUiState> = combine(
        listOf(access, items, selected, refreshing, error, albums, message),
    ) { v ->
        @Suppress("UNCHECKED_CAST")
        GalleryUiState(
            access = v[0] as MediaAccess,
            items = v[1] as List<MediaItem>,
            selected = v[2] as Set<Long>,
            refreshing = v[3] as Boolean,
            error = v[4] as String?,
            albums = v[5] as List<Album>,
            message = v[6] as String?,
        )
    }.eagerly(viewModelScope, GalleryUiState(c.mediaAccess(), null))

    /** Re-reads the permission state and rescans MediaStore on a background dispatcher (the scanner switches to IO). */
    fun refresh() {
        access.value = c.mediaAccess()
        viewModelScope.launch {
            refreshing.value = true
            try {
                c.library.refresh()
                error.value = null
            } catch (e: Exception) {
                error.value = "No se pudo leer la galería: " + Redactor.describe(e)
            } finally {
                refreshing.value = false
            }
        }
    }

    fun toggle(id: Long) = selected.update { if (id in it) it - id else it + id }
    fun clearSelection() { selected.value = emptySet() }
    fun selectAll(ids: List<Long>) { selected.value = ids.toSet() }
    fun dismissMessage() { message.value = null }

    fun backupSelected() {
        val ids = selected.value.toList()
        viewModelScope.launch {
            val n = c.backup.enqueue(ids)
            message.value = when {
                c.settings.currentS3Config() == null -> "$n en cola. Configura Amazon S3 para que se suban."
                n == 0 -> "Las fotos seleccionadas ya están respaldadas o en cola."
                else -> "$n elemento(s) en cola. El sistema decidirá cuándo subirlos según las restricciones (red, batería)."
            }
            clearSelection()
        }
    }

    fun addSelectedToAlbum(albumId: Long) {
        val ids = selected.value.toList()
        viewModelScope.launch { c.albums.add(albumId, ids); message.value = "Añadidas a un álbum."; clearSelection() }
    }

    fun createAlbumWithSelected(name: String) {
        val ids = selected.value.toList()
        viewModelScope.launch {
            val id = c.albums.create(name)
            c.albums.add(id, ids)
            message.value = "Álbum «$name» creado."
            clearSelection()
        }
    }
}

// ---------------------------------------------------------------- Albums

class AlbumsViewModel(private val c: AppContainer) : ViewModel() {
    val albums: StateFlow<List<Album>?> =
        c.db.albums().observeAlbums().map { l -> l.map { it.toDomain() } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val selected = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = selected.asStateFlow()
    val uploads = c.progress.uploads

    fun albumName(id: Long): Flow<String?> = c.db.albums().observeAlbum(id).map { it?.name }
    fun albumMedia(id: Long): Flow<List<MediaItem>> = c.db.albums().observeAlbumMedia(id).map { l -> l.map(MediaItemEntity::toDomain) }

    fun create(name: String) { viewModelScope.launch { c.albums.create(name) } }
    fun rename(id: Long, name: String) { viewModelScope.launch { c.albums.rename(id, name) } }
    fun delete(id: Long) { viewModelScope.launch { c.albums.delete(id) } }

    fun toggle(id: Long) = selected.update { if (id in it) it - id else it + id }
    fun clearSelection() { selected.value = emptySet() }
    fun removeSelected(albumId: Long) {
        val ids = selected.value.toList()
        viewModelScope.launch { c.albums.remove(albumId, ids); clearSelection() }
    }

    fun backupSelected() {
        val ids = selected.value.toList()
        viewModelScope.launch { c.backup.enqueue(ids); clearSelection() }
    }
}

// ---------------------------------------------------------------- Backup

data class BackupUiState(
    val backedUp: Int = 0,
    val pending: Int = 0,
    val queued: Int = 0,
    val inFlight: List<MediaItem> = emptyList(),
    val failed: List<MediaItem> = emptyList(),
    val lastBackupMs: Long? = null,
    val settings: BackupSettings = BackupSettings(),
    val needsCredentials: Boolean = false,
    val auth: AuthStatus = AuthStatus.Missing,
    val configured: Boolean = false,
    val events: List<TransferEvent> = emptyList(),
)

class BackupViewModel(private val c: AppContainer) : ViewModel() {
    val uploads = c.progress.uploads

    private val core: Flow<BackupUiState> = combine(
        c.db.media().observeCounts(),
        c.db.media().observeByStates(listOf(SyncState.UPLOADING, SyncState.VERIFYING)),
        c.db.media().observeByStates(listOf(SyncState.FAILED)),
        c.settings.lastSuccessfulTransferMs,
        c.settings.backup,
    ) { counts, flight, failed, last, settings ->
        fun n(s: SyncState) = counts.firstOrNull { it.syncState == s }?.n ?: 0
        BackupUiState(
            backedUp = n(SyncState.BACKED_UP),
            pending = n(SyncState.LOCAL_ONLY),
            queued = n(SyncState.QUEUED),
            inFlight = flight.map(MediaItemEntity::toDomain),
            failed = failed.map(MediaItemEntity::toDomain),
            lastBackupMs = last,
            settings = settings,
        )
    }

    val state: StateFlow<BackupUiState> = combine(
        core, c.settings.needsCredentials, c.credentials.status, c.settings.s3Config, c.db.events().observeRecent(30),
    ) { base, needs, auth, cfg, events ->
        base.copy(needsCredentials = needs, auth = auth, configured = cfg != null, events = events.map { it.toDomain() })
    }.eagerly(viewModelScope, BackupUiState())

    fun backupPending() {
        viewModelScope.launch { c.backup.enqueuePending(c.settings.currentBackup().includeVideos) }
    }
    fun pause() { viewModelScope.launch { c.backup.pause() } }
    fun resume() { viewModelScope.launch { c.backup.resume() } }
    fun retryFailed() { viewModelScope.launch { c.backup.retryFailed() } }
    fun cancelQueue() { viewModelScope.launch { c.backup.cancelQueued() } }
}

// ---------------------------------------------------------------- Remote library

data class RemoteUiState(
    val configured: Boolean = false,
    val items: List<RemoteObject> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val needsCredentials: Boolean = false,
    val message: String? = null,
    val detail: RemoteDetail? = null,
)

data class RemoteDetail(val key: String, val lines: List<Pair<String, String>>?, val error: String?)

class RemoteViewModel(private val c: AppContainer) : ViewModel() {
    val downloads: StateFlow<Map<String, Progress>> = c.progress.downloads

    private val transient = MutableStateFlow(RemoteUiState())
    private val selection = MutableStateFlow<Set<String>>(emptySet())
    val selectedKeys: StateFlow<Set<String>> = selection.asStateFlow()

    val state: StateFlow<RemoteUiState> = combine(
        c.db.remote().observeAll(), c.db.media().observeBackedUpKeys(), c.settings.s3Config, transient,
    ) { remote, localKeys, cfg, t ->
        val local = localKeys.toSet()
        t.copy(configured = cfg != null, items = remote.map { it.toDomain(it.key in local) })
    }.eagerly(viewModelScope, RemoteUiState())

    fun toggle(key: String) = selection.update { if (key in it) it - key else it + key }
    fun clearSelection() { selection.value = emptySet() }

    fun refresh() {
        viewModelScope.launch {
            transient.update { it.copy(loading = true, error = null, needsCredentials = false) }
            try {
                if (!c.remoteLibrary.refresh()) transient.update { it.copy(error = "Amazon S3 no está configurado.") }
            } catch (e: RemoteException) {
                transient.update { it.copy(error = e.message, needsCredentials = e.needsCredentials) }
            } catch (e: Exception) {
                transient.update { it.copy(error = RemoteErrorKind.UNKNOWN.userMessage) }
            } finally {
                transient.update { it.copy(loading = false) }
            }
        }
    }

    fun restoreSelected() {
        val keys = selection.value.toList()
        viewModelScope.launch {
            val n = c.remoteLibrary.restore(keys)
            transient.update {
                it.copy(message = "$n fotografía(s) en cola de restauración. Se guardarán como copias nuevas en Imágenes/S3Vault; nunca se sobrescribe un archivo existente.")
            }
            clearSelection()
        }
    }

    fun cancelRestore(key: String) { viewModelScope.launch { c.remoteLibrary.cancelRestore(listOf(key)) } }
    fun dismissMessage() = transient.update { it.copy(message = null) }
    fun dismissDetail() = transient.update { it.copy(detail = null) }

    /** HEAD request for the metadata dialog (one request, only when the user asks). */
    fun showDetail(obj: RemoteObject) {
        viewModelScope.launch {
            transient.update { it.copy(detail = RemoteDetail(obj.key, null, null)) }
            try {
                val ctx = c.remoteProvider.get() ?: throw RemoteException(RemoteErrorKind.NOT_CONFIGURED)
                val meta = ctx.storage.head(obj.key) ?: throw RemoteException(RemoteErrorKind.OBJECT_NOT_FOUND)
                val lines = buildList {
                    add("Nombre" to obj.displayName)
                    add("Tamaño" to formatBytes(meta.sizeBytes))
                    meta.lastModifiedMs?.let { add("Modificado en S3" to formatDateTime(it)) }
                    meta.contentType?.let { add("Tipo" to it) }
                    meta.userMetadata["captured-at-ms"]?.toLongOrNull()?.let { add("Fecha de captura" to formatDateTime(it)) }
                    meta.userMetadata["original-name"]?.let { add("Nombre original" to java.net.URLDecoder.decode(it, "UTF-8")) }
                    add("SHA-256 (S3)" to (meta.checksumSha256Base64 ?: "no disponible"))
                    add("Versión (SHA-256 hex)" to obj.versionId)
                    add("Clave" to obj.key)
                }
                transient.update { it.copy(detail = RemoteDetail(obj.key, lines, null)) }
            } catch (e: RemoteException) {
                transient.update { it.copy(detail = RemoteDetail(obj.key, null, e.message)) }
            }
        }
    }
}

// ---------------------------------------------------------------- S3 settings

data class S3Form(
    val region: String = "",
    val bucket: String = "",
    val prefix: String = "",
    val method: AuthMethod = AuthMethod.TEMPORARY,
)

data class S3UiState(
    val form: S3Form = S3Form(),
    val issues: Map<ConfigValidator.Field, String> = emptyMap(),
    val saved: Boolean = false,
    val dirty: Boolean = false,
    val busy: String? = null,
    val resultOk: Boolean? = null,
    val resultMessage: String? = null,
    val permissions: PermissionReport? = null,
    val connection: ConnectionStatus = ConnectionStatus(null, null, null),
    val auth: AuthStatus = AuthStatus.Missing,
    val loaded: Boolean = false,
)

class S3SettingsViewModel(private val c: AppContainer) : ViewModel() {
    private val ui = MutableStateFlow(S3UiState())
    val state: StateFlow<S3UiState> = combine(ui, c.settings.connection, c.credentials.status) { u, conn, auth ->
        u.copy(connection = conn, auth = auth)
    }.eagerly(viewModelScope, S3UiState())

    init {
        viewModelScope.launch {
            val cfg = c.settings.currentS3Config()
            ui.update {
                it.copy(
                    form = cfg?.let { s -> S3Form(s.region, s.bucket, s.prefix, s.authMethod) } ?: it.form,
                    saved = cfg != null, loaded = true,
                )
            }
        }
    }

    fun edit(transform: (S3Form) -> S3Form) = ui.update { it.copy(form = transform(it.form), dirty = true, issues = emptyMap(), resultOk = null, resultMessage = null, permissions = null) }

    private fun validate(f: S3Form): Map<ConfigValidator.Field, String> =
        ConfigValidator.validateConfig(f.region, f.bucket, f.prefix).associate { it.field to it.message }

    private fun config(f: S3Form) = S3Config(f.region.trim(), f.bucket.trim(), RemoteKeys.normalizePrefix(f.prefix), f.method)

    fun save() {
        val f = ui.value.form
        val issues = validate(f)
        if (issues.isNotEmpty()) { ui.update { it.copy(issues = issues) }; return }
        viewModelScope.launch {
            c.settings.saveS3Config(config(f))
            ui.update { it.copy(form = f.copy(prefix = RemoteKeys.normalizePrefix(f.prefix)), saved = true, dirty = false, resultOk = true, resultMessage = "Configuración guardada.") }
        }
    }

    /** ListObjectsV2 maxKeys=1 under the prefix: validates bucket, region and credentials with a single cheap request. */
    fun testConnection() = runTest("Comprobando conexión…") { cfg ->
        c.connectionTester.checkConnection(cfg)
        c.settings.saveConnectionResult(true, "Conexión correcta", System.currentTimeMillis())
        ui.update { it.copy(resultOk = true, resultMessage = "Conexión correcta: el bucket existe, la región es válida y las credenciales son aceptadas.") }
    }

    fun testPermissions() = runTest("Comprobando permisos…") { cfg ->
        val report = c.connectionTester.testPermissions(cfg)
        val ok = report.allAllowed
        c.settings.saveConnectionResult(ok, if (ok) "Permisos correctos" else "Permisos incompletos", System.currentTimeMillis())
        ui.update { it.copy(permissions = report, resultOk = ok, resultMessage = if (ok) "Los permisos mínimos (List, Put, Get) funcionan." else "Faltan permisos. Revisa la política IAM (docs/iam-policy.md).") }
    }

    private fun runTest(label: String, block: suspend (S3Config) -> Unit) {
        val f = ui.value.form
        val issues = validate(f)
        if (issues.isNotEmpty()) { ui.update { it.copy(issues = issues) }; return }
        if (ui.value.dirty) { ui.update { it.copy(resultOk = false, resultMessage = "Guarda la configuración antes de probarla.") }; return }
        viewModelScope.launch {
            ui.update { it.copy(busy = label, resultOk = null, resultMessage = null, permissions = null) }
            try {
                block(config(f))
            } catch (e: RemoteException) {
                c.settings.saveConnectionResult(false, e.kind.name, System.currentTimeMillis())
                ui.update { it.copy(resultOk = false, resultMessage = e.message ?: e.kind.userMessage) }
            } catch (e: Exception) {
                com.s3vault.app.security.SafeLog.e("S3SettingsViewModel", "Connection test failed unexpectedly", e)
                ui.update { it.copy(resultOk = false, resultMessage = RemoteErrorKind.UNKNOWN.userMessage + " (" + e::class.java.simpleName + ")") }
            } finally {
                ui.update { it.copy(busy = null) }
            }
        }
    }

    /** Removes local credentials and the bucket configuration. Never deletes anything in S3. */
    fun disconnect() {
        viewModelScope.launch {
            c.credentials.clear()
            c.settings.clearS3Config()
            c.scheduler.cancelBackup()
            c.scheduler.cancelRestore()
            ui.update { S3UiState(loaded = true, resultOk = true, resultMessage = "Desconectado. Credenciales eliminadas de este dispositivo. Los objetos en S3 no se han tocado.") }
        }
    }
}

// ---------------------------------------------------------------- Security & credentials

data class SecurityUiState(
    val method: AuthMethod = AuthMethod.TEMPORARY,
    val auth: AuthStatus = AuthStatus.Missing,
    val error: String? = null,
    val message: String? = null,
    val pasted: String = "",
    val accessKey: String = "",
    val secret: String = "",
    val sessionToken: String = "",
    val expiration: String = "",
)

class SecurityViewModel(private val c: AppContainer) : ViewModel() {
    private val ui = MutableStateFlow(SecurityUiState())
    val state: StateFlow<SecurityUiState> = combine(ui, c.credentials.status, c.settings.s3Config) { u, auth, cfg ->
        u.copy(auth = auth, method = if (u.method == AuthMethod.TEMPORARY && cfg != null) cfg.authMethod else u.method)
    }.eagerly(viewModelScope, SecurityUiState())

    fun setMethod(m: AuthMethod) {
        ui.update { it.copy(method = m, error = null, message = null) }
        viewModelScope.launch { c.settings.currentS3Config()?.let { c.settings.saveS3Config(it.copy(authMethod = m)) } }
    }

    fun update(transform: (SecurityUiState) -> SecurityUiState) = ui.update { transform(it).copy(error = null, message = null) }

    /** Parses pasted AWS CLI output into the individual fields (so the user can review before saving). */
    fun parsePasted() {
        val parsed = com.s3vault.app.auth.TemporaryCredentialsParser.parse(ui.value.pasted)
        if (parsed == null) {
            ui.update { it.copy(error = "No se reconoció el formato. Pega el JSON de la CLI de AWS o líneas AWS_ACCESS_KEY_ID=…") }
        } else {
            ui.update {
                it.copy(
                    accessKey = parsed.accessKeyId, secret = parsed.secretAccessKey, sessionToken = parsed.sessionToken.orEmpty(),
                    expiration = parsed.expiration?.toString().orEmpty(), pasted = "", error = null,
                    message = "Datos leídos. Revisa la expiración y guarda.",
                )
            }
        }
    }

    fun saveTemporary() {
        val s = ui.value
        try {
            val exp = com.s3vault.app.auth.TemporaryCredentialsParser.parseInstant(s.expiration)
            c.credentials.saveTemporary(s.accessKey, s.secret, s.sessionToken, exp)
            afterSaved(AuthMethod.TEMPORARY)
        } catch (e: InvalidCredentialsInput) {
            ui.update { it.copy(error = e.message) }
        }
    }

    fun saveStatic() {
        val s = ui.value
        try {
            c.credentials.saveStatic(s.accessKey, s.secret)
            afterSaved(AuthMethod.STATIC)
        } catch (e: InvalidCredentialsInput) {
            ui.update { it.copy(error = e.message) }
        }
    }

    private fun afterSaved(method: AuthMethod) {
        // Secrets leave UI state immediately; they live only in the encrypted store.
        ui.update { it.copy(accessKey = "", secret = "", sessionToken = "", expiration = "", pasted = "", message = "Credenciales guardadas de forma cifrada en este dispositivo.") }
        viewModelScope.launch {
            c.settings.currentS3Config()?.let { c.settings.saveS3Config(it.copy(authMethod = method)) }
            c.backup.onCredentialsRenewed()
        }
    }

    fun delete() {
        c.credentials.clear()
        ui.update { it.copy(message = "Credenciales eliminadas de este dispositivo.") }
    }

    fun refreshStatus() = c.credentials.refreshStatus()
}

// ---------------------------------------------------------------- General settings

data class GeneralUiState(val theme: ThemeMode = ThemeMode.SYSTEM, val backup: BackupSettings = BackupSettings())

class GeneralSettingsViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<GeneralUiState> = combine(c.settings.theme, c.settings.backup) { t, b -> GeneralUiState(t, b) }
        .eagerly(viewModelScope, GeneralUiState())

    fun setTheme(t: ThemeMode) { viewModelScope.launch { c.settings.setTheme(t) } }
    fun setWifiOnly(v: Boolean) = change {
        c.settings.setWifiOnly(v)
        // WorkManager constraints are fixed per request: re-enqueue pending work under the new network rule.
        val s = c.settings.currentBackup()
        if (c.db.media().queuedCount() > 0 && !s.paused) {
            c.scheduler.cancelBackup()
            c.scheduler.kickBackup(s.wifiOnly)
        }
    }
    fun setIncludeVideos(v: Boolean) = change { c.settings.setIncludeVideos(v) }
    fun setAutoBackup(v: Boolean) = change { c.settings.setAutoBackup(v) }
    fun setInterval(h: Int) = change { c.settings.setIntervalHours(h) }
    fun setConcurrency(n: Int) = change { c.settings.setMaxConcurrency(n) }

    private fun change(block: suspend () -> Unit) {
        viewModelScope.launch {
            block()
            c.scheduler.configureAuto(c.settings.currentBackup())
        }
    }
}
