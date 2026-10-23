package com.s3vault.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.s3vault.app.domain.AuthMethod
import com.s3vault.app.domain.RemoteKeys
import com.s3vault.app.domain.S3Config
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
)

data class ConnectionStatus(
    val verifiedAtMs: Long?,
    val lastResultOk: Boolean?,
    val lastMessage: String?,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

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
        val theme = stringPreferencesKey("theme")
        val connVerifiedAt = longPreferencesKey("conn_verified_at")
        val connOk = booleanPreferencesKey("conn_ok")
        val connMessage = stringPreferencesKey("conn_message")
        val needsCredentials = booleanPreferencesKey("needs_credentials")
        val lastSuccessfulTransfer = longPreferencesKey("last_success")
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

    suspend fun setAutoBackup(v: Boolean) = store.edit { it[K.autoBackup] = v }.let { }
    suspend fun setWifiOnly(v: Boolean) = store.edit { it[K.wifiOnly] = v }.let { }
    suspend fun setIncludeVideos(v: Boolean) = store.edit { it[K.includeVideos] = v }.let { }
    suspend fun setIntervalHours(v: Int) = store.edit { it[K.intervalHours] = v }.let { }
    suspend fun setMaxConcurrency(v: Int) = store.edit { it[K.maxConcurrency] = v.coerceIn(1, 4) }.let { }
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
        }
    }
}
