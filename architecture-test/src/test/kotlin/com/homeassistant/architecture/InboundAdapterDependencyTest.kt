package com.homeassistant.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import kotlin.test.Test

/** Inbound adapters drive the application only through its inbound ports. */
class InboundAdapterDependencyTest {
    @Test
    fun `inbound adapters import from the application only its inbound ports`() {
        Konsist.scopeFromProduction(moduleName = "adapter-inbound")
            .files
            .assertTrue { file ->
                file.imports
                    .filter { it.name.startsWith(APPLICATION_PACKAGE) }
                    .all { it.name.startsWith(APPLICATION_INBOUND_PORT_PACKAGE) }
            }
    }

    /**
     * A scheduler worker only times calls to inbound ports, so besides those it uses the project runtime lifecycle and
     * the standard, logging and coroutine libraries.
     */
    @Test
    fun `scheduler workers depend only on inbound ports, the runtime lifecycle, and standard libraries`() {
        Konsist.scopeFromPackage("..adapter.inbound.scheduler..", moduleName = "adapter-inbound", sourceSetName = "main")
            .files
            .assertTrue { file ->
                file.imports.all { import -> SCHEDULER_ALLOWED_PACKAGES.any { import.name.startsWith(it) } }
            }
    }

    private companion object {
        const val APPLICATION_PACKAGE = "com.homeassistant.application."
        const val APPLICATION_INBOUND_PORT_PACKAGE = "com.homeassistant.application.port.inbound."
        val SCHEDULER_ALLOWED_PACKAGES = listOf(
            APPLICATION_INBOUND_PORT_PACKAGE,
            "com.homeassistant.infrastructure.runtime.",
            "java.",
            "kotlin.",
            "kotlinx.coroutines.",
            "org.slf4j.",
        )
    }
}
