package com.s3vault.app.sync

import com.s3vault.app.domain.semantic.ModelPack
import com.s3vault.app.domain.semantic.ModelPackStore
import com.s3vault.app.domain.semantic.PackFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException

sealed interface DownloadResult {
    data object Done : DownloadResult
    data class NetworkError(val reason: String) : DownloadResult
    data class HttpError(val code: Int, val file: String) : DownloadResult
    /** A file arrived whole and did not hash to the pinned value: it is deleted and never used. */
    data class HashMismatch(val file: String) : DownloadResult
    data class NotEnoughSpace(val neededBytes: Long) : DownloadResult
}

/**
 * Fetches a [ModelPack] with plain HTTPS (no new dependency), resumably: each file goes to `name.part`, a dropped connection or
 * a stopped worker continues from where the file ends with a Range request, and a file is renamed into place only after its
 * SHA-256 matched. Only the pinned URLs of the pack are ever requested, and nothing about the user, the photos or any search is
 * sent: the requests carry no cookies, no identifiers and no body.
 */
class ModelDownloader(
    private val store: ModelPackStore,
    /** Replaces the scheme and host of every URL (tests point it at a local server); null in the app. */
    private val baseOverride: String? = null,
    private val minFreeMargin: Long = 64L * 1024 * 1024,
) {
    suspend fun download(pack: ModelPack, onProgress: (done: Long, total: Long) -> Unit): DownloadResult = withContext(Dispatchers.IO) {
        val dir = store.dir(pack).apply { mkdirs() }
        val remaining = pack.files.sumOf { f -> if (store.file(pack, f).length() == f.sizeBytes) 0L else f.sizeBytes - File(dir, f.name + ".part").length() }
        if (dir.usableSpace < remaining + minFreeMargin) return@withContext DownloadResult.NotEnoughSpace(remaining + minFreeMargin)
        var done = pack.files.sumOf { f -> if (store.file(pack, f).length() == f.sizeBytes) f.sizeBytes else 0L }
        onProgress(done, pack.totalBytes)
        for (f in pack.files) {
            val target = store.file(pack, f)
            if (target.length() == f.sizeBytes) continue // already there; the final verification checks it
            val base = done
            when (val r = fetch(f, File(dir, f.name + ".part")) { got -> onProgress(base + got, pack.totalBytes) }) {
                is Fetch.Failed -> return@withContext r.result
                Fetch.Ok -> Unit
            }
            done += f.sizeBytes
        }
        // The whole pack is verified before it may run: one wrong byte anywhere means the model is not used.
        val ctx = currentCoroutineContext()
        val bad = store.verify(pack, cancelled = { !ctx.isActive })
        if (bad != null) { store.deleteFile(pack, bad); return@withContext DownloadResult.HashMismatch(bad.name) }
        DownloadResult.Done
    }

    private sealed interface Fetch { data object Ok : Fetch; data class Failed(val result: DownloadResult) : Fetch }

    private suspend fun fetch(f: PackFile, part: File, onBytes: (Long) -> Unit): Fetch {
        val url = baseOverride?.let { it.trimEnd('/') + "/" + f.url.substringAfter("://").substringAfter('/') } ?: f.url
        var conn: HttpURLConnection? = null
        try {
            var have = if (part.isFile) part.length() else 0L
            if (have > f.sizeBytes) { part.delete(); have = 0 }
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000; readTimeout = 30_000; instanceFollowRedirects = true
                setRequestProperty("Accept-Encoding", "identity") // the bytes on the wire are the file: ranges and sizes stay exact
                setRequestProperty("User-Agent", "S3Vault-ModelDownload")
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            val code = conn.responseCode
            when {
                code == 416 -> { // the part already holds everything we asked for
                    if (part.length() != f.sizeBytes) { part.delete(); return Fetch.Failed(DownloadResult.HttpError(code, f.name)) }
                }
                code == 206 -> Unit
                code == 200 -> have = 0 // the server ignored the range: start the file again
                else -> return Fetch.Failed(DownloadResult.HttpError(code, f.name))
            }
            if (code != 416) {
                RandomAccessFile(part, "rw").use { out ->
                    out.seek(have); if (have == 0L) out.setLength(0)
                    var pos = have
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            pos += n
                            onBytes(pos)
                        }
                    }
                }
            }
            if (part.length() != f.sizeBytes) return Fetch.Failed(DownloadResult.NetworkError("La descarga de ${f.name} se cortó."))
            val target = File(part.parentFile, f.name)
            target.delete()
            if (!part.renameTo(target)) return Fetch.Failed(DownloadResult.NetworkError("No se pudo guardar ${f.name}."))
            return Fetch.Ok
        } catch (e: UnknownHostException) {
            return Fetch.Failed(DownloadResult.NetworkError("Sin conexión con ${URL(url).host}."))
        } catch (e: IOException) {
            // A cut connection keeps its part file: the next attempt resumes.
            return Fetch.Failed(DownloadResult.NetworkError(e.message?.take(120) ?: "Error de red."))
        } finally {
            conn?.disconnect()
        }
    }
}
