plugins {
    alias(libs.plugins.buxes.android.library)
    alias(libs.plugins.buxes.android.compose)
}

android {
    namespace = "app.buxes.feature.library"
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:interaction"))
    implementation(project(":player:api"))
    implementation(project(":sources:api"))
    implementation(libs.androidx.lifecycle.runtime.compose)
}

dependencies {
    // Paper-layout screenshots in Carbon and Bone at several device sizes (Robolectric native graphics).
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(testFixtures(project(":sources:api")))
    debugImplementation(libs.compose.ui.test.manifest)
}
