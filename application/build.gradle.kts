plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":domain"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.mockk)
}
