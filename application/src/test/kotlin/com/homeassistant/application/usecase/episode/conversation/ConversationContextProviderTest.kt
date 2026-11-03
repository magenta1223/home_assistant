package com.homeassistant.application.usecase.episode.conversation

import com.homeassistant.application.port.inbound.component.SearchComponents
import com.homeassistant.application.port.inbound.component.ComponentMatch
import com.homeassistant.application.port.inbound.component.ComponentMatchEpisode
import com.homeassistant.application.port.inbound.episode.EpisodeMatch
import com.homeassistant.application.port.inbound.episode.SearchEpisodes
import com.homeassistant.domain.identity.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationContextProviderTest {
    @Test
    fun `reference holds one block per component with timestamps and score`() {
        val provider = provider(ComponentMatch(7, 0.9, listOf(ComponentMatchEpisode(1, "saved fact", 1_000L), ComponentMatchEpisode(2, "older fact", 0L))),
            ComponentMatch(9, 0.5, listOf(ComponentMatchEpisode(3, "other component", 0L))))

        val context = provider.context(USER_ID, "question")

        assertTrue(context.hasMatches)
        assertEquals(
            """
            ## Component 7 [score=0.9]
            - [savedAt=1970-01-01T00:00:01Z] saved fact
            - [savedAt=1970-01-01T00:00:00Z] older fact
            ## Component 9 [score=0.5]
            - [savedAt=1970-01-01T00:00:00Z] other component
            """.trimIndent(),
            context.reference,
        )
    }

    @Test
    fun `reference keeps whole lines within eight thousand characters and drops a header left alone`() {
        val long = ComponentMatchEpisode(1, "x".repeat(7_000), 0L)
        val context = provider(
            ComponentMatch(1, 0.9, listOf(long, ComponentMatchEpisode(2, "y".repeat(2_000), 0L))),
            ComponentMatch(2, 0.5, listOf(ComponentMatchEpisode(3, "short", 0L))),
        ).context(USER_ID, "question")

        assertEquals("## Component 1 [score=0.9]\n- [savedAt=1970-01-01T00:00:00Z] ${long.content}", context.reference)
        assertTrue(context.hasMatches)
    }

    @Test
    fun `a match whose first episode exceeds the budget leaves no reference`() {
        val context = provider(ComponentMatch(1, 0.9, listOf(ComponentMatchEpisode(1, "x".repeat(9_000), 0L)))).context(USER_ID, "question")

        assertFalse(context.hasMatches)
        assertEquals("", context.reference)
    }

    @Test
    fun `nearest episodes not already under a component follow the component blocks`() {
        val context = provider(
            ComponentMatch(7, 0.9, listOf(ComponentMatchEpisode(1, "saved fact", 0L))),
            episodes = listOf(EpisodeMatch(1, 0.8, "saved fact", 0L), EpisodeMatch(4, 0.7, "cake pickup", 2_000L)),
        ).context(USER_ID, "question")

        assertEquals(
            "## Component 7 [score=0.9]\n- [savedAt=1970-01-01T00:00:00Z] saved fact\n## Episodes\n- [savedAt=1970-01-01T00:00:02Z] cake pickup",
            context.reference,
        )
    }

    @Test
    fun `episodes alone make a reference when no component matches`() {
        val context = provider(episodes = listOf(EpisodeMatch(4, 0.7, "cake pickup", 0L))).context(USER_ID, "question")

        assertTrue(context.hasMatches)
        assertEquals("## Episodes\n- [savedAt=1970-01-01T00:00:00Z] cake pickup", context.reference)
    }

    @Test
    fun `no readable component or episode produces no reference`() {
        val context = provider().context(USER_ID, "question")

        assertFalse(context.hasMatches)
        assertEquals("", context.reference)
    }

    private fun provider(vararg matches: ComponentMatch, episodes: List<EpisodeMatch> = emptyList()) = EpisodeConversationContextProvider(
        SearchComponents { userId, query ->
            assertEquals(USER_ID, userId)
            assertEquals("question", query)
            matches.toList()
        },
        SearchEpisodes { userId, query ->
            assertEquals(USER_ID, userId)
            assertEquals("question", query)
            episodes
        },
    )

    private companion object {
        val USER_ID = UserId("member-1")
    }
}
