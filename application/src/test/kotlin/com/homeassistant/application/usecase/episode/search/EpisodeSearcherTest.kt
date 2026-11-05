package com.homeassistant.application.usecase.episode.search

import com.homeassistant.application.usecase.contextprovider.episode.EpisodeMatch
import com.homeassistant.application.port.outbound.memoryextraction.component.EpisodeEmbedding
import com.homeassistant.application.port.outbound.memoryextraction.component.EpisodeEmbeddingGenerator
import com.homeassistant.application.port.outbound.memoryextraction.component.EpisodeEmbeddingKey
import com.homeassistant.application.port.outbound.memoryextraction.component.EpisodeEmbeddingStore
import com.homeassistant.application.port.outbound.memoryextraction.component.QueryEmbeddingGenerator
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeReader
import com.homeassistant.application.port.outbound.memoryextraction.group.EmbeddingSpace
import com.homeassistant.application.usecase.memoryextraction.component.PrepareEpisodeEmbedding
import com.homeassistant.application.usecase.contextprovider.episode.EpisodeSearcher
import com.homeassistant.domain.episode.Episode
import com.homeassistant.domain.episode.EpisodeVisibility
import com.homeassistant.domain.group.ComponentVector
import com.homeassistant.domain.identity.UserAccessDeniedException
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId
import kotlin.test.*

class EpisodeSearcherTest {
    private val user = UserId("a")
    private val east = ComponentVector.normalized(listOf(1.0, 0.0))
    private val north = ComponentVector.normalized(listOf(0.0, 1.0))
    private val nearEast = ComponentVector.normalized(listOf(1.0, 0.2))

    @Test fun `readable episodes come nearest the question first, up to the limit`() {
        val fixture = Fixture(mapOf("north" to north, "east" to east, "near east" to nearEast))

        val matches = fixture.searcher(limit = 2).search(user, "  question  ")

        assertEquals(listOf("question"), fixture.queries)
        assertEquals(listOf(2, 3), matches.map(EpisodeMatch::episodeId))
        assertEquals(EpisodeMatch(2, 1.0, "east", 2L), matches.first())
    }

    @Test fun `an episode is embedded once and then read from the store`() {
        val fixture = Fixture(mapOf("east" to east))

        fixture.searcher().search(user, "q")
        fixture.searcher().search(user, "q")

        assertEquals(listOf("east"), fixture.generated)
    }

    @Test fun `no readable episode needs no embedding and an unauthorized user is rejected`() {
        val empty = Fixture(emptyMap())
        assertTrue(empty.searcher().search(user, "q").isEmpty())
        assertTrue(empty.queries.isEmpty())
        assertFailsWith<UserAccessDeniedException> { Fixture(mapOf("east" to east)).searcher(authorized = false).search(user, "q") }
    }

    private inner class Fixture(private val vectors: Map<String, ComponentVector>) {
        val queries = mutableListOf<String>()
        val generated = mutableListOf<String>()
        private val stored = mutableMapOf<EpisodeEmbeddingKey, EpisodeEmbedding>()
        private val episodes = vectors.keys.mapIndexed { index, content ->
            Episode(index + 1, "creator", "title", content, EpisodeVisibility.PUBLIC, emptySet(), listOf(1), (index + 1).toLong())
        }

        fun searcher(limit: Int = 10, authorized: Boolean = true) = EpisodeSearcher(
            object : EpisodeReader {
                override fun getEpisodes(userId: UserId) = episodes
            },
            QueryEmbeddingGenerator { queries.add(it); east },
            PrepareEpisodeEmbedding(object : EpisodeEmbeddingGenerator {
                override val space = EmbeddingSpace("test", "passage:v1")
                override fun isAvailable() = true
                override fun generate(content: String) = vectors.getValue(content).also { generated.add(content) }
            }, object : EpisodeEmbeddingStore {
                override fun find(key: EpisodeEmbeddingKey) = stored[key]
                override fun save(embedding: EpisodeEmbedding) = stored.getOrPut(embedding.key) { embedding }
            }),
            UserAccessPolicy { authorized },
            limit,
        )
    }
}
