import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("io.ktor.plugin") version "3.1.1"
    application
}

group = "com.homeassistant"
version = "1.0.0"

application {
    mainClass.set("com.homeassistant.ApplicationKt")
}

repositories {
    mavenCentral()
}

val ktorVersion = "3.1.1"
val exposedVersion = "0.57.0"
val caffeineVersion = "3.1.8"

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-config-yaml:$ktorVersion")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Exposed ORM + SQLite
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    // Caffeine cache (session TTL)
    implementation("com.github.ben-manes.caffeine:caffeine:$caffeineVersion")

    // Anthropic Java SDK
    implementation("com.anthropic:anthropic-java:0.8.0")

    // DJL for embeddings
    implementation("ai.djl:api:0.31.0")
    implementation("ai.djl.huggingface:tokenizers:0.31.0")
    implementation("ai.djl.pytorch:pytorch-engine:0.31.0")
    implementation("ai.djl.pytorch:pytorch-native-auto:2.5.1")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.12")

    // HTTP client (for internal use if needed)
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")

    // Testing
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("io.mockk:mockk:1.13.13")
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
