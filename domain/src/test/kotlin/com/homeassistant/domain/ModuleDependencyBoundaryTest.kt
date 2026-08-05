package com.homeassistant.domain

import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.test.Test
import kotlin.test.assertFalse

class ModuleDependencyBoundaryTest {
    private val projectRoot = generateSequence(java.nio.file.Path.of("").toAbsolutePath()) { it.parent }
        .first { it.resolve("settings.gradle.kts").toFile().exists() }

    @Test
    fun `domain does not depend on nlp`() {
        val domainBuild = projectRoot.resolve("domain/build.gradle.kts").readText()
        assertFalse(
            domainBuild.contains("project(\":nlp\")"),
            "domain module must not declare a project dependency on nlp",
        )
        assertFalse(
            domainBuild.contains("project(\":adapter\")") || domainBuild.contains("project(\":application\")"),
            "domain module must not declare a project dependency on application or adapter",
        )
    }

    @Test
    fun `domain source does not import framework or outer packages`() {
        val domainSource = projectRoot.resolve("domain/src/main/kotlin")
        val offendingImports = domainSource.walk()
            .filter { it.toString().endsWith(".kt") }
            .flatMap { file ->
                file.readText().lineSequence()
                    .filter {
                        it.startsWith("import com.homeassistant.nlp.") ||
                            it.startsWith("import com.homeassistant.application.") ||
                            it.startsWith("import com.homeassistant.adapter.") ||
                            it.startsWith("import com.homeassistant.app.") ||
                            it.startsWith("import org.jetbrains.exposed.")
                    }
                    .map { "${projectRoot.relativize(file)}: $it" }
            }
            .toList()

        assertFalse(
            offendingImports.isNotEmpty(),
            "domain source must not import application, adapter, nlp, app, or Exposed packages:\n${offendingImports.joinToString("\n")}",
        )
    }
}
