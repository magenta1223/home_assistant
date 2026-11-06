package com.homeassistant.application.usecase.contextprovider

import com.homeassistant.domain.identity.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextAssemblerTest {
    @Test
    fun `sections of every provider are joined in provider order`() {
        val context = ContextAssembler(listOf(
            provider(ContextSection("Component 7", listOf("saved fact", "older fact"))),
            provider(ContextSection("Balances", listOf("checking 100"))),
        )).context(USER_ID, "question")

        assertTrue(context.hasMatches)
        assertEquals(
            """
            ## Component 7
            - saved fact
            - older fact
            ## Balances
            - checking 100
            """.trimIndent(),
            context.reference,
        )
    }

    @Test
    fun `reference keeps whole lines within eight thousand characters and drops a title left alone`() {
        val long = "x".repeat(7_000)
        val context = ContextAssembler(listOf(provider(
            ContextSection("A", listOf(long, "y".repeat(2_000))),
            ContextSection("B", listOf("short")),
        ))).context(USER_ID, "question")

        assertEquals("## A\n- $long", context.reference)
        assertTrue(context.hasMatches)
    }

    @Test
    fun `a section whose first entry exceeds the budget leaves no reference`() {
        val context = ContextAssembler(listOf(provider(ContextSection("A", listOf("x".repeat(9_000))))))
            .context(USER_ID, "question")

        assertFalse(context.hasMatches)
        assertEquals("", context.reference)
    }

    @Test
    fun `no section produces no reference`() {
        val context = ContextAssembler(listOf(provider())).context(USER_ID, "question")

        assertFalse(context.hasMatches)
        assertEquals("", context.reference)
    }

    private fun provider(vararg sections: ContextSection) = ContextProvider { _, _ -> sections.toList() }

    private companion object {
        val USER_ID = UserId("member-1")
    }
}
