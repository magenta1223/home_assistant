package com.homeassistant.application.usecase.contexttool

import com.homeassistant.application.usecase.contextprovider.ChatContext
import com.homeassistant.application.usecase.contextprovider.ContextSource
import com.homeassistant.domain.identity.UserId
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchMemoryServiceTest {
    @Test
    fun `a search reads the user's memory as a reference block`() {
        val memory = PerUserMemory(mapOf(MEMBER to "## Episodes\n- 현관 비밀번호는 매달 바뀐다"))

        val reference = SearchMemoryService(memory).search(MEMBER, "현관")

        assertEquals("## Episodes\n- 현관 비밀번호는 매달 바뀐다", reference)
        assertEquals(listOf(MEMBER to "현관"), memory.searches)
    }

    @Test
    fun `a search with no match says so`() {
        val reference = SearchMemoryService(PerUserMemory(emptyMap())).search(MEMBER, "현관")

        assertEquals(SearchMemoryService.NO_MATCH, reference)
    }

    private class PerUserMemory(private val references: Map<UserId, String>) : ContextSource {
        val searches = mutableListOf<Pair<UserId, String>>()

        override fun context(userId: UserId, question: String): ChatContext {
            searches += userId to question
            val reference = references[userId].orEmpty()
            return ChatContext(reference, reference.isNotEmpty())
        }
    }

    private companion object {
        val MEMBER = UserId("member-1")
    }
}
