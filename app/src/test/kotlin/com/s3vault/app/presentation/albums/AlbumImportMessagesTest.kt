package com.s3vault.app.presentation.albums

import com.s3vault.app.sync.AlbumImporter.Result
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumImportMessagesTest {
    private fun imported(
        created: Int = 0, updated: Int = 0, local: Int = 0, cloud: Int = 0, unmatched: Int = 0, skipped: Int = 0, total: Int = 0, listed: Boolean = false,
    ) = Result.Imported(created, updated, local, cloud, unmatched, skipped, total, listed)

    @Test fun `photos only in S3 are said to be in S3, not asked to be backed up`() {
        val text = AlbumImportMessages.describe(imported(created = 2, cloud = 40, total = 40))
        assertTrue(text, "Álbumes nuevos: 2" in text && "40" in text && "solo están en S3" in text)
        assertFalse(text, "Respaldar" in text)
    }

    @Test fun `a mix says how many came from where`() {
        val text = AlbumImportMessages.describe(imported(created = 1, local = 3, cloud = 5, unmatched = 1, total = 9))
        assertTrue(text, "3 del dispositivo y 5 solo en S3" in text)
        assertTrue(text, "1 foto no se encontró ni en el dispositivo ni en S3" in text)
    }

    @Test fun `nothing found explains where it looked and never blames the backup`() {
        val text = AlbumImportMessages.describe(imported(unmatched = 12, skipped = 2, total = 12, listed = true))
        assertTrue(text, "12 fotos" in text && "photos/" in text && "volver a listarlo" in text && "SHA-256" in text)
        assertFalse(text, "Respaldar" in text)
    }

    @Test fun `nothing new is reported as up to date`() {
        assertTrue(AlbumImportMessages.describe(imported(total = 5)).contains("al día"))
    }

    @Test fun `a missing manifest names the file it expects`() {
        assertTrue(AlbumImportMessages.describe(Result.NoManifest).contains(".s3vault/albums.json"))
    }
}
