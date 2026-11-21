package com.homeassistant.feature.chat.usecase

import com.homeassistant.application.port.outbound.context.ContextProvider
import com.homeassistant.application.port.outbound.context.ContextSection
import com.homeassistant.domain.identity.UserId
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchMemoryServiceTest {
    @Test
    fun `a search reads the user's memory as a reference block`() {
        val memory = PerUserMemory(mapOf(MEMBER to ContextSection("Episodes", listOf("현관 비밀번호는 매달 바뀐다"))))

        val reference = SearchMemoryService(ContextAssembler(listOf(memory))).search(MEMBER, "현관")

        assertEquals("## Episodes\n- 현관 비밀번호는 매달 바뀐다", reference)
        assertEquals(listOf(MEMBER to "현관"), memory.searches)
    }

    @Test
    fun `a search with no match says so`() {
        val reference = SearchMemoryService(ContextAssembler(listOf(PerUserMemory(emptyMap())))).search(MEMBER, "현관")

        assertEquals(SearchMemoryService.NO_MATCH, reference)
    }

    private class PerUserMemory(private val sections: Map<UserId, ContextSection>) : ContextProvider {
        val searches = mutableListOf<Pair<UserId, String>>()

        override fun sections(userId: UserId, question: String): List<ContextSection> {
            searches += userId to question
            return listOfNotNull(sections[userId])
        }
    }

    private companion object {
        val MEMBER = UserId("member-1")
    }
}
