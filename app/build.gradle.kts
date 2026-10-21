plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    id("application")
}

application {
    mainClass.set("com.homeassistant.app.ApplicationKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("setupEmbedding") {
    group = "application"
    description = "Installs the pinned Windows Ollama runtime and prepares the embedding model."
    mainClass.set("com.homeassistant.app.setup.OllamaSetupKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

dependencies {
    implementation(project(":adapter-inbound"))
    implementation(project(":adapter-outbound"))
    implementation(project(":common"))
    implementation(project(":configuration"))
    implementation(project(":application"))
    implementation(project(":domain"))
    implementation(project(":integration-llm"))

    // Ktor server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.config.yaml)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.kotlinx.coroutines.core)

    // Logging
    implementation(libs.logback.classic)

    // Testing
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test)
}
