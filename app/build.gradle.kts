import java.util.Properties

plugins {
    alias(libs.plugins.buxes.android.application)
    alias(libs.plugins.buxes.android.compose)
}

// Release signing (D-69): the key and its passwords live outside git, in keystore.properties
// (ignored) next to this project, or in BUXES_KEYSTORE_* environment variables. Without either,
// the release APK is built unsigned.
val signing = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun secret(key: String, env: String): String? = signing.getProperty(key) ?: System.getenv(env)
val releaseStore = secret("storeFile", "BUXES_KEYSTORE_FILE")

android {
    namespace = "app.buxes"
    defaultConfig {
        // Provisional: the product name is pending trademark clearance (docs/repository-audit.md §5).
        applicationId = "app.buxes"
        // A device test of the updater (D-99) builds a "newer" copy: -Pbuxes.versionCode=7 -Pbuxes.versionName=0.1.5.1
        versionCode = providers.gradleProperty("buxes.versionCode").orNull?.toInt() ?: 8
        versionName = providers.gradleProperty("buxes.versionName").orNull ?: "1.5.0"
    }
    buildFeatures {
        buildConfig = true
    }
    lint {
        // MainActivity and WebSignInActivity are ComponentActivity, whose result APIs are correct;
        // the check is about FragmentActivity before fragment 1.3, which arrives only transitively.
        disable += "InvalidFragmentVersionForActivityResult"
    }
    // The cutout model is memory-mapped straight from the APK.
    androidResources {
        noCompress += "tflite"
    }
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = rootProject.file(releaseStore)
                storePassword = secret("storePassword", "BUXES_KEYSTORE_PASSWORD")
                keyAlias = secret("keyAlias", "BUXES_KEY_ALIAS")
                keyPassword = secret("keyPassword", "BUXES_KEY_PASSWORD")
            }
        }
    }
    // A build installed beside the phone's own (D-98): -Pbuxes.applicationIdSuffix=.walktest gives
    // another package, so a test needs no uninstall (which would wipe the listener's data) when the
    // installed app was signed with another key.
    val sideBySide = providers.gradleProperty("buxes.applicationIdSuffix").orNull
    buildTypes {
        debug {
            applicationIdSuffix = sideBySide ?: ".debug"
            // This is the build that lives on the phone (D-65). A debuggable app is never compiled
            // ahead of time and loses its JIT-compiled code whenever its process ends, so every
            // fresh open started cold and stuttered until it warmed up. Not debuggable, ART compiles
            // it (the libraries' baseline profiles through profileinstaller, then the phone's own
            // profile) and the first transition is as smooth as the hundredth. Debug commands, the
            // test tones and the package (app.buxes.debug, the listener's data) are unchanged.
            // For a debugger: ./gradlew installDebug -Pbuxes.debuggable=true
            isDebuggable = providers.gradleProperty("buxes.debuggable").orNull == "true"
            // R8 on a debug build, so its debug commands can prove a release's paths under
            // minification (D-99): -Pbuxes.minifyDebug=true
            if (providers.gradleProperty("buxes.minifyDebug").orNull == "true") {
                isMinifyEnabled = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            }
        }
        release {
            sideBySide?.let { applicationIdSuffix = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:interaction"))
    implementation(project(":core:database"))
    implementation(project(":core:lyrics"))
    implementation(project(":sources:api"))
    implementation(project(":sources:local"))
    implementation(project(":player:api"))
    implementation(project(":player:service"))
    implementation(project(":feature:library"))
    implementation(project(":feature:online"))
    implementation(project(":sources:youtubemusic"))
    // Custom sources (D-91): addons the listener adds for their streams.
    implementation(libs.kotlinx.serialization.json)
    // The online source's backup apps run YouTube's player script in a sandbox (D-98).
    implementation(libs.androidx.javascriptengine)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(project(":feature:nowplaying"))
    implementation(project(":feature:settings"))
    // Deterministic generated test tones: debug builds only, never shipped.

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    // Sticker cutout on the phone (D-55): LiteRT runs the bundled magic-touch model.
    implementation(libs.litert)

    // The library repository over a real (in-memory) database, on the development machine.
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.room3.runtime)
    testImplementation(libs.sqlite.bundled.jvm)
    testImplementation(testFixtures(project(":sources:api")))

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "androidx.sqlite", module = "sqlite-bundled-android")
}
