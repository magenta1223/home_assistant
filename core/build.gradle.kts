plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Serialization
    api(libs.kotlinx.serialization.json)

    // Dotenv
    api(libs.dotenv.kotlin)

    // Logging
    implementation(libs.logback.classic)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.mockk)
}
