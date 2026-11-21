package com.homeassistant.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import kotlin.test.Test

/** Outbound ports, in the application and in every feature module, speak the application's language. */
class OutboundPortConventionTest {
    @Test
    fun `outbound ports do not expose implementation technology names`() {
        outboundPorts().files.assertFalse { IMPLEMENTATION_NAMES.containsMatchIn(it.text) }
    }

    @Test
    fun `repository ports read with find and write with save or delete`() {
        outboundPorts()
            .interfaces()
            .filter { it.name.endsWith("Repository") }
            .assertTrue { port ->
                port.functions().all { function -> REPOSITORY_FUNCTION_PREFIXES.any(function.name::startsWith) }
            }
    }

    /** The outbound port declarations of the application and of the feature modules. */
    private fun outboundPorts(): KoScope =
        Konsist.scopeFromProduction().slice { file ->
            OUTBOUND_PORT_PACKAGES.any(file::hasPackage)
        }

    private companion object {
        val OUTBOUND_PORT_PACKAGES = listOf(
            "com.homeassistant.application.port.outbound..",
            "com.homeassistant.feature..port.outbound..",
        )
        val IMPLEMENTATION_NAMES = Regex("\\b(qdrant|sqlite|codex|ollama)\\b", RegexOption.IGNORE_CASE)
        val REPOSITORY_FUNCTION_PREFIXES = listOf("find", "save", "delete")
    }
}
