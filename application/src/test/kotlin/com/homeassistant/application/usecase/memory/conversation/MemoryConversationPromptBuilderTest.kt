package com.homeassistant.application.usecase.memory.conversation

import com.homeassistant.application.port.input.topic.TopicMatch
import com.homeassistant.application.port.input.topic.TopicMatchMemory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class MemoryConversationPromptBuilderTest {
    @Test
    fun `reference exposes storage order as ISO time without treating it as event time`() {
        val older = memoryMatch(1, "2026-01-01T00:00:00Z")
        val newer = memoryMatch(2, "2026-08-01T12:30:00Z")
        val reference = topicReferenceLines(TopicMatch(1, 0.9, listOf(older, newer))).joinToString("\n")
        val prompt = MemoryConversationPromptBuilder(
            Clock.fixed(Instant.parse("2026-08-08T10:00:00Z"), ZoneOffset.UTC),
        ).build(reference, "Which one is current?")

        assertTrue(reference.indexOf("2026-01-01T00:00:00Z") < reference.indexOf("2026-08-01T12:30:00Z"))
        assertContains(prompt, "Current time is 2026-08-08T10:00:00Z")
        assertContains(prompt, "savedAt is when it was stored, not necessarily when its event happened")
        assertContains(prompt, "instead of claiming that one is the latest")
        assertContains(prompt, "<USER_MESSAGE>")
    }

    private fun memoryMatch(id: Int, createdAt: String) =
        TopicMatchMemory(memoryId = id, content = "memory-$id", createdAt = Instant.parse(createdAt).toEpochMilli())
}
