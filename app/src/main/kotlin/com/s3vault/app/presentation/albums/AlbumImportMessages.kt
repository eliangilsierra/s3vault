package com.s3vault.app.presentation.albums

import com.s3vault.app.sync.AlbumImporter.Result

/** What the user is told after importing the album file. Plain text, so every case is covered by a unit test. */
object AlbumImportMessages {
    fun describe(r: Result): String = when (r) {
        Result.NotConfigured -> "Configura Amazon S3 para importar álbumes."
        Result.NoManifest ->
            "No se encontró el archivo de álbumes en tu bucket (o no hay permiso para leerlo). " +
                "Debe estar en «.s3vault/albums.json», dentro del prefijo configurado."
        is Result.Invalid -> "El archivo de álbumes de S3 no es válido: ${r.reason}."
        is Result.Imported -> imported(r)
    }

    private fun imported(r: Result.Imported): String {
        val added = r.photosAdded + r.cloudAdded
        return when {
            added == 0 && r.albumsCreated == 0 && r.photosUnmatched > 0 -> buildString {
                append("El archivo de álbumes nombra ${photos(r.manifestPhotos)}, pero ${if (r.photosUnmatched == 1) "no se encontró" else "no se encontraron"} ")
                append("ni en este dispositivo ni en «photos/» del bucket")
                if (r.listedBucket) append(" (acabo de volver a listarlo)")
                append(". Comprueba que el archivo use el SHA-256 de cada foto y que las fotos estén bajo el prefijo configurado.")
            }
            added == 0 && r.albumsCreated == 0 -> "Los álbumes ya estaban al día."
            else -> buildString {
                append("Álbumes nuevos: ${r.albumsCreated}. Fotos añadidas: $added")
                if (r.photosAdded > 0 && r.cloudAdded > 0) append(" (${r.photosAdded} del dispositivo y ${r.cloudAdded} solo en S3)")
                else if (r.cloudAdded > 0) append(" (solo están en S3)")
                append('.')
                if (r.photosUnmatched > 0) append(" ${photos(r.photosUnmatched)} no ${if (r.photosUnmatched == 1) "se encontró" else "se encontraron"} ni en el dispositivo ni en S3.")
            }
        }
    }

    private fun photos(n: Int) = if (n == 1) "1 foto" else "$n fotos"
}
