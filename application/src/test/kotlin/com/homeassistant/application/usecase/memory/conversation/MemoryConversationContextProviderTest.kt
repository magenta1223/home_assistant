package com.homeassistant.application.usecase.memory.conversation

import com.homeassistant.application.port.input.topic.SearchTopics
import com.homeassistant.application.port.input.topic.TopicMatch
import com.homeassistant.application.port.input.topic.TopicMatchMemory
import com.homeassistant.domain.identity.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryConversationContextProviderTest {
    @Test
    fun `reference holds one block per topic with timestamps and score`() {
        val provider = provider(TopicMatch(7, 0.9, listOf(TopicMatchMemory(1, "saved fact", 1_000L), TopicMatchMemory(2, "older fact", 0L))),
            TopicMatch(9, 0.5, listOf(TopicMatchMemory(3, "other topic", 0L))))

        val context = provider.context(USER_ID, "question")

        assertTrue(context.hasMatches)
        assertEquals(
            """
            ## Topic 7 [score=0.9]
            - [savedAt=1970-01-01T00:00:01Z] saved fact
            - [savedAt=1970-01-01T00:00:00Z] older fact
            ## Topic 9 [score=0.5]
            - [savedAt=1970-01-01T00:00:00Z] other topic
            """.trimIndent(),
            context.reference,
        )
    }

    @Test
    fun `reference keeps whole lines within eight thousand characters and drops a header left alone`() {
        val long = TopicMatchMemory(1, "x".repeat(7_000), 0L)
        val context = provider(
            TopicMatch(1, 0.9, listOf(long, TopicMatchMemory(2, "y".repeat(2_000), 0L))),
            TopicMatch(2, 0.5, listOf(TopicMatchMemory(3, "short", 0L))),
        ).context(USER_ID, "question")

        assertEquals("## Topic 1 [score=0.9]\n- [savedAt=1970-01-01T00:00:00Z] ${long.content}", context.reference)
        assertTrue(context.hasMatches)
    }

    @Test
    fun `a match whose first memory exceeds the budget leaves no reference`() {
        val context = provider(TopicMatch(1, 0.9, listOf(TopicMatchMemory(1, "x".repeat(9_000), 0L)))).context(USER_ID, "question")

        assertFalse(context.hasMatches)
        assertEquals("", context.reference)
    }

    @Test
    fun `no readable topic produces no reference`() {
        val context = provider().context(USER_ID, "question")

        assertFalse(context.hasMatches)
        assertEquals("", context.reference)
    }

    private fun provider(vararg matches: TopicMatch) = MemoryConversationContextProvider(
        SearchTopics { userId, query ->
            assertEquals(USER_ID, userId)
            assertEquals("question", query)
            matches.toList()
        },
    )

    private companion object {
        val USER_ID = UserId("member-1")
    }
}
