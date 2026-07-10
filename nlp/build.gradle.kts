import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core"))
    api(project(":datamodel"))
    api(project(":domain"))

    // Anthropic Java SDK
    implementation(libs.anthropic.java)

    // Ktor HTTP client (OpenRouter)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.schema.generator.json)

    // Local embeddings
    implementation(libs.djl.api)
    implementation(libs.djl.huggingface.tokenizers)
    implementation(libs.djl.pytorch.engine)
    implementation(libs.djl.pytorch.native.auto)

    // Logging
    implementation(libs.logback.classic)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.mockk)
}
val compileKotlin: KotlinCompile by tasks
compileKotlin.compilerOptions {
    freeCompilerArgs.set(listOf("-Xannotation-default-target=param-property"))
}
