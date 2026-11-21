package com.homeassistant.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import kotlin.test.Test

/** Outbound adapters implement the outbound ports of feature modules and use nothing else of them. */
class OutboundAdapterDependencyTest {
    @Test
    fun `outbound adapters import from feature modules only their outbound ports`() {
        Konsist.scopeFromProduction(moduleName = "adapter-outbound")
            .files
            .assertTrue { file ->
                file.imports
                    .filter { it.name.startsWith(FEATURE_PACKAGE) }
                    .all { it.name.contains(OUTBOUND_PORT_PACKAGE) }
            }
    }

    private companion object {
        const val FEATURE_PACKAGE = "com.homeassistant.feature."
        const val OUTBOUND_PORT_PACKAGE = ".port.outbound."
    }
}
