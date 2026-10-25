package com.s3vault.app.data.privacy

import com.s3vault.app.data.local.MediaItemEntity
import com.s3vault.app.domain.LocalFileReader
import com.s3vault.app.domain.MediaKind
import com.s3vault.app.domain.ThumbnailMaker
import com.s3vault.app.domain.privacy.PrivateItem
import com.s3vault.app.domain.privacy.PrivateMeta
import com.s3vault.app.sync.RestoreSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Moves pictures into the vault and back out. Going in is a verified copy: it never deletes, changes or hides the original (the
 * caller offers that as its own confirmed step, with the existing delete flow, and only for the pictures reported as [Report.moved]).
 * Going out writes a verified decrypted copy to the gallery first and removes the sealed one only after that copy is in place.
 *
 * It runs in the foreground, in the caller's coroutine: the vault key exists only while the user has unlocked it, so this is not
 * work for the background scheduler. It is cancellable between pictures, reports progress, and stops at once if the vault locks.
 */
class PrivateMover(
    private val store: PrivateVaultStore,
    private val reader: LocalFileReader,
    private val thumbnails: ThumbnailMaker?,
) {
    class Problem(val id: Long, val name: String, val reason: String)

    class Report(
        /** MediaStore ids of the originals that now have a verified sealed copy. */
        val moved: List<Long>,
        val skipped: List<Problem>,
        val failed: List<Problem>,
        val bytes: Long,
        val stopped: Boolean,
    ) {
        val total get() = moved.size + skipped.size + failed.size
    }

    suspend fun moveIn(items: List<MediaItemEntity>, albumId: Long, progress: (done: Int, total: Int) -> Unit = { _, _ -> }): Report {
        val moved = ArrayList<Long>(); val skipped = ArrayList<Problem>(); val failed = ArrayList<Problem>()
        var bytes = 0L
        var stopped = false
        progress(0, items.size)
        for ((i, m) in items.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (!store.session.unlocked.value) { stopped = true; break }
            when {
                m.kind != MediaKind.IMAGE -> skipped += Problem(m.mediaStoreId, m.displayName, "Los vídeos todavía no se pueden proteger.")
                !m.present -> skipped += Problem(m.mediaStoreId, m.displayName, "El archivo ya no está en el dispositivo.")
                else -> {
                    val thumb = runCatching { thumbnails?.create(m.uri, m.kind) }.getOrNull()
                    val meta = PrivateMeta(m.displayName, m.mimeType, m.sizeBytes, m.width, m.height, m.dateTakenMs, "", m.folderName)
                    try {
                        when (val r = store.add(albumId, { reader.open(m.uri) }, thumb, meta)) {
                            is PrivateVaultStore.AddResult.Added -> { moved += m.mediaStoreId; bytes += r.plainBytes }
                            is PrivateVaultStore.AddResult.Failed -> failed += Problem(m.mediaStoreId, m.displayName, r.reason)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed += Problem(m.mediaStoreId, m.displayName, "No se pudo leer el archivo original.")
                    }
                }
            }
            progress(i + 1, items.size)
        }
        return Report(moved, skipped, failed, bytes, stopped)
    }

    class ExportReport(val restored: List<Long>, val failed: List<Problem>, val stopped: Boolean)

    /** Puts [items] back in the gallery (Pictures/S3Vault) and, after each one is verified and in place, removes its sealed copy. */
    suspend fun moveOut(items: List<PrivateItem>, sink: RestoreSink, progress: (done: Int, total: Int) -> Unit = { _, _ -> }): ExportReport {
        val restored = ArrayList<Long>(); val failed = ArrayList<Problem>()
        var stopped = false
        progress(0, items.size)
        for ((i, item) in items.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (!store.session.unlocked.value) { stopped = true; break }
            val target = sink.create(item.meta.name, item.meta.mimeType)
            try {
                val sha = store.decryptTo(item, target.output)
                if (item.meta.sha256.isNotEmpty() && sha != item.meta.sha256) {
                    target.abort()
                    failed += Problem(item.id, item.meta.name, "La copia descifrada no coincide con la original. No se ha movido nada.")
                } else {
                    target.commit()
                    store.remove(listOf(item.id))
                    restored += item.id
                }
            } catch (e: CancellationException) {
                runCatching { target.abort() }
                throw e
            } catch (e: Exception) {
                runCatching { target.abort() }
                failed += Problem(item.id, item.meta.name, "No se pudo devolver la foto a la galería.")
            }
            progress(i + 1, items.size)
        }
        return ExportReport(restored, failed, stopped)
    }
}
