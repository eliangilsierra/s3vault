package app.s3vault.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.s3vault.domain.model.S3Config
import app.s3vault.domain.model.ServerSideEncryption
import app.s3vault.domain.remote.FailureType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Preferencias no secretas. Los secretos viven solo en el Keystore (ver `security`). */
data class AppSettings(
    val region: String = "",
    val bucket: String = "",
    val prefix: String = "",
    val sse: ServerSideEncryption = ServerSideEncryption.BUCKET_DEFAULT,
    val autoBackup: Boolean = false,
    val wifiOnly: Boolean = true,
    val excludeVideos: Boolean = false,
    val concurrency: Int = 2,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val paused: Boolean = false,
    /** Causa que detiene la cola completa (credenciales, permisos…) hasta que el usuario actúe. */
    val queueBlock: FailureType? = null,
    val connectionVerifiedAtMs: Long? = null,
    /** Último destino (bucket + prefijo) configurado; sobrevive a «Desconectar» para detectar cambios de destino. */
    val lastDestination: String? = null,
) {
    /** Configuración S3 válida, o null si falta o es inválida. */
    val s3Config: S3Config?
        get() = (S3Config.create(region, bucket, prefix, sse) as? app.s3vault.domain.model.ConfigValidation.Valid)?.config
}

private val Context.dataStore by preferencesDataStore(name = "settings")

fun destinationOf(config: S3Config) = config.bucket + "|" + config.prefix

class SettingsRepository(private val context: Context) {
    private object K {
        val region = stringPreferencesKey("region")
        val bucket = stringPreferencesKey("bucket")
        val prefix = stringPreferencesKey("prefix")
        val sse = stringPreferencesKey("sse")
        val auto = booleanPreferencesKey("auto_backup")
        val wifi = booleanPreferencesKey("wifi_only")
        val noVideos = booleanPreferencesKey("exclude_videos")
        val concurrency = intPreferencesKey("concurrency")
        val theme = stringPreferencesKey("theme")
        val paused = booleanPreferencesKey("paused")
        val block = stringPreferencesKey("queue_block")
        val verified = longPreferencesKey("connection_verified_at")
        val destination = stringPreferencesKey("last_destination")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map(::read)

    private fun read(p: Preferences) = AppSettings(
        region = p[K.region].orEmpty(),
        bucket = p[K.bucket].orEmpty(),
        prefix = p[K.prefix].orEmpty(),
        sse = p[K.sse]?.let { v -> ServerSideEncryption.entries.firstOrNull { it.name == v } } ?: ServerSideEncryption.BUCKET_DEFAULT,
        autoBackup = p[K.auto] ?: false,
        wifiOnly = p[K.wifi] ?: true,
        excludeVideos = p[K.noVideos] ?: false,
        concurrency = (p[K.concurrency] ?: 2).coerceIn(1, 3),
        theme = p[K.theme]?.let { v -> ThemeMode.entries.firstOrNull { it.name == v } } ?: ThemeMode.SYSTEM,
        paused = p[K.paused] ?: false,
        queueBlock = p[K.block]?.let { v -> FailureType.entries.firstOrNull { it.name == v } },
        connectionVerifiedAtMs = p[K.verified],
        lastDestination = p[K.destination],
    )

    suspend fun saveS3Config(config: S3Config) = context.dataStore.edit {
        it[K.region] = config.region; it[K.bucket] = config.bucket; it[K.prefix] = config.prefix; it[K.sse] = config.sse.name
        it[K.destination] = destinationOf(config)
        it.remove(K.verified); it.remove(K.block)
    }

    /** Olvida la conexión (no toca S3): borra bucket, región y prefijo locales. */
    suspend fun clearS3Config() = context.dataStore.edit {
        it.remove(K.region); it.remove(K.bucket); it.remove(K.prefix); it.remove(K.sse)
        it.remove(K.verified); it.remove(K.block); it[K.auto] = false
    }

    suspend fun setAutoBackup(v: Boolean) = context.dataStore.edit { it[K.auto] = v }
    suspend fun setWifiOnly(v: Boolean) = context.dataStore.edit { it[K.wifi] = v }
    suspend fun setExcludeVideos(v: Boolean) = context.dataStore.edit { it[K.noVideos] = v }
    suspend fun setConcurrency(v: Int) = context.dataStore.edit { it[K.concurrency] = v.coerceIn(1, 3) }
    suspend fun setTheme(v: ThemeMode) = context.dataStore.edit { it[K.theme] = v.name }
    suspend fun setPaused(v: Boolean) = context.dataStore.edit { it[K.paused] = v }
    suspend fun setConnectionVerified(atMs: Long?) = context.dataStore.edit {
        if (atMs == null) it.remove(K.verified) else it[K.verified] = atMs
    }

    suspend fun setQueueBlock(block: FailureType?) = context.dataStore.edit {
        if (block == null) it.remove(K.block) else it[K.block] = block.name
    }
}
