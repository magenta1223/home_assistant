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
        val actual = listOf("core", "domain", "nlp", "app").associateWith { module ->
            projectDependencies(module)
        }

        assertEquals(emptySet(), actual.getValue("core"))
        assertEquals(setOf("core"), actual.getValue("domain"))
        assertEquals(setOf("core", "domain"), actual.getValue("nlp"))
        assertEquals(setOf("core", "domain", "nlp"), actual.getValue("app"))
    }

    @Test
    fun `source imports follow module direction`() {
        val violations = listOf("core", "domain", "nlp", "app").flatMap { module ->
            forbiddenImports(module).map { forbidden ->
                importsFor(module, forbidden)
            }.flatten()
        }

        assertTrue(
            violations.isEmpty(),
            "Module imports violate dependency direction:\n${violations.joinToString("\n")}",
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
            "core" -> setOf("domain", "nlp", "app")
            "domain" -> setOf("nlp", "app")
            "nlp" -> setOf("app")
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
}
