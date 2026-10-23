package com.s3vault.app.security

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Static guard: the only code allowed to call android.util.Log is SafeLog (which redacts). */
class NoSecretsInLogsTest {
    private val mainSources = File("src/main/kotlin")

    @Test fun onlySafeLogTouchesAndroidLog() {
        assertTrue("run from the :app module directory", mainSources.isDirectory)
        val offenders = mainSources.walkTopDown()
            .filter { it.extension == "kt" && it.name != "Redactor.kt" }
            .filter { f ->
                f.readText().lines().any { line ->
                    val t = line.trim()
                    !t.startsWith("//") && !t.startsWith("*") &&
                        (Regex("\\bLog\\.[divwe]\\(").containsMatchIn(t) || t.contains("println(") || t.contains("Timber."))
                }
            }
            .map { it.name }
            .toList()
        assertTrue("Direct logging found in: $offenders", offenders.isEmpty())
    }

    @Test fun noCredentialLookingLiteralsInMainSources() {
        val offenders = mainSources.walkTopDown().filter { it.extension == "kt" }
            .filter { Regex("\"(AKIA|ASIA)[A-Z0-9]{12,}\"").containsMatchIn(it.readText()) }
            .map { it.name }.toList()
        assertTrue("Credential-like literal in: $offenders", offenders.isEmpty())
    }
}
