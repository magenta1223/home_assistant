package com.homeassistant.application.usecase.memory.conversation

import com.homeassistant.application.port.input.memory.search.MemorySearch
import com.homeassistant.application.port.input.memory.search.MemorySearchMatch
import com.homeassistant.application.port.input.memory.search.SearchMemoriesResult
import com.homeassistant.domain.identity.UserId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryConversationContextProviderTest {
    @Test
    fun `reference retains timestamp score and existing request budget`() {
        val provider = provider("saved fact")

        val context = provider.context(USER_ID, "question")

        assertTrue(context.hasMatches)
        assertContains(context.reference, "saved fact")
        assertContains(context.reference, "savedAt=1970-01-01T00:00:01Z")
        assertContains(context.reference, "score=0.9")
    }

    @Test
    fun `reference retains the eight thousand character limit`() {
        val context = provider("x".repeat(9_000)).context(USER_ID, "question")

        assertEquals(8_000, context.reference.length)
        assertTrue(context.hasMatches)
    }

    @Test
    fun `empty direct search produces no reference`() {
        val provider = MemoryConversationContextProvider(
            MemorySearch { SearchMemoriesResult(it.query, emptyList()) },
        )

        val context = provider.context(USER_ID, "question")

        assertFalse(context.hasMatches)
        assertEquals("", context.reference)
    }

    private fun provider(content: String) = MemoryConversationContextProvider(
        MemorySearch { request ->
            assertEquals(USER_ID.value, request.userId)
            assertEquals("question", request.query)
            assertEquals(5, request.limit)
            SearchMemoriesResult(request.query, listOf(MemorySearchMatch(1, content, listOf(1), 0.9, 1_000L)))
        },
    )

    private companion object {
        val USER_ID = UserId("member-1")
    }
}
