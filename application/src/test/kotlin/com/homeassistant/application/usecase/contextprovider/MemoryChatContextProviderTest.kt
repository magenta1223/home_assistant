package com.homeassistant.application.usecase.contextprovider

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
import kotlin.test.assertTrue

class MemoryChatContextProviderTest {
    @Test
    fun `component statements come before the episodes with their saved time`() {
        val sections = provider(
            components = listOf(component(7, "likes tea"), component(9, "walks the dog daily")),
            episodes = listOf(episode(1, "bought tea", 1_000L), episode(4, "cake pickup", 2_000L)),
        ).sections(USER_ID, "question")

        assertEquals(
            listOf(
                ContextSection("Components", listOf("likes tea", "walks the dog daily")),
                ContextSection(
                    "Episodes",
                    listOf("[savedAt=1970-01-01T00:00:01Z] bought tea", "[savedAt=1970-01-01T00:00:02Z] cake pickup"),
                ),
            ),
            sections,
        )
    }

    @Test
    fun `a search that finds nothing leaves out its section`() {
        val sections = provider(episodes = listOf(episode(4, "cake pickup", 0L))).sections(USER_ID, "question")

        assertEquals(listOf(ContextSection("Episodes", listOf("[savedAt=1970-01-01T00:00:00Z] cake pickup"))), sections)
    }

    @Test
    fun `no readable component or episode produces no section`() {
        assertTrue(provider().sections(USER_ID, "question").isEmpty())
    }

    private fun provider(components: List<Component> = emptyList(), episodes: List<Episode> = emptyList()): MemoryContextProvider {
        val componentSearcher = mockk<ComponentSearcher>()
        every { componentSearcher.search(USER_ID, "question") } returns components
        val episodeSearcher = mockk<EpisodeSearcher>()
        every { episodeSearcher.search(USER_ID, "question") } returns episodes
        return MemoryContextProvider(componentSearcher, episodeSearcher)
    }

    private fun component(id: Int, statement: String) = Component(id, statement, id, EpisodeAccess.PUBLIC)

    private fun episode(id: Int, content: String, createdAt: Long) =
        Episode(id, "creator", "title $id", content, EpisodeVisibility.PUBLIC, emptySet(), listOf(1), createdAt)

    private companion object {
        val USER_ID = UserId("member-1")
    }
}
