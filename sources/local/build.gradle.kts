plugins { alias(libs.plugins.buxes.android.library) }

android {
    namespace = "app.buxes.sources.local"
}

dependencies {
    api(project(":sources:api"))
    implementation(libs.androidx.core.ktx)
}
