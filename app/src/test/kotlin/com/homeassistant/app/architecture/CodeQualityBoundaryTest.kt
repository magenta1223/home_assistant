package com.homeassistant.app.architecture

import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.test.Test
import kotlin.test.assertTrue

class CodeQualityBoundaryTest {
    private val projectRoot = generateSequence(java.nio.file.Path.of("").toAbsolutePath()) { it.parent }
        .first { it.resolve("settings.gradle.kts").toFile().exists() }

    @Test
    fun `Kotlin source files do not exceed three hundred lines`() {
        val violations = kotlinSources()
            .filter { path -> path.toString().endsWith(".kt") }
            .map { path -> projectRoot.relativize(path) to path.readLines().size }
            .filter { (_, lines) -> lines > MAX_LINES }
            .sortedByDescending { (_, lines) -> lines }
            .toList()

        assertTrue(
            violations.isEmpty(),
            "Kotlin files must be at most $MAX_LINES lines:\n" +
                violations.joinToString("\n") { (path, lines) -> "$lines $path" },
        )
    }

    @Test
    fun `domain model names do not expose persistence row terminology`() {
        val domainSource = projectRoot.resolve("domain/src/main/kotlin")
        val violations = domainSource.walk()
            .filter { path -> path.toString().endsWith(".kt") }
            .flatMap { path ->
                ROW_MODEL.findAll(path.readText())
                    .map { match -> projectRoot.relativize(path) to match.groupValues[1] }
            }
            .toList()

        assertTrue(
            violations.isEmpty(),
            "Domain model names must describe domain concepts, not database rows:\n" +
                violations.joinToString("\n") { (path, name) -> "$path: $name" },
        )
    }

    private fun kotlinSources(mainOnly: Boolean = false) =
        MODULES.asSequence()
            .flatMap { module ->
                val sourceRoot = projectRoot.resolve(module).resolve("src")
                if (mainOnly) sourceRoot.resolve("main").walk() else sourceRoot.walk()
            }
            .filter { path -> path.toString().endsWith(".kt") }

    private companion object {
        const val MAX_LINES = 300
        val MODULES = listOf("domain", "application", "adapter", "app")
        val ROW_MODEL =
            Regex("""(?m)^(?:data\s+)?class\s+([A-Za-z_][A-Za-z0-9_]*Row)\b""")
    }
}
