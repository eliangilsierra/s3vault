package com.s3vault.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static guards for what the face feature promises about privacy. They read the sources, so a later change that would break a
 * promise (a network call, a log line with a vector, a new permission, a copy of the database in a system backup) fails here
 * before it can be shipped. They complement, and do not replace, the runtime checks in the instrumented tests.
 */
class FacePrivacyGuardTest {
    private val main = File("src/main/kotlin/com/s3vault/app")

    private fun faceFiles(): List<File> {
        assertTrue("run from the :app module directory", main.isDirectory)
        val roots = listOf("domain/faces", "data/faces", "presentation/people")
        val named = listOf("data/local/FaceEntities.kt", "data/local/FaceDao.kt", "sync/FaceIndexer.kt", "sync/FaceGroupingStore.kt", "sync/FaceBackupService.kt", "sync/FacePeopleController.kt")
        return roots.flatMap { File(main, it).walkTopDown().filter { f -> f.extension == "kt" }.toList() } + named.map { File(main, it) }
    }

    private fun code(f: File) = f.readLines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }

    @Test fun `the face code cannot reach the network or S3`() {
        val forbidden = listOf(
            "java.net", "javax.net", "okhttp", "aws.sdk", "aws.smithy", "android.net.http", "android.net.ConnectivityManager",
            "com.s3vault.app.data.remote", "com.s3vault.app.domain.RemoteStorage", "com.s3vault.app.domain.UploadRequest", "HttpURLConnection", "WebView",
        )
        val offenders = faceFiles().flatMap { f -> code(f).filter { l -> l.trim().startsWith("import ") && forbidden.any { l.contains(it) } }.map { f.name + ": " + it.trim() } }
        assertTrue("network or S3 types in face code: $offenders", offenders.isEmpty())
    }

    @Test fun `the face code does not send anything to a remote store, only to the existing backup queue`() {
        val uses = faceFiles().filter { f -> code(f).any { it.contains("remote.get()") || it.contains("RemoteContextProvider") || it.contains(".upload(") } }.map { it.name }
        assertTrue("face code that talks to S3: $uses", uses.isEmpty())
    }

    @Test fun `the analysis work asks for no network`() {
        val text = File(main, "sync/Scheduling.kt").readText()
        val body = text.substringAfter("override fun kickFaceIndex").substringBefore("override fun cancelFaceIndex")
        val periodic = text.substringAfter("override fun configureFaceSchedule").substringBefore("override fun kickModelDownload")
        assertTrue("kickFaceIndex must exist", body.isNotBlank() && periodic.isNotBlank())
        assertTrue("kickFaceIndex requires a network type: $body", !body.contains("NetworkType") && !body.contains("constraints(wifiOnly)"))
        assertTrue("the daily face pass requires a network type", !periodic.contains("NetworkType"))
    }

    @Test fun `no log line in the face code can carry face data`() {
        val calls = faceFiles().flatMap { f -> code(f).filter { Regex("\\b(SafeLog|Log)\\.[dwievDWIEV]\\(").containsMatchIn(it) }.map { f.name + ": " + it.trim() } }
        // A log call in face code is constant text: no interpolation and no concatenation of anything.
        val interpolated = Regex("\"[^\"]*[$][^\"]*\"")
        val concatenated = Regex("\" *[+]|[+] *\"")
        val bad = calls.filter { interpolated.containsMatchIn(it.substringAfter(": ")) || concatenated.containsMatchIn(it.substringAfter(": ")) }
        assertTrue("log calls with interpolated text in face code: $bad", bad.isEmpty())
        // The worker, which lives with the other workers, logs only constants too.
        val worker = File(main, "sync/Scheduling.kt").readText().substringAfter("class FaceIndexWorker").substringBefore("class ModelDownloadWorker")
        assertTrue(Regex("SafeLog\\.e\\(\"FaceIndexWorker\", \"[^\"$]*\"").containsMatchIn(worker))
        assertTrue("no println or toString of vectors", faceFiles().none { f -> code(f).any { it.contains("println(") || Regex("vector\\.toString|contentToString").containsMatchIn(it) } })
    }

    @Test fun `no face crop or vector is ever written to a file or the media store`() {
        val writers = faceFiles().filter { f ->
            code(f).any { Regex("FileOutputStream|\\.writeBytes\\(|\\.writeText\\(|Bitmap\\.compress|MediaStore\\.|openOutputStream|DiskCache|diskCache").containsMatchIn(it) }
        }.map { it.name }
        assertTrue("face code that writes files: $writers", writers.isEmpty())
    }

    @Test fun `the manifest asks for no permission this feature needs`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val permissions = Regex("<uses-permission[^>]*android:name=\"([^\"]+)\"").findAll(manifest).map { it.groupValues[1].substringAfterLast('.') }.toSet()
        // The permissions the app had before the face feature. A new one (camera, contacts, location...) would be a decision, not a side effect.
        val expected = setOf("INTERNET", "ACCESS_NETWORK_STATE", "USE_BIOMETRIC", "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_VISUAL_USER_SELECTED", "READ_EXTERNAL_STORAGE")
        assertEquals(expected, permissions)
    }

    @Test fun `system backups carry neither the database nor the files that hold the face index and its key`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        for (rules in listOf("src/main/res/xml/backup_rules.xml", "src/main/res/xml/data_extraction_rules.xml")) {
            val t = File(rules).readText()
            for (domain in listOf("database", "file", "sharedpref")) assertTrue("$rules does not exclude $domain", t.contains("<exclude domain=\"$domain\""))
        }
        // The key that seals the index lives in the no-backup directory, never in a backed-up one.
        assertTrue(File(main, "security/SecretStore.kt").readText().contains("noBackupFilesDir"))
    }

    @Test fun `the index is sealed before it reaches the database`() {
        val indexer = File(main, "sync/FaceIndexer.kt").readText()
        assertTrue(indexer.contains("FaceVectorCodec.sealVector"))
        val entity = File(main, "data/local/FaceEntities.kt").readText()
        assertTrue("faces store only the sealed vector", entity.contains("val sealed: ByteArray") && !Regex("val (vector|embedding|crop|thumbnail)\\b").containsMatchIn(entity))
        assertTrue(entity.contains("val centroidSealed: ByteArray?"))
    }

    @Test fun `every way into the face feature honours the build switch`() {
        // `-PwithFaces=false` must leave no screen, row, tour page, help topic, route or worker that reaches the feature.
        val gradle = File("build.gradle.kts").readText()
        assertTrue("the flag", gradle.contains("\"withFaces\"") && gradle.contains("buildConfigField(\"boolean\", \"FACES\"") && gradle.contains("ignoreAssetsPatterns += \"!faces\""))
        val gated = mapOf(
            "presentation/nav/AppNav.kt" to 3, // tool in the hub, strip in Albums, the four routes (one block)
            "presentation/albums/AlbumsScreen.kt" to 2, // the invitation strip, in the empty and in the filled layout
            "presentation/settings/SettingsScreens.kt" to 1,
            "presentation/onboarding/OnboardingScreen.kt" to 1,
            "presentation/help/HelpScreen.kt" to 1,
            "sync/Scheduling.kt" to 1,
        )
        for ((file, atLeast) in gated) {
            val uses = Regex("FaceFeature\\.available").findAll(File(main, file).readText()).count()
            assertTrue("$file checks FaceFeature.available $uses times, expected at least $atLeast", uses >= atLeast)
        }
        val nav = File(main, "presentation/nav/AppNav.kt").readText()
        val outside = nav.lines().filter { it.contains("composable(\"people") }
        assertEquals("the four People routes", 4, outside.size)
        val before = nav.substringBefore("composable(\"people\")").lines().last { it.isNotBlank() }
        assertTrue("the People routes sit inside the FaceFeature check: $before", before.contains("FaceFeature.available"))
    }
}
