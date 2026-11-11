plugins {
    alias(libs.plugins.buxes.jvm.library)
    `java-test-fixtures`
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    testFixturesApi(project(":core:model"))
    testFixturesApi(project(":core:common"))
    testFixturesImplementation(libs.kotlinx.coroutines.core)
}
