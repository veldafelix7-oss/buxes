plugins {
    alias(libs.plugins.buxes.android.library)
    alias(libs.plugins.buxes.android.compose)
}

android {
    namespace = "app.buxes.core.designsystem"
}

dependencies {
    api(project(":core:interaction"))
    implementation(libs.backdrop)
    implementation(libs.kyant.shapes)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
