package com.s3vault.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.s3vault.app.domain.AuthMethod
import com.s3vault.app.domain.RemoteKeys
import com.s3vault.app.domain.S3Config
import com.s3vault.app.domain.library.GroupBy
import com.s3vault.app.domain.library.SortField
import com.s3vault.app.domain.cost.StorageInventory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class BackupSettings(
    val autoBackup: Boolean = false,
    val wifiOnly: Boolean = true,
    val includeVideos: Boolean = false,
    val intervalHours: Int = 6,
    val maxConcurrency: Int = 2,
    /** User pressed "Pause": queued work is kept but no worker runs. */
    val paused: Boolean = false,
    /** Upload a small preview next to every backed-up photo so S3 photos can be previewed without downloading them. */
    val cloudPreviews: Boolean = true,
)

/** How dense the photo grid is: the minimum width of a cell. */
enum class GridDensity(val minCellDp: Int, val label: String) {
    COMFORTABLE(160, "Cómoda"),
    MEDIUM(112, "Media"),
    COMPACT(84, "Compacta"),
}

enum class CloudViewMode { GRID, LIST }

/** How each gallery is sorted, grouped and laid out. Remembered between sessions; filters and searches are not. */
data class LibraryPrefs(
    val localSort: SortField = SortField.CAPTURE_DATE,
    val localDescending: Boolean = true,
    val localGroup: GroupBy = GroupBy.DAY,
    val cloudSort: SortField = SortField.CAPTURE_DATE,
    val cloudDescending: Boolean = true,
    val cloudGroup: GroupBy = GroupBy.DAY,
    val density: GridDensity = GridDensity.MEDIUM,
    val cloudMode: CloudViewMode = CloudViewMode.GRID,
)
/** The last time the bucket inventory was tried: when, and why it failed (null = it worked). */
data class InventoryAttempt(val atMs: Long, val error: String?)

data class ConnectionStatus(
    val verifiedAtMs: Long?,
    val lastResultOk: Boolean?,
    val lastMessage: String?,
)

/**
 * One DataStore per settings file, created on first use. It is the same file the `preferencesDataStore("settings")` delegate used
 * (`files/datastore/settings.preferences_pb`), so existing installations keep their preferences. Keying by path, not by process,
 * is what lets every test with its own temporary files directory get its own store instead of a stale one from an earlier test.
 */
private val settingsStores = HashMap<String, DataStore<Preferences>>()
private val settingsScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

private val Context.dataStore: DataStore<Preferences>
    get() {
        val file = java.io.File(applicationContext.filesDir, "datastore/settings.preferences_pb")
        return synchronized(settingsStores) {
            settingsStores.getOrPut(file.path) { PreferenceDataStoreFactory.create(scope = settingsScope, produceFile = { file }) }
        }
    }

/** Non-secret preferences. Credentials never pass through here. */
class AppSettings(private val context: Context) {
    private val store get() = context.dataStore

    private object K {
        val region = stringPreferencesKey("region")
        val bucket = stringPreferencesKey("bucket")
        val prefix = stringPreferencesKey("prefix")
        val authMethod = stringPreferencesKey("auth_method")
        val autoBackup = booleanPreferencesKey("auto_backup")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val includeVideos = booleanPreferencesKey("include_videos")
        val intervalHours = intPreferencesKey("interval_hours")
        val maxConcurrency = intPreferencesKey("max_concurrency")
        val paused = booleanPreferencesKey("paused")
        val cloudPreviews = booleanPreferencesKey("cloud_previews")
        val theme = stringPreferencesKey("theme")
        val connVerifiedAt = longPreferencesKey("conn_verified_at")
        val connOk = booleanPreferencesKey("conn_ok")
        val connMessage = stringPreferencesKey("conn_message")
        val needsCredentials = booleanPreferencesKey("needs_credentials")
        val lastSuccessfulTransfer = longPreferencesKey("last_success")
        val listedAt = longPreferencesKey("remote_listed_at")
        val listedComplete = booleanPreferencesKey("remote_listed_complete")
        val listedCount = intPreferencesKey("remote_listed_count")
        val listedSource = stringPreferencesKey("remote_listed_source")
        val auditIntervalDays = intPreferencesKey("audit_interval_days")
        val privateAutoLockMs = longPreferencesKey("private_auto_lock_ms")
        val onboardingProgress = stringPreferencesKey("onboarding_progress")
        val semanticEnabled = booleanPreferencesKey("semantic_enabled")
        val semanticCharging = booleanPreferencesKey("semantic_only_charging")
        val modelWifiOnly = booleanPreferencesKey("model_wifi_only")
        val faceEnabled = booleanPreferencesKey("face_enabled")
        val faceCharging = booleanPreferencesKey("face_only_charging")
        val faceGentle = booleanPreferencesKey("face_gentle")
        val faceIntroSeen = booleanPreferencesKey("face_intro_seen")
        val spaceFavorites = booleanPreferencesKey("space_excl_favorites")
        val spaceRecentDays = intPreferencesKey("space_recent_days")
        val spaceBackedDays = intPreferencesKey("space_backed_days")
        val spaceFolders = androidx.datastore.preferences.core.stringSetPreferencesKey("space_excl_folders")
        val spaceVideos = booleanPreferencesKey("space_videos")
        val spaceAlbums = booleanPreferencesKey("space_keep_albums")
        val spaceVerifiedHours = intPreferencesKey("space_verified_hours")
        val dayZone = stringPreferencesKey("day_zone")
        val libLocalSort = stringPreferencesKey("lib_local_sort")
        val libLocalDesc = booleanPreferencesKey("lib_local_desc")
        val libLocalGroup = stringPreferencesKey("lib_local_group")
        val libCloudSort = stringPreferencesKey("lib_cloud_sort")
        val libCloudDesc = booleanPreferencesKey("lib_cloud_desc")
        val libCloudGroup = stringPreferencesKey("lib_cloud_group")
        val libDensity = stringPreferencesKey("lib_density")
        val libCloudMode = stringPreferencesKey("lib_cloud_mode")
        val inventory = stringPreferencesKey("storage_inventory")
        val inventoryAttemptMs = longPreferencesKey("storage_inventory_attempt_ms")
        val inventoryError = stringPreferencesKey("storage_inventory_error")
    }

    val s3Config: Flow<S3Config?> = store.data.map { p ->
        val region = p[K.region].orEmpty()
        val bucket = p[K.bucket].orEmpty()
        if (region.isBlank() || bucket.isBlank()) null
        else S3Config(
            region = region,
            bucket = bucket,
            prefix = RemoteKeys.normalizePrefix(p[K.prefix].orEmpty()),
            authMethod = p[K.authMethod]?.let { runCatching { AuthMethod.valueOf(it) }.getOrNull() } ?: AuthMethod.TEMPORARY,
        )
    }

    val backup: Flow<BackupSettings> = store.data.map { p ->
        BackupSettings(
            autoBackup = p[K.autoBackup] ?: false,
            wifiOnly = p[K.wifiOnly] ?: true,
            includeVideos = p[K.includeVideos] ?: false,
            intervalHours = p[K.intervalHours] ?: 6,
            maxConcurrency = (p[K.maxConcurrency] ?: 2).coerceIn(1, 4),
            paused = p[K.paused] ?: false,
            cloudPreviews = p[K.cloudPreviews] ?: true,
        )
    }

    val theme: Flow<ThemeMode> = store.data.map { p ->
        p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
    }

    val connection: Flow<ConnectionStatus> = store.data.map { p ->
        ConnectionStatus(p[K.connVerifiedAt], p[K.connOk], p[K.connMessage])
    }

    /** True while a worker stopped because credentials are missing/expired; cleared when new ones are saved. */
    val needsCredentials: Flow<Boolean> = store.data.map { it[K.needsCredentials] ?: false }

    val lastSuccessfulTransferMs: Flow<Long?> = store.data.map { it[K.lastSuccessfulTransfer] }

    /** The last pass over the bucket, or null if it was never listed on this device. */
    val remoteListing: Flow<com.s3vault.app.sync.ListingResult?> = store.data.map { p ->
        p[K.listedAt]?.let { com.s3vault.app.sync.ListingResult(it, p[K.listedComplete] ?: false, p[K.listedCount] ?: 0, p[K.listedSource].orEmpty()) }
    }

    suspend fun saveRemoteListing(r: com.s3vault.app.sync.ListingResult) {
        store.edit {
            // An incomplete pass must not overwrite the time of the last complete one: the UI shows both honestly.
            if (r.complete) { it[K.listedAt] = r.atMs; it[K.listedCount] = r.count; it[K.listedSource] = r.source }
            it[K.listedComplete] = r.complete
        }
    }

    /** Zone the day keys were last computed in; null before the first computation. */
    suspend fun dayZone(): String? = store.data.first()[K.dayZone]
    suspend fun setDayZone(id: String) = store.edit { it[K.dayZone] = id }.let { }

    val libraryPrefs: Flow<LibraryPrefs> = store.data.map { p ->
        fun <E : Enum<E>> parse(raw: String?, all: Array<E>, default: E): E = all.firstOrNull { it.name == raw } ?: default
        LibraryPrefs(
            localSort = parse(p[K.libLocalSort], SortField.entries.toTypedArray(), SortField.CAPTURE_DATE),
            localDescending = p[K.libLocalDesc] ?: true,
            localGroup = parse(p[K.libLocalGroup], GroupBy.entries.toTypedArray(), GroupBy.DAY),
            cloudSort = parse(p[K.libCloudSort], SortField.entries.toTypedArray(), SortField.CAPTURE_DATE),
            cloudDescending = p[K.libCloudDesc] ?: true,
            cloudGroup = parse(p[K.libCloudGroup], GroupBy.entries.toTypedArray(), GroupBy.DAY),
            density = parse(p[K.libDensity], GridDensity.entries.toTypedArray(), GridDensity.MEDIUM),
            cloudMode = parse(p[K.libCloudMode], CloudViewMode.entries.toTypedArray(), CloudViewMode.GRID),
        )
    }

    suspend fun saveLocalView(sort: SortField, descending: Boolean, group: GroupBy) = store.edit {
        it[K.libLocalSort] = sort.name; it[K.libLocalDesc] = descending; it[K.libLocalGroup] = group.name
    }.let { }

    suspend fun saveCloudView(sort: SortField, descending: Boolean, group: GroupBy) = store.edit {
        it[K.libCloudSort] = sort.name; it[K.libCloudDesc] = descending; it[K.libCloudGroup] = group.name
    }.let { }

    suspend fun saveDensity(d: GridDensity) = store.edit { it[K.libDensity] = d.name }.let { }
    suspend fun saveCloudMode(m: CloudViewMode) = store.edit { it[K.libCloudMode] = m.name }.let { }

    /** Last complete inventory of the bucket, or null if none was taken (or the stored one is unreadable). */
    val inventory: Flow<StorageInventory?> = store.data.map { p -> p[K.inventory]?.let(InventoryCodec::decode) }

    val inventoryAttempt: Flow<InventoryAttempt?> = store.data.map { p ->
        p[K.inventoryAttemptMs]?.let { InventoryAttempt(it, p[K.inventoryError]) }
    }

    suspend fun saveInventory(i: StorageInventory) {
        store.edit {
            it[K.inventory] = InventoryCodec.encode(i)
            it[K.inventoryAttemptMs] = i.takenAtMs
            it.remove(K.inventoryError)
        }
    }

    /** A failed attempt never replaces the last complete inventory; it is only remembered so the UI can say so. */
    suspend fun saveInventoryFailure(atMs: Long, message: String) {
        store.edit { it[K.inventoryAttemptMs] = atMs; it[K.inventoryError] = message.take(300) }
    }

    /** Semantic search is opt-in: off until the user turns it on (which is also the moment the model is offered for download). */
    val semanticEnabled: Flow<Boolean> = store.data.map { it[K.semanticEnabled] ?: false }
    suspend fun setSemanticEnabled(v: Boolean) = store.edit { it[K.semanticEnabled] = v }.let { }

    /** Index only while charging (the default: indexing a library is minutes to hours of CPU). */
    val semanticOnlyCharging: Flow<Boolean> = store.data.map { it[K.semanticCharging] ?: true }
    suspend fun setSemanticOnlyCharging(v: Boolean) = store.edit { it[K.semanticCharging] = v }.let { }

    /** The model download (about 150 MB) waits for an unmetered network by default. */
    val modelWifiOnly: Flow<Boolean> = store.data.map { it[K.modelWifiOnly] ?: true }
    suspend fun setModelWifiOnly(v: Boolean) = store.edit { it[K.modelWifiOnly] = v }.let { }

    /** People (face grouping) is opt-in: off until the user has read what it does and turned it on. Nothing is analysed before. */
    val faceEnabled: Flow<Boolean> = store.data.map { it[K.faceEnabled] ?: false }
    suspend fun setFaceEnabled(v: Boolean) = store.edit { it[K.faceEnabled] = v }.let { }

    /** Analyse in the background only while charging (the default: a first pass over a library is minutes to hours of CPU). */
    val faceOnlyCharging: Flow<Boolean> = store.data.map { it[K.faceCharging] ?: true }
    suspend fun setFaceOnlyCharging(v: Boolean) = store.edit { it[K.faceCharging] = v }.let { }

    /** One photo at a time (the default). Off: a few at once, according to the device's cores. */
    val faceGentle: Flow<Boolean> = store.data.map { it[K.faceGentle] ?: true }
    suspend fun setFaceGentle(v: Boolean) = store.edit { it[K.faceGentle] = v }.let { }

    /** The short explanation of what the face analysis does and where its data lives has been shown and accepted. */
    val faceIntroSeen: Flow<Boolean> = store.data.map { it[K.faceIntroSeen] ?: false }
    suspend fun setFaceIntroSeen(v: Boolean) = store.edit { it[K.faceIntroSeen] = v }.let { }

    /** The user's rules for what may be offered when freeing space. Cautious by default. */
    val spaceRules: Flow<com.s3vault.app.domain.space.SafetyRules> = store.data.map { p ->
        val d = com.s3vault.app.domain.space.SafetyRules()
        com.s3vault.app.domain.space.SafetyRules(
            excludeFavorites = p[K.spaceFavorites] ?: d.excludeFavorites,
            keepRecentDays = (p[K.spaceRecentDays] ?: d.keepRecentDays).coerceIn(0, com.s3vault.app.domain.space.SafetyRules.MAX_DAYS),
            minBackedUpDays = (p[K.spaceBackedDays] ?: d.minBackedUpDays).coerceIn(0, com.s3vault.app.domain.space.SafetyRules.MAX_DAYS),
            excludedFolders = p[K.spaceFolders] ?: emptySet(),
            includeVideos = p[K.spaceVideos] ?: d.includeVideos,
            keepAlbumPhotos = p[K.spaceAlbums] ?: d.keepAlbumPhotos,
            verifiedWithinHours = (p[K.spaceVerifiedHours] ?: d.verifiedWithinHours).coerceIn(1, 24 * 90),
        )
    }

    suspend fun currentSpaceRules(): com.s3vault.app.domain.space.SafetyRules = spaceRules.first()

    suspend fun saveSpaceRules(r: com.s3vault.app.domain.space.SafetyRules) {
        store.edit {
            it[K.spaceFavorites] = r.excludeFavorites
            it[K.spaceRecentDays] = r.keepRecentDays
            it[K.spaceBackedDays] = r.minBackedUpDays
            it[K.spaceFolders] = r.excludedFolders
            it[K.spaceVideos] = r.includeVideos
            it[K.spaceAlbums] = r.keepAlbumPhotos
            it[K.spaceVerifiedHours] = r.verifiedWithinHours
        }
    }

    /** Days between automatic listing audits; 0 (the default) means audits only run when the user starts them. */
    val auditIntervalDays: Flow<Int> = store.data.map { it[K.auditIntervalDays] ?: 0 }
    suspend fun setAuditIntervalDays(days: Int) = store.edit { it[K.auditIntervalDays] = days.coerceIn(0, 90) }.let { }

    /** How long the app may stay out of the foreground before the private albums lock again (0 = as soon as it is left). */
    val privateAutoLockMs: Flow<Long> = store.data.map { it[K.privateAutoLockMs] ?: com.s3vault.app.domain.privacy.PrivateSession.DEFAULT_AUTO_LOCK_MS }
    suspend fun setPrivateAutoLockMs(ms: Long) = store.edit { it[K.privateAutoLockMs] = ms.coerceIn(0, 3_600_000) }.let { }

    suspend fun currentS3Config(): S3Config? = s3Config.first()
    suspend fun currentBackup(): BackupSettings = backup.first()

    suspend fun saveS3Config(config: S3Config) {
        store.edit {
            it[K.region] = config.region.trim()
            it[K.bucket] = config.bucket.trim()
            it[K.prefix] = RemoteKeys.normalizePrefix(config.prefix)
            it[K.authMethod] = config.authMethod.name
            // A different bucket/region invalidates the previous verification result.
            it.remove(K.connVerifiedAt); it.remove(K.connOk); it.remove(K.connMessage)
        }
    }

    suspend fun saveConnectionResult(ok: Boolean, message: String, nowMs: Long) {
        store.edit {
            it[K.connVerifiedAt] = nowMs
            it[K.connOk] = ok
            it[K.connMessage] = message
        }
    }

    /** Where the first-run tour stands. Absent (a new or upgraded installation that never saw it) reads as NOT_STARTED. */
    val onboardingProgress: Flow<com.s3vault.app.domain.onboarding.OnboardingProgress> = store.data.map { p ->
        p[K.onboardingProgress]?.let { runCatching { com.s3vault.app.domain.onboarding.OnboardingProgress.valueOf(it) }.getOrNull() }
            ?: com.s3vault.app.domain.onboarding.OnboardingProgress.NOT_STARTED
    }
    suspend fun setOnboardingProgress(v: com.s3vault.app.domain.onboarding.OnboardingProgress) = store.edit { it[K.onboardingProgress] = v.name }.let { }

    suspend fun setAutoBackup(v: Boolean) = store.edit { it[K.autoBackup] = v }.let { }
    suspend fun setWifiOnly(v: Boolean) = store.edit { it[K.wifiOnly] = v }.let { }
    suspend fun setIncludeVideos(v: Boolean) = store.edit { it[K.includeVideos] = v }.let { }
    suspend fun setIntervalHours(v: Int) = store.edit { it[K.intervalHours] = v }.let { }
    suspend fun setMaxConcurrency(v: Int) = store.edit { it[K.maxConcurrency] = v.coerceIn(1, 4) }.let { }
    suspend fun setCloudPreviews(v: Boolean) = store.edit { it[K.cloudPreviews] = v }.let { }
    suspend fun setPaused(v: Boolean) = store.edit { it[K.paused] = v }.let { }
    suspend fun setTheme(v: ThemeMode) = store.edit { it[K.theme] = v.name }.let { }
    suspend fun setNeedsCredentials(v: Boolean) = store.edit { it[K.needsCredentials] = v }.let { }
    suspend fun markTransferSuccess(nowMs: Long) = store.edit { it[K.lastSuccessfulTransfer] = nowMs }.let { }

    /** Removes bucket configuration and connection state (does not touch remote data). */
    suspend fun clearS3Config() {
        store.edit {
            it.remove(K.region); it.remove(K.bucket); it.remove(K.prefix); it.remove(K.authMethod)
            it.remove(K.connVerifiedAt); it.remove(K.connOk); it.remove(K.connMessage)
            it[K.needsCredentials] = false
            it.remove(K.listedAt); it.remove(K.listedComplete); it.remove(K.listedCount); it.remove(K.listedSource)
            it.remove(K.inventory); it.remove(K.inventoryAttemptMs); it.remove(K.inventoryError)
        }
    }
}
