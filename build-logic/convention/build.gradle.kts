plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.compose.compiler.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("jvmLibrary") {
            id = "buxes.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
        register("androidLibrary") {
            id = "buxes.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "buxes.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidApplication") {
            id = "buxes.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
    }
}
