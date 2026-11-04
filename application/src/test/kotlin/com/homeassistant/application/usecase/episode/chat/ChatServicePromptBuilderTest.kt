package com.homeassistant.application.usecase.episode.chat

import com.homeassistant.application.port.inbound.component.ComponentMatch
import com.homeassistant.application.port.inbound.component.ComponentMatchEpisode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class ChatServicePromptBuilderTest {
    @Test
    fun `reference exposes storage order as ISO time without treating it as event time`() {
        val older = episodeMatch(1, "2026-01-01T00:00:00Z")
        val newer = episodeMatch(2, "2026-08-01T12:30:00Z")
        val reference = componentReferenceLines(ComponentMatch(1, 0.9, listOf(older, newer))).joinToString("\n")
        val prompt = ChatPromptBuilder.build(reference, "Which one is current?")

        assertTrue(reference.indexOf("2026-01-01T00:00:00Z") < reference.indexOf("2026-08-01T12:30:00Z"))
        assertContains(prompt, "savedAt is when it was stored, not necessarily when its event happened")
        assertContains(prompt, "instead of claiming that one is the latest")
        assertContains(prompt, "<USER_MESSAGE>")
    }

    private fun episodeMatch(id: Int, createdAt: String) =
        ComponentMatchEpisode(episodeId = id, content = "episode-$id", createdAt = Instant.parse(createdAt).toEpochMilli())
}
