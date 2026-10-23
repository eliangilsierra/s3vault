package com.s3vault.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AlbumManifestTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)

    @Test fun `parses albums and removes repeated items`() {
        val m = AlbumManifest.parse("""{"version":1,"albums":[{"name":" Amor ","items":["$a","$b","$a"]},{"name":"Vacío","items":[]}]}""")
        assertEquals(listOf("Amor", "Vacío"), m.albums.map { it.name })
        assertEquals(listOf(a, b), m.albums[0].items)
    }

    @Test fun `keeps accents and spaces exactly as written`() {
        val m = AlbumManifest.parse("""{"version":1,"albums":[{"name":"recuerdos / Fotos grados","items":["$a"]},{"name":"Cañón – 2022","items":["$b"]}]}""")
        assertEquals("recuerdos / Fotos grados", m.albums[0].name)
        assertEquals("Cañón – 2022", m.albums[1].name)
    }

    @Test fun `rejects anything that is not the expected shape`() {
        listOf(
            "no es json",
            """{"version":2,"albums":[]}""",
            """{"version":1}""",
            """{"version":1,"albums":[{"name":"","items":["$a"]}]}""",
            """{"version":1,"albums":[{"name":"x","items":["no-es-un-hash"]}]}""",
            """{"version":1,"albums":[{"name":"x","items":["${a.uppercase()}"]}]}""",
            """{"version":1,"albums":[{"name":"x"}]}""",
            """{"version":1,"albums":[{"name":"${"n".repeat(81)}","items":[]}]}""",
        ).forEach { json -> assertThrows(json, AlbumManifest.Invalid::class.java) { AlbumManifest.parse(json) } }
    }

    @Test fun `manifest lives next to the connection check object under the prefix`() {
        assertEquals("mi/prefijo/.s3vault/albums.json", RemoteKeys("mi/prefijo").albumManifestKey)
        assertEquals(".s3vault/albums.json", RemoteKeys("").albumManifestKey)
    }
}
