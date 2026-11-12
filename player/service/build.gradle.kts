plugins { alias(libs.plugins.buxes.android.library) }

android {
    namespace = "app.buxes.player.service"
}

dependencies {
    api(project(":player:api"))
    api(project(":sources:api"))
    implementation(libs.media3.exoplayer)
    // Live radio's streams are live HLS (D-90); DefaultMediaSourceFactory finds it on the classpath.
    implementation(libs.media3.exoplayer.hls)
    // Custom sources (D-91) may hand over a DASH manifest (one lossless representation, say).
    implementation(libs.media3.exoplayer.dash)
    api(libs.media3.session)
    implementation(libs.media3.common)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.androidx.core.ktx)

    testImplementation(testFixtures(project(":sources:api")))
    testImplementation(libs.media3.test.utils)
    testImplementation(libs.media3.test.utils.robolectric)
}
