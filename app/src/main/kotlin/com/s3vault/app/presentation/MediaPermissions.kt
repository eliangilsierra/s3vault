package com.s3vault.app.presentation

import android.Manifest
import android.os.Build

/**
 * The runtime permissions the gallery asks for, by Android version: photos and videos only (never "all files"). On Android 14+ the
 * user may grant access to selected photos only; the app works with that and says so.
 */
fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}
