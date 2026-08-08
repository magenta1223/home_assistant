package com.homeassistant.application.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplicationPackageArchitectureTest {
    private val repositoryRoot: Path = findRepositoryRoot()
    private val applicationSourceRoot = repositoryRoot.resolve("application/src/main/kotlin")

    @Test
    fun `application sources live in an explicit input output or usecase package`() {
        kotlinSources(applicationSourceRoot).forEach { source ->
            val packageName = packageName(source)
            val applicationSuffix = packageName.removePrefix(APPLICATION_PACKAGE_PREFIX)
            assertTrue(
                applicationSuffix.startsWith("port.input.") ||
                    applicationSuffix.startsWith("port.output.") ||
                    applicationSuffix.startsWith("usecase."),
                "$source is not assigned to an explicit application layer",
            )

            val expectedPackage = applicationSourceRoot
                .relativize(source.parent)
                .joinToString(".") { it.name }
            assertEquals(expectedPackage, packageName, "$source package does not match its directory")
        }
    }

    @Test
    fun `adapters do not import usecase implementations`() {
        listOf("adapter-inbound", "adapter-outbound").forEach { module ->
            kotlinSources(repositoryRoot.resolve("$module/src/main/kotlin")).forEach { source ->
                assertFalse(
                    Files.readString(source).contains("import com.homeassistant.application.usecase."),
                    "$source imports an application usecase implementation",
                )
            }
        }
    }

    @Test
    fun `output ports do not expose implementation technology names`() {
        val outputPorts = applicationSourceRoot.resolve("com/homeassistant/application/port/output")
        val implementationNames = Regex("\\b(qdrant|sqlite|codex|ollama)\\b", RegexOption.IGNORE_CASE)

        kotlinSources(outputPorts).forEach { source ->
            assertFalse(
                implementationNames.containsMatchIn(Files.readString(source)),
                "$source exposes an implementation technology in an output port",
            )
        }
    }

    private fun kotlinSources(root: Path): List<Path> {
        if (!root.isDirectory()) return emptyList()
        return Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .toList()
        }
    }

    private fun packageName(source: Path): String = Files.readAllLines(source)
        .first { it.startsWith("package ") }
        .removePrefix("package ")
        .trim()

    private fun findRepositoryRoot(): Path {
        var current: Path? = Path.of("").toAbsolutePath().normalize()
        while (current != null) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) return current
            current = current.parent
        }
        error("Repository root not found")
    }

    private companion object {
        const val APPLICATION_PACKAGE_PREFIX = "com.homeassistant.application."
    }
}
