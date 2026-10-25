package com.s3vault.app.domain.smart

import com.s3vault.app.domain.MediaKind
import com.s3vault.app.domain.library.DateRange
import org.json.JSONArray
import org.json.JSONObject

/** Where a file stands with respect to the backup, for album definitions. */
enum class SmartBackup { NOT_BACKED_UP, BACKED_UP }

/**
 * A smart album is a saved question about metadata the app already has, not a list of photos: membership is decided by the
 * database every time, so a photo that is added, deleted, restored or backed up enters or leaves by itself and nothing has to be
 * kept in step. Every field is optional and they combine with AND.
 */
data class SmartFilter(
    val kinds: Set<MediaKind> = emptySet(),
    val favorites: Boolean = false,
    /** Folders that Android names like a screenshots folder (see [com.s3vault.app.data.local.SmartAlbumSql.SCREENSHOT_SQL]). */
    val screenshots: Boolean = false,
    val minSizeBytes: Long? = null,
    /** Entered the device's library in the last N days (MediaStore `date_added`). */
    val addedWithinDays: Int? = null,
    /** Capture date range as day keys (`yyyyMMdd`): a year, a month or a day. */
    val dateRange: DateRange? = null,
    val backup: SmartBackup? = null,
    /** Backed up, confirmed in S3 and allowed by the user's safety rules: what "Liberar espacio" would offer. */
    val recoverable: Boolean = false,
    /** Restored from S3 into `Pictures/S3Vault` in the last N days. */
    val restoredWithinDays: Int? = null,
    /** No capture date, or no known size in pixels. */
    val missingMetadata: Boolean = false,
    val folders: Set<String> = emptySet(),
    val nameContains: String = "",
) {
    val activeCount: Int
        get() = listOf(
            kinds.isNotEmpty(), favorites, screenshots, minSizeBytes != null, addedWithinDays != null, dateRange != null, backup != null,
            recoverable, restoredWithinDays != null, missingMetadata, folders.isNotEmpty(), nameContains.isNotBlank(),
        ).count { it }

    val isEmpty: Boolean get() = activeCount == 0
}

/** Icons are named, not imported, so the domain stays free of UI classes. */
enum class SmartIcon { RECENT, FAVORITE, SCREENSHOT, VIDEO, LARGE, NOT_BACKED_UP, RECOVERABLE, RESTORED, METADATA, YEAR, CUSTOM }

data class SmartAlbum(
    /** Stable key: `builtin:favorites`, `year:2023`, `custom:12`. */
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: SmartIcon,
    val filter: SmartFilter,
    val custom: Boolean = false,
)

object SmartAlbums {
    const val LARGE_BYTES = 25L * 1024 * 1024
    const val RECENT_DAYS = 30
    const val RESTORED_DAYS = 30

    /** The folder the restore engine writes to (Pictures/S3Vault and Movies/S3Vault): how "restored" is recognised without a column. */
    const val RESTORE_FOLDER = "S3Vault"

    val builtIn: List<SmartAlbum> = listOf(
        SmartAlbum("builtin:recent", "Añadidas recientemente", "Últimos $RECENT_DAYS días", SmartIcon.RECENT, SmartFilter(addedWithinDays = RECENT_DAYS)),
        SmartAlbum("builtin:favorites", "Favoritas", "Las que marcaste en Android (Android 11 o superior)", SmartIcon.FAVORITE, SmartFilter(favorites = true)),
        SmartAlbum("builtin:screenshots", "Capturas de pantalla", "Carpetas llamadas Screenshots o «capturas de pantalla»", SmartIcon.SCREENSHOT, SmartFilter(screenshots = true)),
        SmartAlbum("builtin:videos", "Vídeos", "Todos los vídeos del dispositivo", SmartIcon.VIDEO, SmartFilter(kinds = setOf(MediaKind.VIDEO))),
        SmartAlbum("builtin:large", "Archivos grandes", "25 MB o más", SmartIcon.LARGE, SmartFilter(minSizeBytes = LARGE_BYTES)),
        SmartAlbum("builtin:not-backed-up", "Sin respaldar", "Solo en este dispositivo o con error", SmartIcon.NOT_BACKED_UP, SmartFilter(backup = SmartBackup.NOT_BACKED_UP)),
        SmartAlbum("builtin:recoverable", "Listas para liberar espacio", "Respaldadas y confirmadas en S3, según tus reglas", SmartIcon.RECOVERABLE, SmartFilter(recoverable = true)),
        SmartAlbum("builtin:restored", "Restauradas recientemente", "Bajadas de S3 en los últimos $RESTORED_DAYS días", SmartIcon.RESTORED, SmartFilter(restoredWithinDays = RESTORED_DAYS, folders = setOf(RESTORE_FOLDER))),
        SmartAlbum("builtin:metadata", "Sin fecha o datos completos", "Sin fecha de captura o sin tamaño en píxeles", SmartIcon.METADATA, SmartFilter(missingMetadata = true)),
    )

    fun year(year: Int) = SmartAlbum(
        "year:$year", year.toString(), "Fotos y vídeos de $year, por mes y día", SmartIcon.YEAR,
        SmartFilter(dateRange = DateRange(year * 10_000 + 101, year * 10_000 + 1231)),
    )

    fun find(id: String, custom: List<SmartAlbum>): SmartAlbum? = when {
        id.startsWith("year:") -> id.removePrefix("year:").toIntOrNull()?.let(::year)
        else -> builtIn.firstOrNull { it.id == id } ?: custom.firstOrNull { it.id == id }
    }
}

/** Stores a [SmartFilter] as JSON. Unknown or missing keys are tolerated, so an older or newer record never breaks the list. */
object SmartFilterCodec {
    fun encode(f: SmartFilter): String = JSONObject().apply {
        if (f.kinds.isNotEmpty()) put("kinds", JSONArray(f.kinds.map { it.name }))
        if (f.favorites) put("favorites", true)
        if (f.screenshots) put("screenshots", true)
        f.minSizeBytes?.let { put("minSize", it) }
        f.addedWithinDays?.let { put("addedDays", it) }
        f.dateRange?.let { put("from", it.fromDay); put("to", it.toDay) }
        f.backup?.let { put("backup", it.name) }
        if (f.recoverable) put("recoverable", true)
        f.restoredWithinDays?.let { put("restoredDays", it) }
        if (f.missingMetadata) put("missingMetadata", true)
        if (f.folders.isNotEmpty()) put("folders", JSONArray(f.folders.sorted()))
        if (f.nameContains.isNotBlank()) put("name", f.nameContains.trim())
    }.toString()

    fun decode(json: String): SmartFilter = try {
        val o = JSONObject(json)
        SmartFilter(
            kinds = o.optJSONArray("kinds")?.let { a -> (0 until a.length()).mapNotNull { runCatching { MediaKind.valueOf(a.getString(it)) }.getOrNull() }.toSet() } ?: emptySet(),
            favorites = o.optBoolean("favorites", false),
            screenshots = o.optBoolean("screenshots", false),
            minSizeBytes = if (o.has("minSize")) o.getLong("minSize").takeIf { it > 0 } else null,
            addedWithinDays = if (o.has("addedDays")) o.getInt("addedDays").takeIf { it > 0 } else null,
            dateRange = if (o.has("from") && o.has("to")) DateRange(o.getInt("from"), o.getInt("to")) else null,
            backup = o.optString("backup").takeIf { it.isNotEmpty() }?.let { runCatching { SmartBackup.valueOf(it) }.getOrNull() },
            recoverable = o.optBoolean("recoverable", false),
            restoredWithinDays = if (o.has("restoredDays")) o.getInt("restoredDays").takeIf { it > 0 } else null,
            missingMetadata = o.optBoolean("missingMetadata", false),
            folders = o.optJSONArray("folders")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet(),
            nameContains = o.optString("name", ""),
        )
    } catch (e: org.json.JSONException) {
        SmartFilter() // an unreadable record is an album with no condition, shown as such, never a crash
    }
}
