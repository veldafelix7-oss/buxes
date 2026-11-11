plugins { alias(libs.plugins.buxes.android.library) }

android {
    namespace = "app.buxes.player.remote"
}

dependencies {
    api(project(":player:api"))
    implementation(libs.androidx.core.ktx)
}
