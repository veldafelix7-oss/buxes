plugins { alias(libs.plugins.buxes.android.library) }

android {
    namespace = "app.buxes.sources.test"
}

dependencies {
    api(project(":sources:api"))
}
