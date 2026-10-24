package com.s3vault.app.data.local

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.s3vault.app.sync.ChangeSignal
import java.util.concurrent.atomic.AtomicLong

/**
 * Counts changes to MediaStore (a photo taken, added, edited or deleted by anyone) while the process lives. Comparing the
 * counter with the value seen by the last scan tells whether another scan can find anything new, so coming back to the app
 * does not re-read and re-diff the whole library when nothing happened.
 */
class MediaChangeTracker(private val context: Context) : ChangeSignal {
    private val counter = AtomicLong()
    override val version: Long get() = counter.get()

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        // Only a counter bump: cheap even when a bulk deletion fires one callback per file.
        override fun onChange(selfChange: Boolean) { counter.incrementAndGet() }
    }

    private var started = false

    @Synchronized fun start() {
        if (started) return
        started = true
        val resolver = context.contentResolver
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
    }
}
