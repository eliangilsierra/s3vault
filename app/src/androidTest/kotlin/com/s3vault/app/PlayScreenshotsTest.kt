package com.s3vault.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.s3vault.app.settings.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.sin

/**
 * Takes the gallery, selection, tools, settings and help screenshots for the store listing draft. The pictures are landscapes drawn by
 * code (sky, sun, hills), so no third-party photo or personal photo is in them. Run with the display at 1080x2160 (`adb shell wm size
 * 1080x2160`): Google Play does not accept screenshots whose long side is more than twice the short one. The files land in
 * `Android/data/com.s3vault.app/files/shots/play-*.png`.
 */
class PlayScreenshotsTest {
    private val perms = GrantPermissionRule.grant(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(perms).around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext
    private val container get() = (ctx.applicationContext as S3VaultApp).container
    private val inserted = mutableListOf<android.net.Uri>()

    private fun landscape(seed: Int): ByteArray {
        val w = 1200; val h = 900
        val rnd = java.util.Random(seed.toLong())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val hue = (seed * 37 % 360).toFloat()
        val top = Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.55f))
        val bottom = Color.HSVToColor(floatArrayOf((hue + 25f) % 360, 0.35f, 1.0f))
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, 0f, h * 0.7f, top, bottom, Shader.TileMode.CLAMP) })
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.argb(235, 255, 244, 214)
        c.drawCircle(w * (0.2f + rnd.nextFloat() * 0.6f), h * (0.25f + rnd.nextFloat() * 0.2f), 70f + rnd.nextInt(40), p)
        for (layer in 0 until 3) {
            val base = h * (0.55f + layer * 0.14f)
            val amp = 60f - layer * 12f
            val freq = 0.004f + rnd.nextFloat() * 0.004f + layer * 0.002f
            val phase = rnd.nextFloat() * 6f
            val path = Path().apply {
                moveTo(0f, h.toFloat())
                var x = 0f
                while (x <= w) { lineTo(x, base + amp * sin(x * freq + phase).toFloat()); x += 6f }
                lineTo(w.toFloat(), h.toFloat()); close()
            }
            p.color = Color.HSVToColor(floatArrayOf((hue + 90f + layer * 20f) % 360, 0.45f, 0.62f - layer * 0.17f))
            c.drawPath(path, p)
        }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    @Before fun setUp() {
        runBlocking(Dispatchers.IO) { container.settings.setTheme(ThemeMode.LIGHT) }
        for (i in 1..18) inserted += BulkPerfSupport.insertBytes("IMG_2026_%02d.jpg".format(i), landscape(i), "Pictures/S3VaultPlay")
        runBlocking(Dispatchers.IO) { container.library.refresh(force = true) }
    }

    @After fun tearDown() {
        inserted.forEach { BulkPerfSupport.delete(it) }
        runBlocking(Dispatchers.IO) { container.settings.setTheme(ThemeMode.SYSTEM) }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(600) // thumbnails decode off the main thread
        val bmp = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(ctx.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test fun storeScreenshots() {
        compose.waitUntil(30_000) { compose.onAllNodes(hasContentDescription("IMG_2026_", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        shot("play-1-galeria")
        compose.onAllNodes(hasContentDescription("IMG_2026_0", substring = true))[0].performTouchInput { longClick() }
        compose.onAllNodes(hasContentDescription("IMG_2026_0", substring = true))[1].performClick()
        compose.onAllNodes(hasContentDescription("IMG_2026_0", substring = true))[2].performClick()
        shot("play-2-seleccion")
        compose.onNodeWithContentDescription("Cancelar selección").performClick()
        compose.onNodeWithContentDescription("Más opciones").performClick()
        compose.onNodeWithText("Herramientas de la biblioteca").performClick()
        compose.waitUntil(15_000) { shown("Herramientas") }
        shot("play-3-herramientas")
        compose.onNodeWithContentDescription("Volver").performClick()
        compose.onNodeWithText("Ajustes").performClick()
        compose.waitUntil(15_000) { shown(ctx.getString(R.string.settings_help_subtitle)) }
        shot("play-4-ajustes")
        compose.onNodeWithText(ctx.getString(R.string.settings_help_subtitle)).performClick()
        compose.waitUntil(15_000) { shown(ctx.getString(R.string.help_replay_title)) }
        compose.onNodeWithText(ctx.getString(R.string.help_cost_title)).performClick()
        shot("play-5-ayuda")
    }
}
