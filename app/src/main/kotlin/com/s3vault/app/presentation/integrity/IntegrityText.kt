package com.s3vault.app.presentation.integrity

import com.s3vault.app.data.local.IntegrityReason
import com.s3vault.app.data.local.IntegrityResultEntity
import com.s3vault.app.data.local.IntegrityVerdict
import com.s3vault.app.data.local.VerifyLevel
import com.s3vault.app.presentation.formatBytes

/** Plain-language wording of audit results. Kept apart from the screen so it can be tested and reused by other screens. */
object IntegrityText {
    fun verdict(v: IntegrityVerdict): String = when (v) {
        IntegrityVerdict.VERIFIED -> "Verificada"
        IntegrityVerdict.MISSING -> "Falta en S3"
        IntegrityVerdict.CHANGED -> "Posible cambio"
        IntegrityVerdict.UNKNOWN -> "Sin concluir"
        IntegrityVerdict.STALE_RECORD -> "Registro obsoleto"
    }

    fun level(l: VerifyLevel): String = when (l) {
        VerifyLevel.LISTING -> "por lista (existencia y tamaño)"
        VerifyLevel.METADATA -> "por metadato declarado"
        VerifyLevel.CHECKSUM -> "por SHA-256"
    }

    fun reason(r: IntegrityResultEntity): String = when (r.reason) {
        IntegrityReason.NONE -> when (r.level) {
            VerifyLevel.CHECKSUM -> "El SHA-256 guardado en S3 coincide con el de la foto. No se descargó nada."
            VerifyLevel.LISTING -> "Está en la lista de S3 con el tamaño esperado. No se comprobó el contenido: usa la verificación profunda para eso."
            VerifyLevel.METADATA -> "Coincide el tamaño y el metadato declarado al subirla."
        }
        IntegrityReason.NOT_LISTED -> "No aparece en la lista completa del bucket."
        IntegrityReason.HEAD_NOT_FOUND -> "S3 responde que el objeto no existe."
        IntegrityReason.SIZE_MISMATCH ->
            "En S3 mide ${r.remoteSize?.let(::formatBytes) ?: "otra cosa"} y la copia verificada medía ${formatBytes(r.expectedSize)}. " +
                "Puede ser un objeto distinto o una subida incompleta."
        IntegrityReason.ETAG_CHANGED -> "El objeto fue reemplazado desde que se verificó el respaldo (cambió su marca de versión)."
        IntegrityReason.MODIFIED_AFTER_BACKUP ->
            "Se modificó en S3 después del respaldo. Es un indicio, no una prueba: no había marca de versión guardada. Compruébala a fondo."
        IntegrityReason.CHECKSUM_MISMATCH -> "El SHA-256 guardado en S3 no coincide con el de la foto: el contenido es otro."
        IntegrityReason.LISTING_INCOMPLETE -> "La lista de S3 quedó incompleta, así que no se puede concluir nada de este objeto."
        IntegrityReason.LISTING_EMPTY ->
            "La lista de S3 llegó vacía. ¿Bucket o prefijo distinto, o una política que oculta los objetos? No se marca como perdido."
        IntegrityReason.FORBIDDEN ->
            "S3 rechazó la consulta (403). Con una política limitada a un prefijo, S3 también responde 403 si el objeto no existe: no se puede concluir."
        IntegrityReason.NO_REMOTE_CHECKSUM ->
            if (r.verdict == IntegrityVerdict.VERIFIED) "S3 no guarda un SHA-256 comparable; coincide el tamaño y el metadato que escribió quien lo subió."
            else "S3 no guarda un SHA-256 comparable para este objeto, así que no se puede confirmar su contenido."
        IntegrityReason.TEMPORARY_ERROR -> "La comprobación falló por un error temporal de red o de S3. No significa que falte."
        IntegrityReason.KEY_OUTSIDE_PREFIX -> "El registro apunta a un objeto fuera del bucket y prefijo configurados. ¿Cambiaste de bucket?"
        IntegrityReason.VERSION_MISMATCH -> "El registro local no es coherente con la clave del objeto."
    }

    /** What the auditor cannot know, always shown next to the results. */
    val limitations: List<String> = listOf(
        "La lista de S3 demuestra que un objeto existe y cuánto mide, no que su contenido sea el correcto.",
        "La verificación profunda compara el SHA-256 que S3 guardó al subir cada foto con el de su clave. No descarga las fotos y no detecta cambios que S3 no refleje.",
        "La marca de versión (ETag) solo sirve para notar que un objeto se reemplazó. No se usa como suma de verificación: con subidas multiparte o cifrado no equivale a un MD5.",
        "Si tu política IAM solo permite listar el prefijo de fotos, S3 responde 403 tanto a un permiso que falta como a un objeto inexistente: esos casos quedan «sin concluir».",
        "La app no hace subidas multiparte, así que no puede crear subidas incompletas; un objeto con tamaño distinto se marca como posible cambio.",
        "Una auditoría solo lee. Nada se borra ni se sobrescribe en S3 sin que elijas «Volver a subir».",
    )
}
