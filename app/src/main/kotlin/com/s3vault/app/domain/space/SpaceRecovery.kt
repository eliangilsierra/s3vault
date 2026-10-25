package com.s3vault.app.domain.space

/**
 * The user's safety rules for freeing space. They only ever make the candidate list shorter: nothing here can add a file to it.
 * Defaults are cautious (favourites, recent photos and fresh backups stay).
 */
data class SafetyRules(
    val excludeFavorites: Boolean = true,
    /** Photos taken in the last N days stay on the device. 0 turns the rule off. */
    val keepRecentDays: Int = 30,
    /** A photo must have been backed up at least N days ago. 0 turns the rule off. */
    val minBackedUpDays: Int = 7,
    /** Folders (MediaStore albums) that are never offered. */
    val excludedFolders: Set<String> = emptySet(),
    val includeVideos: Boolean = true,
    /** Photos that belong to an album of this app are kept. */
    val keepAlbumPhotos: Boolean = false,
    /** Evidence that S3 still holds a photo is trusted this long; older evidence is checked again before deleting. */
    val verifiedWithinHours: Int = 168,
) {
    companion object {
        const val MAX_DAYS = 3650
    }
}

/** Where a backed-up file stands with respect to being removed from the device. */
enum class SpaceBucket(val label: String, val excluded: Boolean = false) {
    /** Backed up, and S3 was confirmed to hold it recently: the only bucket that may be recommended. */
    VERIFIED("Verificadas en S3"),

    /** Backed up, but nothing recent confirms that S3 still has it. It can be verified, not recommended yet. */
    UNVERIFIED("Sin verificar"),

    /** An audit found its object missing in S3 (or the record stale): removing the local file could lose the photo. */
    MISSING("Falta en S3"),

    /** The file or the object changed since the backup: the copy in S3 is not this file. */
    OUTDATED("Desactualizadas"),

    EXCL_FAVORITE("Favoritas", excluded = true),
    EXCL_RECENT("Recientes", excluded = true),
    EXCL_NEW_BACKUP("Respaldo reciente", excluded = true),
    EXCL_FOLDER("Carpeta excluida", excluded = true),
    EXCL_VIDEO("Vídeos", excluded = true),
    EXCL_ALBUM("En un álbum", excluded = true);

    /** True for the buckets the user can act on from a list of candidates. */
    val isCandidate: Boolean get() = this == VERIFIED || this == UNVERIFIED
}

/** How the candidate list is divided into sections the user can select as a whole. */
enum class SpaceGrouping(val label: String) { MONTH("Mes"), YEAR("Año"), FOLDER("Carpeta") }

/** Why a selected file did not make it through the last check before deleting. */
enum class DropReason(val label: String) {
    NOT_CONFIRMED("S3 no confirmó el objeto (sin conexión o sin permiso)"),
    MISSING("El objeto ya no existe en S3"),
    CHANGED("El objeto en S3 no coincide con la copia verificada"),
    LOCAL_CHANGED("El archivo del dispositivo cambió después del respaldo"),
    GONE_FROM_DEVICE("Ya no está en el dispositivo"),
    RULES("Ya no cumple tus reglas de seguridad"),
}

/** What the final check before deleting concluded. Only [safe] may go to Android's deletion dialog. */
data class VerifiedBatch(val safeIds: List<Long>, val dropped: Map<DropReason, Int>, val credentialsNeeded: Boolean = false) {
    val droppedCount: Int get() = dropped.values.sum()
}
