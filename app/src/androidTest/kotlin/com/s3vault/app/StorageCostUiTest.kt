package com.s3vault.app

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.s3vault.app.domain.AuthMethod
import com.s3vault.app.domain.RemoteKeys
import com.s3vault.app.domain.S3Config
import com.s3vault.app.domain.TransferEventType
import com.s3vault.app.domain.cost.ClassUsage
import com.s3vault.app.domain.cost.StorageInventory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.io.File

/**
 * The storage and cost screen in the real app, against an in-memory S3 that lists objects with their storage class and
 * (declared) sizes. Screenshots go to the app's external files dir (`.../files/shots`) for visual review.
 */
class StorageCostUiTest {
    private val perms = GrantPermissionRule.grant(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(perms).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container get() = (instrumentation.targetContext.applicationContext as S3VaultApp).container
    private lateinit var server: MockWebServer
    private lateinit var s3: FakeS3Dispatcher
    private val keys = RemoteKeys("ui/")
    private val gib = 1L shl 30

    @Before fun setUp() {
        s3 = FakeS3Dispatcher("test-bucket", allowedPrefix = "ui/")
        server = MockWebServer().also { it.dispatcher = s3; it.start() }
        runBlocking(Dispatchers.IO) {
            container.db.clearAllTables()
            container.settings.clearS3Config()
            container.credentials.saveStatic("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
            container.settings.saveS3Config(S3Config("us-east-1", "test-bucket", "ui/", AuthMethod.STATIC))
        }
        container.endpointOverride = server.url("/").toString().trimEnd('/')
    }

    @After fun tearDown() {
        container.endpointOverride = null
        runBlocking(Dispatchers.IO) {
            container.db.clearAllTables()
            container.credentials.clear()
            container.settings.clearS3Config()
        }
        server.close()
    }

    // ------------------------------------------------------------------ helpers

    private fun listed(name: String, size: Long, storageClass: String = "STANDARD") {
        val key = keys.photoKey("%032x".format(name.hashCode().toLong() and 0xffffffffL), "%064x".format(name.hashCode().toLong() and 0xffffffffL), name)
        s3.objects[key] = FakeS3Dispatcher.Obj(ByteArray(0), "", "image/jpeg", emptyMap(), storageClass, size)
    }

    /** 120 GiB in Standard (two objects) and 500 GiB in Glacier Instant Retrieval (two objects): 120 x 0.023 + 500 x 0.004 = 4.76 USD. */
    private fun fourObjects() {
        listed("a.jpg", 100 * gib); listed("b.jpg", 20 * gib)
        listed("c.jpg", 300 * gib, "GLACIER_IR"); listed("d.jpg", 200 * gib, "GLACIER_IR")
    }

    private fun shot(name: String) {
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shown(t: String, substring: Boolean = false) = compose.onAllNodesWithText(t, substring = substring).fetchSemanticsNodes().isNotEmpty()
    private fun first(t: String) = compose.onAllNodesWithText(t, substring = true)[0]
    private fun waitFor(ms: Long = 20_000, cond: () -> Boolean) = compose.waitUntil(ms, cond)
    private fun tab(label: String) = compose.onNode(hasText(label) and hasClickAction())

    private fun openCosts() {
        tab("Ajustes").performClick()
        compose.onNodeWithText("Almacenamiento y costes").performClick()
        waitFor { shown("Es una estimación, no tu factura") }
    }

    private fun refresh(label: String = "Actualizar inventario") {
        compose.onNodeWithText(label).performScrollTo().performClick()
    }

    // ------------------------------------------------------------------ flows

    @Test fun beforeTheFirstInventoryTheScreenExplainsWhatRefreshingDoes() {
        fourObjects()
        openCosts()
        compose.onNodeWithText("Todavía no hay inventario").assertIsDisplayed()
        assertTrue(shown("una solicitud LIST por cada 1.000 objetos", substring = true))
        assertFalse("no total can be shown before anything was measured", shown("Coste mensual estimado"))
        assertTrue("it does not list the bucket by itself", s3.methods.isEmpty())
        shot("c01-no-inventory")
    }

    @Test fun refreshMeasuresTheBucketAndShowsAnEstimateWithItsBasis() {
        fourObjects()
        openCosts()
        refresh("Calcular ahora")

        waitFor { shown("Coste mensual estimado") }
        first("≈ US$ 4,76").assertIsDisplayed()
        shot("c02a-hero")
        assertTrue(shown("Confianza", substring = true))
        assertTrue(shown("Glacier Instant Retrieval"))
        assertTrue("measured values are tagged", shown("Medido"))
        compose.onNodeWithText("Vistas previas").performScrollTo().assertIsDisplayed()
        assertTrue("assumptions are tagged as such", shown("Suposición"))
        compose.onNodeWithText("Recuperación en Glacier Instant Retrieval").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Versionado").performScrollTo().assertIsDisplayed()
        assertEquals("one LIST request for 4 objects, nothing else", listOf("GET"), s3.methods.distinct())
        assertEquals(1, s3.methods.size)
        shot("c02-dashboard")

        // The Settings home row now summarises it.
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor { shown("≈ US$ 4,76 al mes · 4 objetos", substring = true) }
        shot("c03-settings-home")
    }

    @Test fun anEmptyBucketCostsNothingAndSaysSo() {
        openCosts()
        refresh("Calcular ahora")
        waitFor { shown("Coste mensual estimado") }
        first("≈ US$ 0,00").assertIsDisplayed()
        assertTrue(shown("No hay nada bajo ui/photos/", substring = true))
        shot("c04-empty")
    }

    @Test fun aStorageClassWithoutAPriceGivesNoTotalInsteadOfAMisleadingOne() {
        listed("a.jpg", 10 * gib); listed("deep.jpg", 50 * gib, "DEEP_ARCHIVE")
        openCosts()
        refresh("Calcular ahora")
        waitFor { shown("Hay clases de almacenamiento sin precio") }
        compose.onNodeWithText("No disponible").assertIsDisplayed()
        assertFalse(shown("al mes"))
        assertTrue("what was measured is still shown", shown("Glacier Deep Archive"))
        shot("c05-unsupported-class")
    }

    @Test fun aRegionWithoutPricesShowsUsageButNoAmount() {
        runBlocking(Dispatchers.IO) { container.settings.saveS3Config(S3Config("xx-test-1", "test-bucket", "ui/", AuthMethod.STATIC)) }
        fourObjects()
        openCosts()
        refresh("Calcular ahora")
        waitFor { shown("Sin precios para la región «xx-test-1»") }
        compose.onNodeWithText("No disponible").assertIsDisplayed()
        assertTrue(shown("Datos almacenados"))
        assertFalse(shown("al mes"))
        shot("c06-no-prices")
    }

    @Test fun aFailedRefreshKeepsThePreviousInventoryAndSaysSo() {
        fourObjects()
        openCosts()
        refresh("Calcular ahora")
        waitFor { shown("Coste mensual estimado") }

        s3.denyAll = true
        refresh()
        waitFor { shown("No se pudo actualizar el inventario") }
        assertTrue(shown("Se conserva el último inventario completo", substring = true))
        first("≈ US$ 4,76").assertExists()
        shot("c07-refresh-failed")

        s3.denyAll = false
        compose.onNodeWithText("Reintentar").performClick()
        waitFor { !shown("No se pudo actualizar el inventario") }
        first("≈ US$ 4,76").assertExists()
    }

    @Test fun uploadsAndDeletionsSinceTheInventoryAreFlaggedAsPending() {
        fourObjects()
        openCosts()
        refresh("Calcular ahora")
        waitFor { shown("Coste mensual estimado") }
        assertFalse(shown("Hay cambios desde el inventario"))

        Thread.sleep(20)
        runBlocking(Dispatchers.IO) {
            container.events.log(TransferEventType.UPLOADED, "nueva.jpg")
            container.events.log(TransferEventType.CLOUD_DELETED, "3 elementos")
        }
        waitFor { shown("Hay cambios desde el inventario") }
        assertTrue(shown("1 subida(s) y 3 borrado(s) en S3", substring = true))
        shot("c08-pending-changes")
    }

    @Test fun anOldInventoryIsFlaggedAndLowersTheConfidence() {
        runBlocking(Dispatchers.IO) {
            container.settings.saveInventory(
                StorageInventory(
                    "us-east-1", "test-bucket", "ui/", System.currentTimeMillis() - 10L * 86_400_000, 1,
                    listOf(ClassUsage("STANDARD", 3, 12L * gib, 0, 0)), 3, 0, 0, emptyMap(),
                ),
            )
        }
        openCosts()
        waitFor { shown("Inventario desactualizado") }
        assertTrue(shown("Se tomó hace 10 días", substring = true))
        assertTrue(shown("Confianza media"))
        shot("c09-old-inventory")
    }

    @Test fun anInventoryOfAnotherBucketIsNeverShownAsThisOne() {
        runBlocking(Dispatchers.IO) {
            container.settings.saveInventory(
                StorageInventory("us-east-1", "otro-bucket", "ui/", System.currentTimeMillis(), 1, listOf(ClassUsage("STANDARD", 1, gib, 0, 0)), 1, 0, 0, emptyMap()),
            )
        }
        openCosts()
        compose.onNodeWithText("El inventario guardado es de otro bucket").assertIsDisplayed()
        assertFalse(shown("Coste mensual estimado"))
    }

    @Test fun withoutS3ConfiguredTheScreenSaysWhatToDo() {
        runBlocking(Dispatchers.IO) { container.settings.clearS3Config() }
        openCosts()
        compose.onNodeWithText("Amazon S3 no está configurado").assertIsDisplayed()
        compose.onNodeWithText("Configurar S3").assertIsDisplayed()
        assertNotNull(compose.onNodeWithText("Es una estimación, no tu factura"))
    }

    @Test fun missingCredentialsBlockRefreshButNotTheLastInventory() {
        fourObjects()
        openCosts()
        refresh("Calcular ahora")
        waitFor { shown("Coste mensual estimado") }

        runBlocking(Dispatchers.IO) { container.credentials.clear() }
        waitFor { shown("Faltan credenciales") }
        assertTrue(shown("Se muestra el último guardado", substring = true))
        first("≈ US$ 4,76").assertExists()
        shot("c10-no-credentials")
    }

    @Test fun theHelpSheetExplainsTheMethod() {
        fourObjects()
        openCosts()
        refresh("Calcular ahora")
        waitFor { shown("Coste mensual estimado") }
        compose.onNodeWithText("Cómo se calcula", substring = true).performScrollTo().performClick()
        waitFor { shown("Medido, estimado, suposición") }
        assertTrue(shown("No es tu factura", substring = true))
        shot("c11-help")
    }
}
