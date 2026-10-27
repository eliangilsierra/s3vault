import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Optional release signing. `keystore.properties` (git-ignored, repo root) holds storeFile, storePassword, keyAlias and
// keyPassword; for CI the same four values can come from the environment instead (S3VAULT_STORE_FILE, S3VAULT_STORE_PASSWORD,
// S3VAULT_KEY_ALIAS, S3VAULT_KEY_PASSWORD). Without either, the release build stays unsigned. No key or password is in the repository.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun signingValue(property: String, environment: String): String? = keystoreProps.getProperty(property) ?: System.getenv(environment)
val signingStoreFile = signingValue("storeFile", "S3VAULT_STORE_FILE")
val signingStorePassword = signingValue("storePassword", "S3VAULT_STORE_PASSWORD")
val signingKeyAlias = signingValue("keyAlias", "S3VAULT_KEY_ALIAS")
val signingKeyPassword = signingValue("keyPassword", "S3VAULT_KEY_PASSWORD")
val hasReleaseSigning = listOf(signingStoreFile, signingStorePassword, signingKeyAlias, signingKeyPassword).all { !it.isNullOrBlank() }

val withFaces = ((findProperty("withFaces") as String?) ?: "true").toBoolean()

android {
    namespace = "com.s3vault.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.s3vault.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 8
        versionName = "0.5.0"
        testInstrumentationRunner = "com.s3vault.app.S3VaultTestRunner"
        buildConfigField("boolean", "FACES", withFaces.toString())
    }

    // `-PwithFaces=false` builds the app without the People feature: the two face models are left out of the package (about 18 MiB
    // less) and no screen, setting or worker reaches the feature. The default keeps it (docs/face-grouping.md).
    if (!withFaces) androidResources { ignoreAssetsPatterns += "!faces" }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    // Release code (optimised, not debuggable, no tooling) signed with the debug key so it can be installed and instrumented
    // next to a debug build: `-PperfBuildType=benchmark` runs the on-device performance tests against it instead of the debug
    // build, to tell app cost from debug-build overhead (see docs/performance.md).
    testBuildType = (findProperty("perfBuildType") as String?) ?: "debug"

    buildTypes {
        create("benchmark") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
        release {
            // ONNX Runtime (the optional on-device search) ships a native library per ABI: 19-27 MiB each on ARM, 33 MiB on x86.
            // Phones that run Android 10+ are 64-bit ARM, so release carries only arm64-v8a (33.5 MiB APK). Pass
            // `-PreleaseAbis=arm64-v8a,armeabi-v7a` for 32-bit ARM (53 MiB) or `...,x86_64` to run a release build on the emulator
            // (the `benchmark` type keeps every ABI).
            ndk { abiFilters += ((findProperty("releaseAbis") as String?) ?: "arm64-v8a").split(",").map { it.trim() } }
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main").java.srcDir("src/main/kotlin")
        getByName("test").java.srcDir("src/test/kotlin")
        getByName("androidTest") {
            java.srcDir("src/androidTest/kotlin")
            assets.srcDir("$projectDir/schemas")
            // The public-domain photographs of the face tests are shared with the JVM tests (assets/faces/*.jpg).
            assets.srcDir("$projectDir/src/test/resources")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/INDEX.LIST",
                "/META-INF/io.netty.versions.properties",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.coroutines.android)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)

    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    // On-device semantic search only (optional feature; the model is downloaded by the user, never bundled). MIT licence.
    implementation(libs.onnxruntime.android)

    implementation(libs.aws.s3)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.org.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.room.testing)
    testImplementation(libs.work.testing)
    testImplementation(libs.turbine)
    // The desktop build of ONNX Runtime, so the face pipeline can be tested against the real models on the JVM (see the exclusion below).
    testImplementation(libs.onnxruntime.jvm)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(libs.mockwebserver)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.work.testing)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}

// Optional: run the JVM tests with another JDK (`-PtestJavaHome=<path>`). Only needed on a Windows machine whose JDK ships a Visual C++
// runtime older than the one the desktop ONNX Runtime needs: the JVM then dies inside msvcp140.dll when the face-model tests load the
// real models. A copy of the JDK with the system's current msvcp140/vcruntime140 DLLs works (see docs/testing.md).
tasks.withType<Test>().configureEach {
    (findProperty("testJavaHome") as String?)?.let { home ->
        executable = File(home, "bin/java" + if (org.gradle.internal.os.OperatingSystem.current().isWindows) ".exe" else "").path
    }
}

// Unit tests run on the JVM: they use the desktop ONNX Runtime above, not the Android one (two copies of the same classes would clash).
configurations.matching { it.name.contains("UnitTest", ignoreCase = true) }.configureEach {
    exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
}

// OkHttp 5.4+ requires compileSdk 37 (not installed here); the AWS SDK works with 5.3.x.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.squareup.okhttp3" && requested.version?.startsWith("5.") == true) {
            useVersion("5.3.2")
        }
    }
}
