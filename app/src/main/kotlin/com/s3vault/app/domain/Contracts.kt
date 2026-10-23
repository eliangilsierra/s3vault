package com.s3vault.app.domain

import java.io.InputStream
import java.io.OutputStream

/**
 * Source of valid AWS credentials. The gallery and sync logic only see this abstraction, never the type
 * of credential behind it (temporary or static).
 */
interface AwsCredentialsProvider {
    /**
     * Returns credentials valid right now, or throws [RemoteException] with kind CREDENTIALS_MISSING /
     * CREDENTIALS_EXPIRED. Implementations that can renew a session do so here before it expires.
     */
    suspend fun getCredentials(): AwsCredentials

    /** True only if this provider can obtain new credentials without user interaction. */
    val canRenewAutomatically: Boolean
}

data class UploadRequest(
    val key: String,
    val openStream: () -> InputStream,
    val sizeBytes: Long,
    val contentType: String,
    /** Base64 of the raw SHA-256 digest, as required by x-amz-checksum-sha256. */
    val sha256Base64: String,
    val metadata: Map<String, String>,
)

data class UploadResult(val etag: String?, val checksumSha256Base64: String?)

data class RemoteListing(
    val key: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    /** S3 storage class reported by the listing (STANDARD, GLACIER_IR...); null when the listing did not say. */
    val storageClass: String? = null,
)

/** A finished listing and how many `ListObjectsV2` requests it took. Behaves as a plain list. */
class PagedListing(items: List<RemoteListing>, val requests: Int) : List<RemoteListing> by items

data class PermissionCheck(val operation: String, val allowed: Boolean, val error: RemoteErrorKind?)

data class PermissionReport(val checks: List<PermissionCheck>) {
    val allAllowed: Boolean get() = checks.all { it.allowed }

    /**
     * The first failure whose cause is not a missing IAM permission (wrong region, bad or expired credentials,
     * unknown bucket, network...). Telling the user to review the IAM policy in those cases would point at the wrong fix.
     */
    val blockingError: RemoteErrorKind?
        get() = checks.firstOrNull { !it.allowed && it.error != null && it.error != RemoteErrorKind.ACCESS_DENIED }?.error
}

/** Outcome of a batch delete: S3 reports success or failure per key, so one bad key never hides the rest. */
data class DeleteReport(val deleted: List<String>, val failed: Map<String, RemoteErrorKind>)

/**
 * Object access scoped to the configured bucket and prefix.
 *
 * Deleting is the only destructive operation. It is reachable solely from an explicit, confirmed user action in the
 * UI (see `CloudDeletionController`) and is limited to photo and thumbnail keys; backup and restore never call it.
 */
interface RemoteStorage {
    /** ListObjectsV2 (maxKeys=1) under the configured prefix. Validates bucket, region and credentials. */
    suspend fun checkConnection()

    /** Exercises List, Put and Get against a small marker object. Never touches user photos. */
    suspend fun testPermissions(): PermissionReport

    /**
     * Lists every object under `{prefix}photos/`, recognised photo keys or not (callers decide what to do with the
     * rest: it is stored, and billed, all the same). Returns only when the listing is complete; a failure on any
     * page throws, so a partial listing is never mistaken for a whole one. The result is a [PagedListing].
     */
    suspend fun listPhotos(): List<RemoteListing>

    /** Returns null when the object does not exist. */
    suspend fun head(key: String): RemoteObjectMetadata?

    suspend fun upload(request: UploadRequest, onProgress: (Long) -> Unit): UploadResult

    /**
     * Streams the object (from [offset]) into [sink]. When [ifMatchEtag] is set and the remote object changed,
     * fails with INTEGRITY_MISMATCH. Returns the number of bytes written.
     */
    suspend fun download(
        key: String,
        sink: OutputStream,
        offset: Long = 0,
        ifMatchEtag: String? = null,
        onProgress: (Long) -> Unit = {},
    ): Long

    /**
     * Reads an object (or only its first [maxBytes] bytes, using an HTTP Range request) into memory. Meant for small
     * objects: thumbnails and the head of a JPEG. Returns null when the object does not exist.
     */
    suspend fun readBytes(key: String, maxBytes: Long? = null): ByteArray?

    /** Stores a generated preview. Only keys under the thumbnails folder are accepted. */
    suspend fun putThumbnail(key: String, bytes: ByteArray)

    /**
     * Deletes the given photo or thumbnail keys with a single DeleteObjects request (at most [MAX_DELETE_BATCH]).
     * Deleting a key that is already gone counts as success (S3 deletes are idempotent). Throws [RemoteException] when
     * the whole request fails (credentials, network...); per-key failures come back in the report.
     */
    suspend fun delete(keys: List<String>): DeleteReport

    companion object {
        const val MAX_DELETE_BATCH = 100
    }
}

/** Abstraction over local file access so sync logic can be tested without Android. */
interface LocalFileReader {
    fun open(uri: String): InputStream
}

/** Produces the small JPEG that is stored in S3 as the preview of a photo or video. Null when nothing can be decoded. */
fun interface ThumbnailMaker {
    suspend fun create(uri: String, kind: MediaKind): ByteArray?
}

/** Reads the JPEG thumbnail embedded in the EXIF block of an image head (a partial download is enough). */
fun interface EmbeddedThumbnailReader {
    fun read(head: ByteArray): ByteArray?
}

/** Longest edge, in pixels, of the previews stored in S3. */
const val PREVIEW_MAX_EDGE_PX = 720
