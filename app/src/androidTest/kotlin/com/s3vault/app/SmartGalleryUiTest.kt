package com.s3vault.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.s3vault.app.domain.AuthMethod
import com.s3vault.app.domain.RemoteKeys
import com.s3vault.app.domain.S3Config
import com.s3vault.app.domain.SyncState
import com.s3vault.app.domain.library.DateSource
import com.s3vault.app.domain.library.GroupBy
import com.s3vault.app.domain.library.LibraryQuery
import com.s3vault.app.domain.library.SortField
import com.s3vault.app.settings.CloudViewMode
import com.s3vault.app.settings.GridDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The smart gallery on a real device: capture-date sections, search, filters, sorting, the jump-to-date sheet, and the S3
 * gallery with its "searches only the downloaded list" promise (checked against the number of requests S3 received).
 * Photos carry their date in the file name (this emulator's MediaProvider ignores any DATE_TAKEN a test writes). Screenshots go to `.../files/shots` for visual review.
 */
class SmartGalleryUiTest {
    private val perms = GrantPermissionRule.grant(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(perms).around(compose)

    @get:Rule val testName = org.junit.rules.TestName()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as S3VaultApp
    private val container get() = app.container
    private lateinit var server: MockWebServer
    private lateinit var s3: FakeS3Dispatcher
    private val inserted = mutableListOf<Uri>()
    private val keys = RemoteKeys("sg/")

    @Before fun setUp() {
        s3 = FakeS3Dispatcher("test-bucket", allowedPrefix = "sg/")
        server = MockWebServer().also { it.dispatcher = s3; it.start() }
        runBlocking(Dispatchers.IO) {
            container.db.clearAllTables()
            container.credentials.saveStatic("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
            container.settings.saveS3Config(S3Config("eu-west-1", "test-bucket", "sg/", AuthMethod.STATIC))
        }
        container.endpointOverride = server.url("/").toString().trimEnd('/')
        // The date-jump scenario needs the 2021 photo to sit far below a long list of newer pictures: bring them, whatever the device holds.
        if (testName.methodName.startsWith("localGalleryGroups")) BulkPerfSupport.insertPhotos("sgfill_", 150)
    }

    @After fun tearDown() {
        container.endpointOverride = null
        BulkPerfSupport.deletePhotos("sgfill_")
        runBlocking(Dispatchers.IO) {
            inserted.forEach { runCatching { instrumentation.targetContext.contentResolver.delete(it, null, null) } }
            instrumentation.targetContext.contentResolver.delete(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("sg_%"),
            )
            container.db.clearAllTables()
            container.credentials.clear()
            container.settings.clearS3Config()
            container.previews.clear()
            // The query state lives as long as the process: leave it as a fresh install would have it for the next test.
            val store = container.libraryStore
            store.local.value = LibraryQuery()
            store.cloud.value = LibraryQuery()
            store.density.value = GridDensity.MEDIUM
            store.cloudMode.value = CloudViewMode.GRID
            container.settings.saveLocalView(SortField.CAPTURE_DATE, true, GroupBy.DAY)
            container.settings.saveCloudView(SortField.CAPTURE_DATE, true, GroupBy.DAY)
            container.settings.saveDensity(GridDensity.MEDIUM)
            container.settings.saveCloudMode(CloudViewMode.GRID)
        }
        server.close()
    }

    // ------------------------------------------------------------------ fixtures and helpers

    private fun jpeg(name: String, hue: Int): ByteArray {
        val w = 800; val h = 600
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.8f, 0.95f)), Color.HSVToColor(floatArrayOf((hue + 70f) % 360, 0.9f, 0.35f)), Shader.TileMode.CLAMP)
        })
        val rnd = java.util.Random(name.hashCode().toLong())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(30) {
            p.color = Color.argb(160, rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
            canvas.drawCircle(rnd.nextInt(w).toFloat(), rnd.nextInt(h).toFloat(), 20f + rnd.nextInt(80), p)
        }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
    }

    private fun insert(name: String, hue: Int) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/S3VaultSmartTest")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = instrumentation.targetContext.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { it.write(jpeg(name, hue)) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        inserted += uri
    }

    /** Six photos over four years; sg_e has no date anywhere. */
    private fun seed() = runBlocking(Dispatchers.IO) {
        insert("sg_a1_20210610.jpg", 10)
        insert("sg_b1_20230315.jpg", 60)
        insert("sg_b2_20230315.jpg", 120)
        insert("sg_b3_20230315.jpg", 180)
        insert("sg_c1_20231224.jpg", 240)
        insert("sg_e1.jpg", 300)
        container.library.refresh()
        val rows = container.db.media().getEverything().filter { it.displayName.startsWith("sg_") }
        assertEquals(6, rows.size)
        assertEquals("the date written in the file name places the photo", 5, rows.count { it.dateSource == DateSource.FILENAME })
        assertEquals("no date anywhere: the file's own date, flagged", DateSource.FALLBACK, rows.first { it.displayName == "sg_e1.jpg" }.dateSource)
        assertEquals(20230315, rows.first { it.displayName == "sg_b1_20230315.jpg" }.dayKey)
    }

    private fun shot(name: String) {
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun settle() { repeat(6) { Thread.sleep(150); compose.waitForIdle() } }
    private var waits = 0

    /** Like waitUntil, but a timeout leaves a screenshot and the semantics tree in the log, which is what a failing UI test needs. */
    private fun waitFor(ms: Long = 20_000, cond: () -> Boolean) {
        try {
            compose.waitUntil(ms, cond)
        } catch (e: Throwable) {
            shot("failure-" + testName.methodName.take(24) + "-" + (++waits))
            compose.onRoot().printToLog("SmartGalleryUiTest")
            throw e
        }
    }
    private fun textShown(t: String, substring: Boolean = false) = compose.onAllNodesWithText(t, substring = substring).fetchSemanticsNodes().isNotEmpty()
    private fun descShown(d: String) = compose.onAllNodesWithContentDescription(d, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun cell(name: String) = compose.onNodeWithContentDescription(name, substring = true)
    private fun tab(label: String) = compose.onNode(hasText(label) and hasClickAction())

    /** Opens the search (if it is not open) and replaces its text. */
    private fun searchFor(text: String) {
        if (compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty()) compose.onNodeWithContentDescription("Buscar").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(text)
        compose.onNode(hasSetTextAction()).performImeAction() // "Buscar" on the keyboard: hides it so the results can be seen
        settle()
    }

    private fun closeSearch() {
        compose.onNodeWithContentDescription("Cerrar la búsqueda").performClick()
        settle()
    }

    private val a1 = "sg_a1_20210610.jpg"
    private val b1 = "sg_b1_20230315.jpg"
    private val c1 = "sg_c1_20231224.jpg"
    private val e1 = "sg_e1.jpg"

    // ------------------------------------------------------------------ local gallery

    // The emulator's own library (hundreds of undated test images) shares the list, so the photos of this test are
    // found with a search for "sg_" - which is itself part of what is being tested.
    @Test fun localGalleryGroupsByCaptureDateAndSearchesFiltersAndSorts() {
        seed()
        searchFor("sg_")
        waitFor { descShown(b1) && descShown(c1) && descShown(e1) }

        // Sections by capture date: the day headings carry the year; the undated photo is not given a made-up capture date.
        waitFor { textShown("2023", substring = true) }
        shot("20-local-by-day")

        // Group by month and by year.
        compose.onNodeWithText("Mes").performClick(); settle()
        compose.onNodeWithText("Marzo 2023").assertIsDisplayed()
        shot("21-local-by-month")
        compose.onNodeWithText("Año").performClick(); settle()
        compose.onNodeWithText("2023").assertIsDisplayed()
        compose.onNodeWithText("Día").performClick(); settle()

        // Search understands months and years next to text: three photos were taken in March 2023.
        searchFor("sg_ marzo 2023")
        waitFor { descShown(b1) && textShown("3 resultados en este dispositivo") }
        assertTrue("other dates are filtered out", !descShown(c1) && !descShown(e1))
        assertTrue("the search shows how it was understood", textShown("Marzo", substring = true) || textShown("marzo", substring = true))
        shot("22-local-search")

        // Nothing matches: say so and offer the way back.
        searchFor("zzzzzz")
        waitFor { textShown("Sin resultados") }
        shot("23-local-no-results")
        compose.onNodeWithText("Quitar búsqueda y filtros").performClick()
        settle()
        searchFor("sg_")
        waitFor { descShown(b1) && descShown(e1) }

        // Filters: only the photo without a capture date (every other undated image on the device is out by the search).
        compose.onNodeWithContentDescription("Filtros", substring = true).performClick()
        waitFor { textShown("Fecha estimada") }
        shot("24-local-filter-sheet")
        compose.onNodeWithText("Sin fecha de captura", substring = true).performClick()
        waitFor { textShown("Mostrar 1 resultado") }
        compose.onNodeWithText("Mostrar 1 resultado").performClick()
        settle()
        waitFor { descShown(e1) && !descShown(b1) }
        shot("25-local-filtered")
        compose.onNodeWithContentDescription("Quitar filtro Sin fecha de captura").performClick()
        settle()
        waitFor { descShown(b1) && descShown(c1) }

        // Sorting: by name the date headings disappear and the order is alphabetical.
        compose.onNodeWithContentDescription("Ordenar: Recientes primero").performClick()
        compose.onNodeWithText("Nombre").performClick()
        settle()
        waitFor { descShown("Ordenar: Nombre A–Z") }
        assertTrue("no date headings when ordering by name", !textShown("Marzo 2023") && !textShown("2023", substring = true))
        shot("26-local-sorted-by-name")
        compose.onNodeWithContentDescription("Ordenar: Nombre A–Z").performClick()
        compose.onNodeWithText("Fecha de captura").performClick()
        settle()

        // Jump to a date in the whole library: the 2021 photo is far below hundreds of newer images.
        compose.onNodeWithContentDescription("Cerrar la búsqueda").performClick()
        settle()
        waitFor { !descShown(a1) }
        compose.onNodeWithContentDescription("Ir a una fecha").performClick()
        waitFor { textShown("2021") }
        shot("27-timeline-sheet")
        compose.onNodeWithText("2021").performClick()
        compose.onNodeWithText("Todo 2021").performClick()
        settle()
        waitFor { descShown(a1) }
        cell(a1).assertIsDisplayed()
        shot("28-local-after-jump")
    }

    @Test fun localGallerySelectionStillWorksOnTheNewGrid() {
        seed()
        searchFor("sg_ marzo 2023")
        waitFor { descShown(b1) && textShown("3 resultados en este dispositivo") }
        cell(b1).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("1 seleccionada").assertIsDisplayed()
        // Selecting the whole 15 March section from its heading selects its three photos.
        compose.onNodeWithContentDescription("2023: seleccionar todo", substring = true).performClick()
        settle()
        shot("27-local-selection")
    }

    // ------------------------------------------------------------------ S3 gallery

    private fun backUpEverything() = runBlocking(Dispatchers.IO) {
        container.db.media().getEverything().filter { it.displayName.startsWith("sg_") && it.displayName != "sg_e1.jpg" }
            .forEach { container.db.media().enqueue(it.mediaStoreId, 1L) }
        container.backupEngine.run(1)
        container.catalog.refresh(container.remoteProvider.get()!!)
    }

    @Test fun cloudGalleryUsesCaptureDatesAndNeverPretendsToSearchTheWholeBucket() {
        seed()
        backUpEverything()
        assertEquals(5, runBlocking(Dispatchers.IO) { container.db.media().getEverything().count { it.syncState == SyncState.BACKED_UP } })
        tab("S3").performClick()
        waitFor { descShown("sg_b1_20230315.jpg") }
        settle()
        Thread.sleep(1200) // let the preview thumbnails arrive
        settle()
        // Uploaded photos reach the cache with the capture date of their local copy: no extra request.
        assertTrue(textShown("Lista descargada", substring = true))
        shot("30-cloud-grid")

        val requestsBefore = s3.methods.size
        searchFor("sg_ 2023")
        waitFor { descShown("sg_c1_20231224.jpg") && textShown("4 de 5 objetos", substring = true) }
        assertTrue("the scope is stated next to the results", compose.onAllNodesWithText("no consulta S3", substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty())
        shot("31-cloud-search-scope")

        closeSearch()
        compose.onNodeWithContentDescription("Filtros", substring = true).performClick()
        waitFor { textShown("Disponibilidad") }
        shot("32-cloud-filter-sheet")
        compose.onNodeWithText("Solo en S3").performClick()
        waitFor { textShown("Sin resultados con estos filtros") } // all five also exist on this device
        compose.onNodeWithText("También en el dispositivo").performClick()
        waitFor { textShown("Mostrar 5 resultados") }
        compose.onNodeWithText("Mostrar 5 resultados").performClick()
        settle()
        waitFor { descShown("sg_c1_20231224.jpg") }
        assertEquals("searching, filtering and sorting never touched S3", requestsBefore, s3.methods.size)

        // The list view shows the same objects as rows with their dates and where they live.
        compose.onNodeWithContentDescription("Ver como lista").performClick()
        settle()
        shot("33-cloud-list")
        assertTrue(textShown("También en este dispositivo", substring = true))
        compose.onNodeWithContentDescription("Ver como cuadrícula").performClick()
        settle()
    }

    @Test fun anObjectUploadedElsewhereOnlyAppearsAfterTheListIsRefreshed() {
        seed()
        backUpEverything()
        tab("S3").performClick()
        waitFor { descShown("sg_b1_20230315.jpg") }

        val asset = "0123456789abcdef0123456789abcdef"
        val version = "f".repeat(64)
        val key = keys.photoKey(asset, version, "sg_outside.jpg")
        s3.objects[key] = FakeS3Dispatcher.Obj(ByteArray(10), "AAAA", "image/jpeg", emptyMap())

        searchFor("outside")
        waitFor { textShown("Sin resultados") }
        assertTrue(
            "the empty result says the search never asked S3",
            compose.onAllNodesWithText("no consulta S3", substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty(),
        )
        shot("34-cloud-not-in-the-downloaded-list")
        closeSearch()
        compose.onNodeWithContentDescription("Actualizar listado").performClick()
        waitFor(30_000) { runBlocking(Dispatchers.IO) { container.db.remote().get(key) } != null }
        searchFor("outside")
        waitFor { descShown("sg_outside.jpg") }
        shot("35-cloud-found-after-refresh")
    }
}
