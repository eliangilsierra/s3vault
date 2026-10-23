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

data class RemoteListing(val key: String, val sizeBytes: Long, val lastModifiedMs: Long)

data class PermissionCheck(val operation: String, val allowed: Boolean, val error: RemoteErrorKind?)

data class PermissionReport(val checks: List<PermissionCheck>) {
    val allAllowed: Boolean get() = checks.all { it.allowed }
}

/** Object access scoped to the configured bucket and prefix. There is deliberately no delete operation. */
interface RemoteStorage {
    /** ListObjectsV2 (maxKeys=1) under the configured prefix. Validates bucket, region and credentials. */
    suspend fun checkConnection()

    /** Exercises List, Put and Get against a small marker object. Never touches user photos. */
    suspend fun testPermissions(): PermissionReport

    /** Lists every object under `{prefix}photos/`. */
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
}

/** Abstraction over local file access so sync logic can be tested without Android. */
interface LocalFileReader {
    fun open(uri: String): InputStream
}
