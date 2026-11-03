package com.homeassistant.application.usecase.episode.conversation

import com.homeassistant.application.port.inbound.component.ComponentMatch
import com.homeassistant.application.port.inbound.component.ComponentMatchEpisode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class ConversationPromptBuilderTest {
    @Test
    fun `reference exposes storage order as ISO time without treating it as event time`() {
        val older = episodeMatch(1, "2026-01-01T00:00:00Z")
        val newer = episodeMatch(2, "2026-08-01T12:30:00Z")
        val reference = componentReferenceLines(ComponentMatch(1, 0.9, listOf(older, newer))).joinToString("\n")
        val prompt = EpisodeConversationPromptBuilder(
            Clock.fixed(Instant.parse("2026-08-08T10:00:00Z"), ZoneOffset.UTC),
        ).build(reference, "Which one is current?")

        assertTrue(reference.indexOf("2026-01-01T00:00:00Z") < reference.indexOf("2026-08-01T12:30:00Z"))
        assertContains(prompt, "Current time is 2026-08-08T10:00:00Z")
        assertContains(prompt, "savedAt is when it was stored, not necessarily when its event happened")
        assertContains(prompt, "instead of claiming that one is the latest")
        assertContains(prompt, "<USER_MESSAGE>")
    }

    private fun episodeMatch(id: Int, createdAt: String) =
        ComponentMatchEpisode(episodeId = id, content = "episode-$id", createdAt = Instant.parse(createdAt).toEpochMilli())
}
