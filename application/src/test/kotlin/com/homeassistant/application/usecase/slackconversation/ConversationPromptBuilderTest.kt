package com.homeassistant.application.usecase.slackconversation

import com.homeassistant.application.port.input.memory.search.MemorySearchMatch
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class ConversationPromptBuilderTest {
    @Test
    fun `reference exposes storage order as ISO time without treating it as event time`() {
        val older = memoryMatch(1, "2026-01-01T00:00:00Z")
        val newer = memoryMatch(2, "2026-08-01T12:30:00Z")
        val reference = listOf(older, newer).joinToString("\n", transform = ::memoryReferenceLine)
        val prompt = ConversationPromptBuilder(
            Clock.fixed(Instant.parse("2026-08-08T10:00:00Z"), ZoneOffset.UTC),
        ).build(reference, "Which one is current?")

        assertTrue(reference.indexOf("2026-01-01T00:00:00Z") < reference.indexOf("2026-08-01T12:30:00Z"))
        assertContains(prompt, "Current time is 2026-08-08T10:00:00Z")
        assertContains(prompt, "savedAt is when it was stored, not necessarily when its event happened")
        assertContains(prompt, "instead of claiming that one is the latest")
    }

    private fun memoryMatch(id: Int, createdAt: String) = MemorySearchMatch(
        memoryId = id,
        content = "memory-$id",
        evidenceRefs = listOf(id),
        score = 0.9,
        createdAt = Instant.parse(createdAt).toEpochMilli(),
    )
}
