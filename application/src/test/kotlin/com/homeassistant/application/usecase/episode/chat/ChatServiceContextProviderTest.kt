package com.homeassistant.application.usecase.episode.chat

import com.homeassistant.application.usecase.contextprovider.ContextProvider
import com.homeassistant.application.usecase.contextprovider.component.ComponentSearcher
import com.homeassistant.application.usecase.contextprovider.episode.EpisodeSearcher
import com.homeassistant.domain.component.Component
import com.homeassistant.domain.episode.Episode
import com.homeassistant.domain.episode.EpisodeAccess
import com.homeassistant.domain.episode.EpisodeVisibility
import com.homeassistant.domain.identity.UserId
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatServiceContextProviderTest {
    @Test
    fun `reference holds the component statements then the episodes with their saved time`() {
        val context = provider(
            components = listOf(component(7, "likes tea"), component(9, "walks the dog daily")),
            episodes = listOf(episode(1, "bought tea", 1_000L), episode(4, "cake pickup", 2_000L)),
        ).context(USER_ID, "question")

        assertTrue(context.hasMatches)
        assertEquals(
            """
            ## Components
            - likes tea
            - walks the dog daily
            ## Episodes
            - [savedAt=1970-01-01T00:00:01Z] bought tea
            - [savedAt=1970-01-01T00:00:02Z] cake pickup
            """.trimIndent(),
            context.reference,
        )
    }

    @Test
    fun `reference keeps whole lines within eight thousand characters and drops a header left alone`() {
        val long = "x".repeat(7_000)
        val context = provider(
            components = listOf(component(1, long), component(2, "y".repeat(2_000))),
            episodes = listOf(episode(3, "short", 0L)),
        ).context(USER_ID, "question")

        assertEquals("## Components\n- $long", context.reference)
        assertTrue(context.hasMatches)
    }

    @Test
    fun `a first line that exceeds the budget leaves no reference`() {
        val context = provider(components = listOf(component(1, "x".repeat(9_000)))).context(USER_ID, "question")

        assertFalse(context.hasMatches)
        assertEquals("", context.reference)
    }

    @Test
    fun `episodes alone make a reference when no component matches`() {
        val context = provider(episodes = listOf(episode(4, "cake pickup", 0L))).context(USER_ID, "question")

        assertTrue(context.hasMatches)
        assertEquals("## Episodes\n- [savedAt=1970-01-01T00:00:00Z] cake pickup", context.reference)
    }

    @Test
    fun `no readable component or episode produces no reference`() {
        val context = provider().context(USER_ID, "question")

        assertFalse(context.hasMatches)
        assertEquals("", context.reference)
    }

    private fun provider(components: List<Component> = emptyList(), episodes: List<Episode> = emptyList()): ContextProvider {
        val componentSearcher = mockk<ComponentSearcher>()
        every { componentSearcher.search(USER_ID, "question") } returns components
        val episodeSearcher = mockk<EpisodeSearcher>()
        every { episodeSearcher.search(USER_ID, "question") } returns episodes
        return ContextProvider(componentSearcher, episodeSearcher)
    }

    private fun component(id: Int, statement: String) = Component(id, statement, id, EpisodeAccess.PUBLIC)

    private fun episode(id: Int, content: String, createdAt: Long) =
        Episode(id, "creator", "title $id", content, EpisodeVisibility.PUBLIC, emptySet(), listOf(1), createdAt)

    private companion object {
        val USER_ID = UserId("member-1")
    }
}
