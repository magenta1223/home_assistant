plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Serialization
    api(libs.kotlinx.serialization.json)

    // DJL for embeddings
    api(libs.djl.api)
    api(libs.djl.huggingface.tokenizers)
    api(libs.djl.pytorch.engine)
    api(libs.djl.pytorch.native.auto)

    // Dotenv
    api(libs.dotenv.kotlin)

    // Logging
    implementation(libs.logback.classic)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.mockk)
}
