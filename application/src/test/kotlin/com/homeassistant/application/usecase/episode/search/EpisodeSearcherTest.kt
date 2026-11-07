package com.homeassistant.application.usecase.episode.search

import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeRepository
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeBatch
import com.homeassistant.application.port.outbound.embedding.Embedding
import com.homeassistant.application.port.outbound.embedding.EpisodeEmbeddingKey
import com.homeassistant.application.port.outbound.embedding.EpisodeEmbeddingRepository
import com.homeassistant.application.port.outbound.embedding.EmbeddingGenerator
import com.homeassistant.application.port.outbound.embedding.EmbeddingSpace
import com.homeassistant.application.usecase.embedding.PrepareEpisodeEmbedding
import com.homeassistant.application.usecase.contextprovider.episode.EpisodeSearcher
import com.homeassistant.domain.episode.Episode
import com.homeassistant.domain.episode.EpisodeVisibility
import com.homeassistant.domain.embedding.EmbeddingVector
import com.homeassistant.domain.identity.UserAccessDeniedException
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId
import kotlin.test.*

class EpisodeSearcherTest {
    private val user = UserId("a")
    private val east = EmbeddingVector.normalized(listOf(1.0, 0.0))
    private val north = EmbeddingVector.normalized(listOf(0.0, 1.0))
    private val nearEast = EmbeddingVector.normalized(listOf(1.0, 0.2))

    @Test fun `readable episodes come nearest the question first, up to the limit`() {
        val fixture = Fixture(mapOf("north" to north, "east" to east, "near east" to nearEast))

        val matches = fixture.searcher(limit = 2).search(user, "  question  ")

        assertEquals(listOf("question"), fixture.queries)
        assertEquals(listOf(2, 3), matches.map { it.id })
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

    private inner class Fixture(private val vectors: Map<String, EmbeddingVector>) {
        val queries = mutableListOf<String>()
        val generated = mutableListOf<String>()
        private val stored = mutableMapOf<EpisodeEmbeddingKey, Embedding<EpisodeEmbeddingKey>>()
        private val episodes = vectors.keys.mapIndexed { index, content ->
            Episode(index + 1, "creator", "title", content, EpisodeVisibility.PUBLIC, emptySet(), listOf(1), (index + 1).toLong())
        }

        private val embeddings = object : EmbeddingGenerator {
            override val space = EmbeddingSpace("test", "passage:v1")
            override fun isAvailable() = true
            override fun embedDocument(text: String) = vectors.getValue(text).also { generated.add(text) }
            override fun embedQuery(text: String): EmbeddingVector { queries.add(text); return east }
        }

        fun searcher(limit: Int = 10, authorized: Boolean = true) = EpisodeSearcher(
            object : EpisodeRepository {
                override fun findVisibleTo(userId: UserId) = episodes
                override fun save(batch: EpisodeBatch) = error("search never saves episodes")
            },
            embeddings,
            PrepareEpisodeEmbedding(embeddings, object : EpisodeEmbeddingRepository {
                override fun find(key: EpisodeEmbeddingKey) = stored[key]
                override fun save(embedding: Embedding<EpisodeEmbeddingKey>) = stored.getOrPut(embedding.key) { embedding }
            }),
            UserAccessPolicy { authorized },
            limit,
        )
    }
}
