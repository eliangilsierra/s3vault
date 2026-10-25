package com.s3vault.app

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.s3vault.app.domain.semantic.ModelPacks
import com.s3vault.app.domain.semantic.PackStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.io.File

/**
 * The semantic-search screen in the real app with the real model (copied by `adb`/`run-as`; skipped without it): opt-in text first,
 * name search that works with the feature off, indexing through the real worker, a content search that finds the right picture, and
 * the state where the feature is on but the model is not on the device.
 */
class SemanticSearchUiTest {
    private val perms = GrantPermissionRule.grant(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(perms).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container get() = (instrumentation.targetContext.applicationContext as S3VaultApp).container
    private val pack = ModelPacks.ClipB32
    private val inserted = mutableListOf<android.net.Uri>()

    private fun asset(name: String) = instrumentation.context.assets.open("semantic/$name").readBytes()

    @Before fun setUp() {
        compose.waitUntil(30_000) { container.library.hasScanned || container.mediaAccess() == com.s3vault.app.sync.MediaAccess.NONE }
        runBlocking(Dispatchers.IO) {
            container.settings.setSemanticEnabled(false)
            container.db.clearAllTables()
        }
        listOf("cat", "coffee", "page").forEach { inserted += BulkPerfSupport.insertBytes("eval_$it.jpg", asset("$it.jpg"), "Pictures/S3VaultSemantic") }
        runBlocking(Dispatchers.IO) {
            container.library.refresh(force = true)
            container.db.openHelper.writableDatabase.execSQL("DELETE FROM media_items WHERE folderName != 'S3VaultSemantic'")
        }
    }

    @After fun tearDown() {
        inserted.forEach { BulkPerfSupport.delete(it) }
        runBlocking(Dispatchers.IO) {
            container.settings.setSemanticEnabled(false)
            container.scheduler.cancelSemanticIndex()
            container.db.clearAllTables()
        }
        File(container.modelStore.dir(pack).path + ".away").takeIf { it.exists() }?.renameTo(container.modelStore.dir(pack))
    }

    private fun shot(name: String) {
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitForText(text: String, substring: Boolean = false, timeout: Long = 60_000) {
        try {
            compose.waitUntil(timeout) {
                if (compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isEmpty()) {
                    runCatching { compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text, substring = substring)) }
                }
                compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) { shot("timeout-" + text.take(24).replace(Regex("[^A-Za-z0-9]"), "_")); throw e }
    }

    private fun waitForCell(name: String) {
        compose.waitUntil(60_000) { compose.onAllNodes(hasContentDescription(name, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun open() {
        compose.onNodeWithContentDescription("Más opciones").performClick()
        compose.onNodeWithText("Herramientas de la biblioteca").performClick()
        compose.waitForIdle()
        shot("semantic-tools-hub")
        compose.onNodeWithText("Buscar por contenido").performScrollTo().performClick()
    }

    @Test fun theFeatureIsOptInExplainsItsCostsAndNameSearchWorksWithoutIt() {
        open()
        waitForText("Encuentra fotos por lo que se ve")
        waitForText("Privacidad")
        waitForText("Las fotos, sus vectores y tus búsquedas nunca salen del móvil", substring = true)
        waitForText("Activar la búsqueda por contenido")
        shot("semantic-intro")
        // Name search is there with the feature off.
        compose.onNodeWithText("Nombre, carpeta, fecha o tipo").performTextInput("eval_cat")
        waitForCell("eval_cat.jpg")
        waitForText("Solo se busca por nombre", substring = true)
        assertTrue(compose.onAllNodes(hasContentDescription("eval_coffee.jpg", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test fun enabledWithoutTheModelOffersTheDownloadAndKeepsNameSearch() {
        val dir = container.modelStore.dir(pack)
        val away = File(dir.path + ".away")
        if (dir.exists()) assertTrue(dir.renameTo(away))
        try {
            open()
            waitForText("Activar la búsqueda por contenido")
            compose.onNodeWithText("Activar la búsqueda por contenido").performClick()
            waitForText("Falta descargar el modelo")
            waitForText("huggingface.co", substring = true)
            waitForText("Descargar solo con Wi-Fi")
            shot("semantic-model-missing")
            compose.onNodeWithText("Nombre, carpeta, fecha o tipo", substring = true).performTextInput("eval_page")
            waitForCell("eval_page.jpg")
            waitForText("necesita el modelo", substring = true)
        } finally {
            if (away.exists()) { dir.deleteRecursively(); away.renameTo(dir) }
        }
    }

    @Test fun withTheModelTheLibraryIsIndexedAndAContentSearchFindsThePicture() {
        assumeTrue("model pack not on the device", container.modelStore.status(pack) != PackStatus.MISSING)
        open()
        waitForText("Activar la búsqueda por contenido")
        compose.onNodeWithText("Activar la búsqueda por contenido").performClick()
        waitForText("3 de 3 fotos se pueden buscar", substring = true, timeout = 120_000)
        shot("semantic-ready")
        compose.onNodeWithText("Playa al atardecer, mi perro, recibos…").performTextInput("a cup of coffee")
        waitForText("Por contenido · ", substring = true)
        waitForCell("eval_coffee.jpg")
        shot("semantic-results")
        // The first cell of the content results is the coffee picture.
        val first = compose.onAllNodes(hasContentDescription("eval_", substring = true)).fetchSemanticsNodes().first()
        val desc = first.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].firstOrNull().orEmpty()
        assertTrue("the best match is the coffee picture, got: $desc", desc.contains("eval_coffee.jpg"))
    }

    @Test fun aSpanishQueryIsShownAsTheEnglishOneThatWasRun() {
        assumeTrue("model pack not on the device", container.modelStore.status(pack) != PackStatus.MISSING)
        open()
        compose.onNodeWithText("Activar la búsqueda por contenido").performClick()
        waitForText("3 de 3 fotos se pueden buscar", substring = true, timeout = 120_000)
        compose.onNodeWithText("Playa al atardecer, mi perro, recibos…").performTextInput("gato")
        waitForText("Se ha buscado como: «cat»", substring = true)
        waitForCell("eval_cat.jpg")
    }

    @Test fun deletingVectorsAsksNothingOfTheModelAndLeavesPhotosAlone() {
        assumeTrue("model pack not on the device", container.modelStore.status(pack) != PackStatus.MISSING)
        open()
        compose.onNodeWithText("Activar la búsqueda por contenido").performClick()
        waitForText("3 de 3 fotos se pueden buscar", substring = true, timeout = 120_000)
        compose.onNodeWithText("Datos y gestión").performScrollTo().performClick()
        compose.onNodeWithText("Borrar los vectores").performScrollTo().performClick()
        waitForText("0 de 3 fotos se pueden buscar", substring = true)
        val photos = runBlocking(Dispatchers.IO) { container.db.media().getEverything().count { it.folderName == "S3VaultSemantic" && it.present } }
        assertTrue("the photos are untouched", photos == 3)
    }
}
