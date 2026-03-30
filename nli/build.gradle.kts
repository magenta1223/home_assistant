plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core"))

    // Logging
    implementation(libs.logback.classic)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.mockk)
}
