package com.homeassistant.application.usecase.contexttool

import com.homeassistant.application.port.inbound.contexttool.ContextAccessExpiredException
import com.homeassistant.application.usecase.contextprovider.ChatContext
import com.homeassistant.application.usecase.contextprovider.ContextSource
import com.homeassistant.domain.identity.UserId
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SearchMemoryServiceTest {
    @Test
    fun `a search reads the memory of the user the token was issued for`() {
        val grants = ContextGrants(LIFETIME)
        val memory = PerUserMemory(mapOf(MEMBER to "## Episodes\n- 현관 비밀번호는 매달 바뀐다"))
        val token = grants.issue(MEMBER)

        val reference = SearchMemoryService(grants, memory).search(token, "현관")

        assertEquals("## Episodes\n- 현관 비밀번호는 매달 바뀐다", reference)
        assertEquals(listOf(MEMBER to "현관"), memory.searches)
    }

    @Test
    fun `a search with no match says so`() {
        val grants = ContextGrants(LIFETIME)

        val reference = SearchMemoryService(grants, PerUserMemory(emptyMap())).search(grants.issue(MEMBER), "현관")

        assertEquals(SearchMemoryService.NO_MATCH, reference)
    }

    @Test
    fun `a revoked token reads nothing`() {
        val grants = ContextGrants(LIFETIME)
        val memory = PerUserMemory(mapOf(MEMBER to "secret"))
        val token = grants.issue(MEMBER).also(grants::revoke)

        assertFailsWith<ContextAccessExpiredException> { SearchMemoryService(grants, memory).search(token, "현관") }
        assertEquals(emptyList(), memory.searches)
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
        val LIFETIME: Duration = Duration.ofMinutes(10)
    }
}
