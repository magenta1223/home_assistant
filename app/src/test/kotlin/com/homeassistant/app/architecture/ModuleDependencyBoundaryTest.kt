package com.homeassistant.app.architecture

import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModuleDependencyBoundaryTest {
    private val projectRoot = generateSequence(java.nio.file.Path.of("").toAbsolutePath()) { it.parent }
        .first { it.resolve("settings.gradle.kts").toFile().exists() }

    @Test
    fun `gradle project dependencies follow module direction`() {
        val actual = MODULES.associateWith { module ->
            projectDependencies(module)
        }

        assertEquals(emptySet(), actual.getValue("domain"))
        assertEquals(emptySet(), actual.getValue("common"))
        assertEquals(emptySet(), actual.getValue("configuration"))
        assertEquals(setOf("domain"), actual.getValue("application"))
        assertEquals(
            setOf("application", "domain", "common", "configuration"),
            actual.getValue("adapter-inbound"),
        )
        assertEquals(
            setOf("application", "domain", "common", "configuration"),
            actual.getValue("adapter-outbound"),
        )
        assertEquals(
            setOf("adapter-inbound", "adapter-outbound", "application", "domain", "common", "configuration"),
            actual.getValue("app"),
        )
    }

    @Test
    fun `source imports follow module direction`() {
        val violations = MODULES.flatMap { module ->
            forbiddenImports(module).map { forbidden ->
                importsFor(module, forbidden)
            }.flatten()
        }

        assertTrue(
            violations.isEmpty(),
            "Module imports violate dependency direction:\n${violations.joinToString("\n")}",
        )
    }

    @Test
    fun `persistence internals are not imported outside adapter composition`() {
        val violations = listOf("domain", "application", "adapter-inbound", "app").flatMap { module ->
            persistenceInternalImportsFor(module)
        }

        assertTrue(
            violations.isEmpty(),
            "Persistence internals must not cross adapter boundaries:\n${violations.joinToString("\n")}",
        )
    }

    @Test
    fun `exposed is only imported inside persistence adapter`() {
        val violations = listOf("domain", "application", "adapter-inbound", "app").flatMap { module ->
            exposedImportsFor(module)
        } + exposedImportsOutsidePersistenceAdapter()

        assertTrue(
            violations.isEmpty(),
            "Exposed database APIs must not be imported outside persistence adapter:\n${violations.joinToString("\n")}",
        )
    }

    @Test
    fun `application and domain do not import adapter frameworks`() {
        val forbiddenPrefixes = listOf(
            "io.ktor.",
            "com.slack.api.",
            "org.jetbrains.exposed.",
        )
        val violations = listOf("domain", "application").flatMap { module ->
            forbiddenPrefixes.flatMap { prefix -> externalImportsFor(module, prefix) }
        }

        assertTrue(
            violations.isEmpty(),
            "Domain and application must remain framework independent:\n${violations.joinToString("\n")}",
        )
    }

    @Test
    fun `inbound and outbound adapters do not import each other`() {
        val violations = externalImportsFor("adapter-inbound", "com.homeassistant.adapter.outbound.") +
            externalImportsFor("adapter-outbound", "com.homeassistant.adapter.inbound.")

        assertTrue(
            violations.isEmpty(),
            "Inbound and outbound adapters must communicate through application ports:\n" +
                violations.joinToString("\n"),
        )
    }

    private fun projectDependencies(module: String): Set<String> {
        val buildFile = projectRoot.resolve("$module/build.gradle.kts")
        if (!buildFile.toFile().exists()) return emptySet()

        return Regex("""project\(":([^"]+)"\)""")
            .findAll(buildFile.readText())
            .map { it.groupValues[1] }
            .toSet()
    }

    private fun forbiddenImports(module: String): Set<String> =
        when (module) {
            "domain" -> setOf("application", "common", "configuration", "adapter.inbound", "adapter.outbound", "app")
            "application" -> setOf("common", "configuration", "adapter.inbound", "adapter.outbound", "app")
            "adapter-inbound" -> setOf("adapter.outbound", "app")
            "adapter-outbound" -> setOf("adapter.inbound", "app")
            "app" -> emptySet()
            "common", "configuration" -> emptySet()
            else -> error("Unknown module $module")
        }

    private fun importsFor(module: String, forbiddenModule: String): List<String> {
        val sourceRoot = projectRoot.resolve("$module/src/main/kotlin")
        if (!sourceRoot.toFile().exists()) return emptyList()

        return sourceRoot.walk()
            .filter { it.toString().endsWith(".kt") }
            .flatMap { file ->
                file.readText().lineSequence()
                    .filter { it.startsWith("import com.homeassistant.$forbiddenModule.") }
                    .map { "${projectRoot.relativize(file)}: $it" }
            }
            .toList()
    }

    private fun persistenceInternalImportsFor(module: String): List<String> {
        val sourceRoot = projectRoot.resolve("$module/src/main/kotlin")
        if (!sourceRoot.toFile().exists()) return emptyList()

        val allowedPersistenceImports = setOf(
            "import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory",
            "import com.homeassistant.adapter.outbound.persistence.repo.RepositoryStores",
        )

        return sourceRoot.walk()
            .filter { it.toString().endsWith(".kt") }
            .flatMap { file ->
                file.readText().lineSequence()
                    .filter { it.startsWith("import com.homeassistant.adapter.outbound.persistence.") }
                    .filterNot { it in allowedPersistenceImports }
                    .map { "${projectRoot.relativize(file)}: $it" }
            }
            .toList()
    }

    private fun exposedImportsFor(module: String): List<String> {
        val sourceRoot = projectRoot.resolve("$module/src/main/kotlin")
        if (!sourceRoot.toFile().exists()) return emptyList()

        return sourceRoot.walk()
            .filter { it.toString().endsWith(".kt") }
            .flatMap { file ->
                file.readText().lineSequence()
                    .filter { it.startsWith("import org.jetbrains.exposed.") }
                    .map { "${projectRoot.relativize(file)}: $it" }
            }
            .toList()
    }

    private fun exposedImportsOutsidePersistenceAdapter(): List<String> {
        val sourceRoot = projectRoot.resolve("adapter-outbound/src/main/kotlin")
        if (!sourceRoot.toFile().exists()) return emptyList()

        return sourceRoot.walk()
            .filter { it.toString().endsWith(".kt") }
            .filterNot { it.toString().contains("${java.io.File.separator}persistence${java.io.File.separator}") }
            .flatMap { file ->
                file.readText().lineSequence()
                    .filter { it.startsWith("import org.jetbrains.exposed.") }
                    .map { "${projectRoot.relativize(file)}: $it" }
            }
            .toList()
    }

    private fun externalImportsFor(module: String, prefix: String, packageSegment: String? = null): List<String> {
        val sourceRoot = projectRoot.resolve("$module/src/main/kotlin")
        if (!sourceRoot.toFile().exists()) return emptyList()

        return sourceRoot.walk()
            .filter { it.toString().endsWith(".kt") }
            .filter { packageSegment == null || it.toString().contains("${java.io.File.separator}$packageSegment${java.io.File.separator}") }
            .flatMap { file ->
                file.readText().lineSequence()
                    .filter { it.startsWith("import $prefix") }
                    .map { "${projectRoot.relativize(file)}: $it" }
            }
            .toList()
    }

    private companion object {
        val MODULES = listOf(
            "domain",
            "common",
            "configuration",
            "application",
            "adapter-inbound",
            "adapter-outbound",
            "app",
        )
    }
}
