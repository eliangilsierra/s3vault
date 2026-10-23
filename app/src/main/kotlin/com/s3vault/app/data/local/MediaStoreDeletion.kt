package com.s3vault.app.data.local

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.s3vault.app.sync.LocalMediaGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Existence check against MediaStore, used to confirm what the system deletion dialog actually removed. */
class MediaStoreGateway(private val context: Context) : LocalMediaGateway {
    override suspend fun exists(uri: String): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.query(Uri.parse(uri), arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                ?.use { it.count > 0 } ?: false
        } catch (e: SecurityException) {
            true // cannot tell: assume it is still there rather than forgetting a file that exists
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}

/**
 * Android's own deletion flow for media owned by other apps (every camera photo). The app never holds a broad
 * "manage all files" permission: each deletion is confirmed by a system dialog.
 *
 *  - Android 11+ (API 30): [MediaStore.createDeleteRequest] shows one system dialog for the whole batch.
 *  - Android 10 (API 29): no batch API. Deleting throws [RecoverableSecurityException], whose intent shows the system
 *    dialog for that single file; the caller retries the delete once the user agrees.
 */
object PlatformMediaDelete {
    val supportsBatchRequest: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun batchRequest(context: Context, uris: List<Uri>): IntentSender {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) throw IllegalStateException("createDeleteRequest needs Android 11")
        return MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
    }

    sealed interface Direct {
        data object Deleted : Direct
        data class NeedsConsent(val intentSender: IntentSender) : Direct
        data object Failed : Direct
    }

    /** API 29 path (also fine on later versions for files the app created itself, such as restored photos). */
    suspend fun deleteDirect(resolver: ContentResolver, uri: Uri): Direct = withContext(Dispatchers.IO) {
        try {
            if (resolver.delete(uri, null, null) > 0) Direct.Deleted else Direct.Failed
        } catch (e: RecoverableSecurityException) {
            Direct.NeedsConsent(e.userAction.actionIntent.intentSender)
        } catch (e: SecurityException) {
            Direct.Failed
        }
    }
}
