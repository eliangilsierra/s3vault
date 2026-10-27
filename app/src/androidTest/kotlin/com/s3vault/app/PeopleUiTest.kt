package com.s3vault.app

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.s3vault.app.domain.AuthMethod
import com.s3vault.app.domain.RemoteKeys
import com.s3vault.app.domain.S3Config
import com.s3vault.app.domain.SyncState
import com.s3vault.app.domain.faces.FaceAssignment
import com.s3vault.app.domain.faces.PersonState
import com.s3vault.app.domain.faces.RuleState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.io.File
import java.security.MessageDigest

/**
 * People, in the real app, with the real models and real photographs (public domain): the first-use explanation, nothing analysed
 * until the user says so, correcting a group, the backup rule with its preview and undo, the review list and erasing the index.
 * The S3 side is a stand-in over localhost, so no real bucket and no personal photo is involved.
 */
class PeopleUiTest {
    private val perms = GrantPermissionRule.grant(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(perms).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container get() = (instrumentation.targetContext.applicationContext as S3VaultApp).container
    private val inserted = mutableListOf<android.net.Uri>()
    private lateinit var server: MockWebServer
    private lateinit var s3: FakeS3Dispatcher

    /** No automatic group forms from these (each person's pairs are below the linking similarity, and there is no group photo). */
    private val photos = listOf("collins_1", "collins_2", "collins_3", "ride_1", "ride_2", "glenn_1", "glenn_2")

    private fun bytes(name: String) = instrumentation.context.assets.open("faces/$name.jpg").use { it.readBytes() }

    @Before fun setUp() {
        compose.waitUntil(30_000) { container.library.hasScanned || container.mediaAccess() == com.s3vault.app.sync.MediaAccess.NONE }
        s3 = FakeS3Dispatcher("test-bucket", allowedPrefix = "ui/")
        server = MockWebServer().also { it.dispatcher = s3; it.start() }
        runBlocking(Dispatchers.IO) {
            container.facePeople.deleteIndex()
            container.settings.setFaceEnabled(false); container.settings.setFaceIntroSeen(false)
            photos.forEach { inserted += BulkPerfSupport.insertBytes("face_ui_$it.jpg", bytes(it), "Pictures/S3VaultFaceUi") }
            container.library.refresh(force = true)
            container.db.openHelper.writableDatabase.execSQL("DELETE FROM media_items WHERE folderName != 'S3VaultFaceUi'")
            container.credentials.saveStatic("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
            container.settings.saveS3Config(S3Config("eu-west-1", "test-bucket", "ui/", AuthMethod.STATIC))
        }
        container.endpointOverride = server.url("/").toString().trimEnd('/')
    }

    @After fun tearDown() {
        container.endpointOverride = null
        runBlocking(Dispatchers.IO) {
            container.scheduler.cancelFaceIndex(); container.scheduler.cancelBackup(); container.scheduler.configureFaceSchedule(false, true)
            container.settings.setPaused(false)
            container.facePeople.deleteIndex()
            container.settings.setFaceEnabled(false); container.settings.setFaceIntroSeen(false)
            container.db.clearAllTables()
            container.credentials.clear(); container.settings.clearS3Config()
        }
        inserted.forEach { BulkPerfSupport.delete(it) }
        server.close()
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

    private fun gone(text: String, substring: Boolean = false) = compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isEmpty()

    private fun click(text: String, substring: Boolean = false) {
        waitForText(text, substring)
        val node = compose.onAllNodesWithText(text, substring = substring)[0]
        runCatching { node.performScrollTo() } // dialogs and sheets have no scrolling parent: the button is already in view
        node.performClick()
    }

    private fun openPeople() {
        compose.onNodeWithContentDescription("Más opciones").performClick()
        compose.onNodeWithText("Herramientas de la biblioteca").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Personas").performScrollTo().performClick()
    }

    /** The photos are analysed with the real models, then the user makes one person of Collins's three portraits (nothing groups by itself here). */
    private fun analyseAndMakeAPerson(): Long = runBlocking(Dispatchers.IO) {
        container.settings.setFaceEnabled(true); container.settings.setFaceIntroSeen(true)
        val out = container.faceIndexer.run(maxPhotos = 100)
        assertTrue("analysed: $out", out is com.s3vault.app.sync.FaceRunOutcome.Done)
        assertEquals("no group forms by itself from these photos", 0, container.db.faces().people().size)
        val ids = media().filter { it.displayName.contains("collins") }.map { it.mediaStoreId }
        val faces = ids.map { container.db.faces().facesOfPhoto(it).single().id }
        container.facePeople.moveFaces(faces, null).targetId!!
    }

    private fun media() = runBlocking(Dispatchers.IO) { container.db.media().getEverything().filter { it.folderName == "S3VaultFaceUi" } }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------ first use

    @Test fun nothingIsAnalysedUntilTheUserHasReadTheExplanationAndSaysYes() {
        openPeople()
        waitForText("Tus fotos, agrupadas por persona")
        shot("people-not-started")
        click("Empezar…")
        waitForText("Antes de empezar")
        waitForText("Ninguna foto, rostro ni dato facial se envía a internet", substring = true)
        waitForText("Puedes borrar todo el índice facial", substring = true)
        shot("people-intro")
        click("Ahora no")
        compose.waitForIdle()
        assertFalse(runBlocking { container.settings.faceEnabled.first() })
        assertEquals("nothing was analysed", 0, runBlocking(Dispatchers.IO) { container.db.faces().observeFaceCount().first() })
        waitForText("Tus fotos, agrupadas por persona")
    }

    @Test fun sayingYesStartsTheRealAnalysisAndTheScreenShowsWhatItFound() {
        openPeople()
        click("Empezar…")
        click("Activar análisis de rostros")
        compose.waitUntil(10_000) { runBlocking { container.settings.faceEnabled.first() } }
        // The real worker analyses the seven photographs with the real models.
        compose.waitUntil(120_000) { runBlocking(Dispatchers.IO) { container.db.faces().observeFaceCount().first() } >= 7 }
        waitForText("Aún no hay grupos fiables")
        waitForText("Se han encontrado", substring = true)
        shot("people-no-groups")
        assertEquals(7, runBlocking(Dispatchers.IO) { container.db.media().getEverything().count { it.folderName == "S3VaultFaceUi" } })
        assertEquals("the faces were stored sealed", 0, runBlocking(Dispatchers.IO) {
            container.db.faces().sampleSealed().count { it.size < 20 }
        })
    }

    // ------------------------------------------------------------------ correcting a group

    @Test fun aPersonCanBeNamedAndAPhotoTakenOutWithoutTouchingThePhoto() {
        val person = analyseAndMakeAPerson()
        openPeople()
        waitForText("Persona 1")
        waitForText("3 fotos")
        shot("people-grid")
        click("Persona 1")
        waitForText("Fotos de Persona 1")
        waitForText("Este grupo es una estimación automática", substring = true)
        compose.onNodeWithContentDescription("Cambiar nombre").performClick()
        waitForText("Nombre de esta persona")
        compose.onNodeWithText("Nombre").performTextInput("Ana")
        click("Guardar")
        waitForText("Fotos de Ana")
        assertEquals("Ana", runBlocking(Dispatchers.IO) { container.db.faces().person(person)!!.name })

        compose.onNode(hasContentDescription("face_ui_collins_2.jpg", substring = true)).performTouchInput { longClick() }
        waitForText("1 seleccionada")
        compose.onNodeWithContentDescription("Quitar de este grupo").performClick()
        waitForText("¿Quitar 1 foto de este grupo?")
        waitForText("no se volverán a sugerir", substring = true)
        click("Quitar del grupo")
        waitForText("Quitada 1 foto del grupo. No se borró nada.")
        waitForText("2 fotos")
        runBlocking(Dispatchers.IO) {
            val m = media().first { it.displayName.contains("collins_2") }
            assertTrue("the photo is untouched and present", m.present && m.syncState == SyncState.LOCAL_ONLY)
            val face = container.db.faces().facesOfPhoto(m.mediaStoreId).single()
            assertNull(face.personId)
            assertEquals("remembered as not this person", listOf(person), container.db.faces().rejectionsOf(listOf(face.id)).map { it.personId })
        }
    }

    // ------------------------------------------------------------------ the backup rule

    @Test fun theRuleShowsAPreviewTakesOutAPhotoAndTheExistingWorkerUploadsOnlyWhatWasConfirmed() {
        val person = analyseAndMakeAPerson()
        openPeople()
        click("Persona 1")
        click("Incluir en respaldo automático")
        waitForText("Respaldo automático de Persona 1")
        waitForText("Solo las nuevas")
        // New photos only is the default: nothing existing is touched.
        waitForText("Solo las fotos que entren en tu galería a partir de ahora", substring = true)
        click("También las que ya tengo")
        waitForText("Se añadirían 3 fotos", substring = true)
        shot("people-rule-preview")
        // the sheet thumbnail (a checkbox), not the grid cell behind it
        compose.onNode(hasContentDescription("face_ui_collins_3.jpg", substring = true) and androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Checkbox)).performClick()
        waitForText("1 foto excluida.")
        click("Continuar")
        waitForText("Esto es lo que va a pasar con Persona 1:")
        waitForText("pasarán a la cola 2 fotos que ya tienes", substring = true)
        waitForText("Se sube la foto original completa", substring = true)
        shot("people-rule-confirm")
        // Nothing has been queued yet: the user has not said yes.
        assertEquals(0, media().count { it.syncState == SyncState.QUEUED || it.syncState == SyncState.BACKED_UP })
        click("Activar respaldo automático")
        waitForText("Respaldo activado: 2 fotos en la cola.")
        compose.waitUntil(120_000) { s3.objects.size == 2 }
        val originals = listOf("collins_1", "collins_2").map { sha(bytes(it)) }.toSet()
        assertEquals("the whole original of each confirmed photo, and nothing else", originals, s3.objects.values.map { sha(it.bytes) }.toSet())
        assertTrue(s3.objects.keys.all { it.startsWith("ui/photos/") })
        assertEquals("the excluded photo and the other people's stay on the device", 5, media().count { it.syncState == SyncState.LOCAL_ONLY })
        assertEquals(RuleState.ACTIVE, runBlocking(Dispatchers.IO) { container.db.faces().rule(person)!!.state })
    }

    @Test fun undoingTheRuleTakesBackWhatNeverStartedUploadingAndBackupsAreNeverTouched() {
        val person = analyseAndMakeAPerson()
        runBlocking { container.backup.pause() } // keep the queue as it is, so there is something to take back
        openPeople()
        click("Persona 1")
        click("Incluir en respaldo automático")
        click("También las que ya tengo")
        waitForText("Se añadirían 3 fotos", substring = true)
        click("Continuar")
        click("Activar respaldo automático")
        waitForText("Respaldo activado: 3 fotos en la cola.")
        assertEquals(3, media().count { it.syncState == SyncState.QUEUED })
        compose.onNodeWithText("Deshacer").performClick()
        waitForText("salieron de la cola", substring = true)
        assertEquals(0, media().count { it.syncState == SyncState.QUEUED })
        assertTrue("back to how they were: not marked as excluded", media().none { it.backupExcluded })
        assertNull(runBlocking(Dispatchers.IO) { container.db.faces().rule(person) })
        assertTrue("nothing reached S3", s3.objects.isEmpty())
    }

    @Test fun anUnreviewedGroupMustBeConfirmedBeforeItsRuleCanContinue() {
        val person = analyseAndMakeAPerson()
        runBlocking(Dispatchers.IO) { container.db.faces().setState(person, PersonState.PROVISIONAL, System.currentTimeMillis()) }
        openPeople()
        click("Persona 1")
        waitForText("Grupo sin revisar")
        click("Incluir en respaldo automático")
        waitForText("Primero confirma el grupo")
        compose.onNodeWithText("Continuar").assertIsNotEnabled()
        compose.onNodeWithText("También las que ya tengo").performClick()
        click("Confirmar grupo")
        waitForText("Se añadirían 3 fotos", substring = true)
        compose.onNodeWithText("Continuar").assertIsEnabled()
        assertEquals(PersonState.CONFIRMED, runBlocking(Dispatchers.IO) { container.db.faces().person(person)!!.state })
        assertEquals("nothing was queued by confirming", 0, media().count { it.syncState == SyncState.QUEUED })
    }

    @Test fun withoutS3TheRuleIsSavedAndSaysSo() {
        analyseAndMakeAPerson()
        runBlocking(Dispatchers.IO) { container.settings.clearS3Config() }
        openPeople()
        click("Persona 1")
        click("Incluir en respaldo automático")
        waitForText("Falta configurar Amazon S3")
        click("Continuar")
        click("Activar respaldo automático")
        waitForText("Regla guardada. Configura Amazon S3", substring = true)
        assertEquals(0, media().count { it.syncState == SyncState.QUEUED })
    }

    // ------------------------------------------------------------------ the review list

    @Test fun aSuggestionWaitsForTheUserAndAnsweringYesConfirmsIt() {
        val person = analyseAndMakeAPerson()
        val ride = runBlocking(Dispatchers.IO) {
            val id = media().first { it.displayName.contains("ride_1") }.mediaStoreId
            container.db.faces().facesOfPhoto(id).single().also { container.db.faces().suggest(it.id, person, 0.55f, 0.1f) }
        }
        openPeople()
        waitForText("Hay 1 cosa por revisar")
        compose.onNodeWithContentDescription("Revisar", substring = true).performClick()
        waitForText("¿Es Persona 1?")
        waitForText("Mientras no respondas no pertenecen a nadie", substring = true)
        shot("people-review")
        assertEquals("a suggestion belongs to nobody yet", null, runBlocking(Dispatchers.IO) { container.db.faces().face(ride.id)!!.personId })
        compose.onNodeWithContentDescription("Sí, es Persona 1").performClick()
        waitForText("1 rostro confirmado.")
        val after = runBlocking(Dispatchers.IO) { container.db.faces().face(ride.id)!! }
        assertEquals(person, after.personId); assertEquals(FaceAssignment.CONFIRMED, after.assignment)
    }

    // ------------------------------------------------------------------ appearance

    @Test fun thePeopleScreensAreReadableInTheDarkTheme() = walkAllScreens("1.0")

    @Test fun thePeopleScreensStillFitAtLargeText() = walkAllScreens("1.5")

    /** Every People screen in the dark theme at the given system text size: each shows its own title and the controls stay on screen. */
    private fun walkAllScreens(fontScale: String) {
        val person = analyseAndMakeAPerson()
        runBlocking(Dispatchers.IO) {
            container.settings.setTheme(com.s3vault.app.settings.ThemeMode.DARK)
            val ride = media().first { it.displayName.contains("ride_1") }.mediaStoreId
            container.db.faces().facesOfPhoto(ride).single().also { container.db.faces().suggest(it.id, person, 0.55f, 0.1f) }
            container.settings.setFaceEnabled(true)
        }
        try {
            instrumentation.uiAutomation.executeShellCommand("settings put system font_scale $fontScale").close()
            compose.waitForIdle()
            val tag = "people-dark-$fontScale"
            openPeople()
            waitForText("Persona 1")
            shot("$tag-grid")
            click("Persona 1")
            waitForText("Fotos de Persona 1")
            shot("$tag-detail")
            click("Incluir en respaldo automático")
            waitForText("Respaldo automático de Persona 1")
            click("También las que ya tengo")
            waitForText("Se añadirían 3 fotos", substring = true)
            shot("$tag-rule")
            androidx.test.espresso.Espresso.pressBack(); compose.waitForIdle()   // closes the sheet
            androidx.test.espresso.Espresso.pressBack(); compose.waitForIdle()   // back to the grid
            compose.onNodeWithContentDescription("Revisar", substring = true).performClick()
            waitForText("¿Es Persona 1?")
            Thread.sleep(1500) // only so the screenshot is taken after the crops were cut
            shot("$tag-review")
            androidx.test.espresso.Espresso.pressBack(); compose.waitForIdle()
            compose.onNodeWithContentDescription("Más opciones").performClick()
            click("Ajustes de personas")
            waitForText("Rostros guardados")
            shot("$tag-settings")
        } finally {
            instrumentation.uiAutomation.executeShellCommand("settings put system font_scale 1.0").close()
            runBlocking(Dispatchers.IO) { container.settings.setTheme(com.s3vault.app.settings.ThemeMode.SYSTEM) }
        }
    }

    // ------------------------------------------------------------------ erasing

    @Test fun erasingTheIndexRemovesFaceDataAndLeavesEveryPhotoAndBackupAlone() {
        analyseAndMakeAPerson()
        val before = media().map { it.mediaStoreId to it.syncState }.toSet()
        openPeople()
        waitForText("Persona 1")
        compose.onNodeWithContentDescription("Más opciones").performClick()
        click("Ajustes de personas")
        waitForText("Rostros guardados")
        click("Borrar índice facial")
        waitForText("¿Borrar el índice facial?")
        waitForText("no se tocan", substring = true)
        click("Borrar índice")
        runBlocking { compose.waitUntil(30_000) { runBlocking(Dispatchers.IO) { container.db.faces().people().isEmpty() } } }
        runBlocking(Dispatchers.IO) {
            assertEquals(0, container.db.faces().observeFaceCount().first())
            assertEquals(0, container.db.faces().sampleSealed().size)
        }
        compose.waitUntil(10_000) { !runBlocking { container.settings.faceEnabled.first() } } // turning the analysis off is part of erasing
        assertEquals("every photo is still there, in the same state", before, media().map { it.mediaStoreId to it.syncState }.toSet())
        assertTrue(s3.methods.none { it == "DELETE" })
    }
}
