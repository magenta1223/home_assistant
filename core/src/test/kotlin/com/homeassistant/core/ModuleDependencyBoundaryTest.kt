package com.homeassistant.core

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
        val actual = listOf("core", "domain", "repository", "nlp", "app").associateWith { module ->
            projectDependencies(module)
        }

        assertEquals(emptySet(), actual.getValue("core"))
        assertEquals(setOf("core"), actual.getValue("domain"))
        assertEquals(setOf("core", "domain"), actual.getValue("repository"))
        assertEquals(setOf("core", "domain"), actual.getValue("nlp"))
        assertEquals(setOf("core", "domain", "nlp", "repository"), actual.getValue("app"))
    }

    @Test
    fun `source imports follow module direction`() {
        val violations = listOf("core", "domain", "repository", "nlp", "app").flatMap { module ->
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
    fun `repository internals are not imported outside repository module`() {
        val violations = listOf("core", "domain", "nlp", "app").flatMap { module ->
            repositoryInternalImportsFor(module)
        }

        assertTrue(
            violations.isEmpty(),
            "Repository internals must not be imported outside repository module:\n${violations.joinToString("\n")}",
        )
    }

    @Test
    fun `exposed is only imported inside repository module`() {
        val violations = listOf("core", "domain", "nlp", "app").flatMap { module ->
            exposedImportsFor(module)
        }

        assertTrue(
            violations.isEmpty(),
            "Exposed database APIs must not be imported outside repository module:\n${violations.joinToString("\n")}",
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
            "core" -> setOf("domain", "repository", "nlp", "app")
            "domain" -> setOf("repository", "nlp", "app")
            "repository" -> setOf("nlp", "app")
            "nlp" -> setOf("repository", "app")
            "app" -> emptySet()
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

    private fun repositoryInternalImportsFor(module: String): List<String> {
        val sourceRoot = projectRoot.resolve("$module/src/main/kotlin")
        if (!sourceRoot.toFile().exists()) return emptyList()

        val allowedRepositoryImports = setOf(
            "import com.homeassistant.repository.RepositoryFactory",
            "import com.homeassistant.repository.RepositoryStores",
            "import com.homeassistant.repository.repo.RepositoryFactory",
            "import com.homeassistant.repository.repo.RepositoryStores",
        )

        return sourceRoot.walk()
            .filter { it.toString().endsWith(".kt") }
            .flatMap { file ->
                file.readText().lineSequence()
                    .filter { it.startsWith("import com.homeassistant.repository.") }
                    .filterNot { it in allowedRepositoryImports }
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
}
