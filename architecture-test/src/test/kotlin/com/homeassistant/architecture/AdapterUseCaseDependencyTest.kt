package com.homeassistant.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import kotlin.test.Test

/** Adapters reach use cases only through ports, in the application and in every feature module. */
class AdapterUseCaseDependencyTest {
    @Test
    fun `adapters do not import use case implementations`() {
        ADAPTER_MODULES
            .map { Konsist.scopeFromProduction(moduleName = it) }
            .flatMap { it.files }
            .assertTrue { file -> file.imports.none { USE_CASE_PACKAGE.matches(it.name) } }
    }

    private companion object {
        val ADAPTER_MODULES = listOf("adapter-inbound", "adapter-outbound")
        val USE_CASE_PACKAGE = Regex("""com\.homeassistant\.(application|feature\.[\w.]+)\.usecase\..+""")
    }
}
