plugins {
    alias(libs.plugins.buxes.android.library)
    alias(libs.plugins.buxes.android.compose)
}

android {
    namespace = "app.buxes.feature.settings"
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:interaction"))
    implementation(project(":sources:api"))
    implementation(project(":player:api"))
    implementation(libs.androidx.lifecycle.runtime.compose)
}
