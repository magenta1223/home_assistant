plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Serialization
    api(libs.kotlinx.serialization.json)

    // Exposed ORM + SQLite
    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    api(libs.exposed.dao)
    api(libs.sqlite.jdbc)

    // Caffeine cache (session TTL)
    api(libs.caffeine)

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
