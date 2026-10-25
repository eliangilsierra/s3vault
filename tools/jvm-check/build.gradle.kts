// Arnés JVM: compila y prueba, sin Android SDK, las partes de la app que no dependen de
// APIs de Android (dominio, sincronización, S3, autenticación). Ver docs/testing.md.
plugins {
    kotlin("jvm") version "2.3.21"
}

kotlin { jvmToolchain(21) }

val appMain = "../../app/src/main/kotlin"
val appTest = "../../app/src/test/kotlin"

// Paquetes puros (sin imports de android.* / androidx.*).
val pureMain = listOf(
    "**/domain/**", "**/auth/**", "**/data/remote/**", "**/sync/*.kt", "**/security/*.kt", "**/logging/**",
    "**/presentation/Strings.kt", "**/sync/work/TransferProgressTracker.kt",
)

// Archivos que solo usan el framework de Android (no AndroidX): se comprueban de tipos contra
// `android-all` (el android.jar completo que publica Robolectric en Maven Central). No se ejecutan.
val androidFrameworkOnly = listOf("**/data/local/media/**", "**/security/keystore/**")

sourceSets {
    main { kotlin { srcDir(appMain); include(pureMain + androidFrameworkOnly) } }
    test { kotlin { srcDir(appTest); include("**/pure/**") } }
}

dependencies {
    compileOnly("org.robolectric:android-all:15-robolectric-13954326")
    implementation("aws.sdk.kotlin:s3:1.6.107")
    implementation("aws.sdk.kotlin:sso:1.6.107")
    implementation("aws.sdk.kotlin:ssooidc:1.6.107")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.test { useJUnitPlatform() }
