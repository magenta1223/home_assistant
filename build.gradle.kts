import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktor) apply false
}

tasks.register("setupEmbedding") {
    group = "application"
    description = "Installs the pinned Windows Ollama runtime and prepares the embedding model."
    dependsOn(":app:setupEmbedding")
}

tasks.register("setupQdrant") {
    group = "application"
    description = "Installs the pinned Windows Qdrant runtime."
    dependsOn(":app:setupQdrant")
}

tasks.register("setupRuntime") {
    group = "application"
    description = "Prepares all project-managed local runtimes."
    dependsOn("setupEmbedding", "setupQdrant")
}

tasks.register<Exec>("updateSlackManifest") {
    group = "application"
    description = "Validates and updates the Slack app from slack-app-manifest.json."
    workingDir(rootDir)
    commandLine(
        "powershell.exe",
        "-NoLogo",
        "-NoProfile",
        "-NonInteractive",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        rootProject.file("scripts/update-slack-manifest.ps1").absolutePath,
    )
}

val testDeploymentScripts by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs Windows deployment script regression tests."
    onlyIf { System.getProperty("os.name").startsWith("Windows", ignoreCase = true) }
    workingDir(rootDir)
    commandLine(
        "powershell.exe",
        "-NoLogo",
        "-NoProfile",
        "-NonInteractive",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        rootProject.file("scripts/test-deploy-runtime-control.ps1").absolutePath,
    )
}

tasks.register("test") {
    group = "verification"
    description = "Runs every project and deployment script test."
    dependsOn(testDeploymentScripts)
    dependsOn(subprojects.map { "${it.path}:test" })
}

subprojects {
    group = "com.homeassistant"
    version = "1.0.0"

    repositories {
        mavenCentral()
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
