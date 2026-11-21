package com.homeassistant.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import kotlin.test.Test

/** The application module holds only port contracts; use cases that implement them live in feature modules. */
class ApplicationPortContractTest {
    /**
     * No application class or object implements an inbound port. Sealed interfaces are closed result types rather
     * than ports, so their subtypes are not implementations.
     */
    @Test
    fun `application declares inbound ports without implementing them`() {
        val application = Konsist.scopeFromProduction(moduleName = "application")

        (application.classes() + application.objects()).assertFalse { declaration ->
            declaration.hasParent { parent ->
                parent.sourceDeclaration?.hasInterfaceDeclaration { port ->
                    port.resideInPackage("..port.inbound..") && !port.hasSealedModifier
                } == true
            }
        }
    }

    /**
     * An application interface that is not an inbound port is an outbound port, so it is declared in
     * `..port.outbound..`. Sealed interfaces are closed result types rather than ports and are not checked.
     */
    @Test
    fun `application interfaces outside the inbound port package reside in the outbound port package`() {
        Konsist.scopeFromProduction(moduleName = "application")
            .interfaces()
            .filterNot { it.hasSealedModifier }
            .filterNot { it.resideInPackage("..port.inbound..") }
            .assertTrue { it.resideInPackage("..port.outbound..") }
    }
}
