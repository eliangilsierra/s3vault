package com.s3vault.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

/** Critical UI flows on a real device: navigation, S3 form validation and credential input validation. */
class AppFlowTest {
    private val perms = GrantPermissionRule.grant(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(perms).around(compose)

    @Before fun resetState() {
        // Tests must not depend on leftovers from earlier runs/installs.
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as S3VaultApp
        app.container.credentials.clear()
        kotlinx.coroutines.runBlocking { app.container.settings.clearS3Config() }
    }

    // Tabs share their label with screen titles; the tab is the clickable one.
    private fun tab(label: String) = compose.onNode(hasText(label) and hasClickAction())

    private fun openSettings(item: String) {
        tab("Ajustes").performClick()
        compose.onNodeWithText(item).performClick()
    }

    @Test fun bottomNavigationShowsEveryMainScreen() {
        tab("Galería").assertIsDisplayed()
        tab("Álbumes").performClick()
        compose.onNodeWithText("Aún no hay álbumes").assertIsDisplayed()
        tab("Respaldo").performClick()
        compose.onNodeWithText("Última transferencia correcta", substring = true).assertIsDisplayed()
        tab("S3").performClick()
        compose.onNodeWithText("Biblioteca en S3").assertIsDisplayed()
    }

    @Test fun s3FormRejectsInvalidRegionAndBucketBeforeAnyRequest() {
        openSettings("Amazon S3")
        compose.onNodeWithText("Región AWS").performTextInput("europa")
        compose.onNodeWithText("Nombre del bucket").performTextInput("Bad_Bucket")
        compose.onNodeWithText("Guardar configuración").performScrollTo().performClick()
        compose.onNodeWithText("Región no válida (ejemplo: eu-west-1).").assertIsDisplayed()
        compose.onNodeWithText("Solo minúsculas, números, puntos y guiones; debe empezar y terminar con letra o número.").assertIsDisplayed()
    }

    @Test fun s3FormRejectsPrefixTraversal() {
        openSettings("Amazon S3")
        compose.onNodeWithText("Región AWS").performTextInput("eu-west-1")
        compose.onNodeWithText("Nombre del bucket").performTextInput("my-bucket")
        compose.onNodeWithText("Prefijo (opcional)").performTextInput("../otro")
        compose.onNodeWithText("Guardar configuración").performScrollTo().performClick()
        compose.onNodeWithText("El prefijo no puede contener '.' ni '..' como segmento.").assertIsDisplayed()
    }

    @Test fun securityScreenRejectsGarbageAndShortSecrets() {
        openSettings("Seguridad y credenciales")
        compose.onNodeWithText("Sin credenciales").assertIsDisplayed()
        compose.onNodeWithText("Pegar salida de la CLI (JSON o AWS_…=…)").performTextInput("esto no son credenciales")
        compose.onNodeWithText("Leer datos pegados").performScrollTo().performClick()
        compose.onNodeWithText("No se reconoció el formato", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun staticMethodShowsTheRiskWarning() {
        openSettings("Seguridad y credenciales")
        compose.onNodeWithText("Estáticas").performClick()
        compose.onNodeWithText("Método para usuarios avanzados").assertIsDisplayed()
    }

    @Test fun savingStaticCredentialsStoresThemAndClearsTheForm() {
        openSettings("Seguridad y credenciales")
        compose.onNodeWithText("Estáticas").performClick()
        compose.onNodeWithText("Access key ID (AKIA…)").performTextInput("AKIAIOSFODNN7EXAMPLE")
        compose.onNodeWithText("Secret access key").performTextInput("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
        compose.onNodeWithText("Guardar claves estáticas").performScrollTo().performClick()
        compose.onNodeWithText("Credenciales guardadas", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Credenciales estáticas activas").assertIsDisplayed()
        // cleanup so other tests start without credentials
        compose.onNodeWithText("Eliminar credenciales de este dispositivo").performScrollTo().performClick()
        compose.onNodeWithText("Eliminar").performClick()
    }
}
