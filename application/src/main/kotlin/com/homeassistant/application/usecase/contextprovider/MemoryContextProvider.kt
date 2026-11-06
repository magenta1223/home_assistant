package com.homeassistant.application.usecase.contextprovider

import com.homeassistant.application.usecase.contextprovider.component.ComponentSearcher
import com.homeassistant.application.usecase.contextprovider.episode.EpisodeSearcher
import com.homeassistant.domain.component.Component
import com.homeassistant.domain.episode.Episode
import com.homeassistant.domain.identity.UserId
import java.time.Instant

/**
 * Memory as xMemory gives it to the reader: the statements of the matched Components, then the nearest episodes with the
 * time each was saved. A search that finds nothing leaves out its section.
 */
class MemoryContextProvider(
    private val componentSearch: ComponentSearcher,
    private val episodeSearch: EpisodeSearcher,
) : ContextProvider {
    override fun sections(userId: UserId, question: String): List<ContextSection> =
        listOfNotNull(
            componentSection(componentSearch.search(userId, question)),
            episodeSection(episodeSearch.search(userId, question)),
        )

    private fun componentSection(components: List<Component>): ContextSection? =
        if (components.isEmpty()) null else ContextSection("Components", components.map { it.statement })

    private fun episodeSection(episodes: List<Episode>): ContextSection? =
        if (episodes.isEmpty()) null
        else ContextSection("Episodes", episodes.map { "[savedAt=${Instant.ofEpochMilli(it.createdAt)}] ${it.content}" })
}
