package com.homeassistant.application.usecase.component

import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentRepository
import com.homeassistant.application.port.outbound.memoryextraction.component.EpisodeComponents
import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentExtractor
import com.homeassistant.application.port.outbound.embedding.Embedding
import com.homeassistant.application.port.outbound.memoryextraction.component.EpisodeEmbeddingKey
import com.homeassistant.application.port.outbound.memoryextraction.component.EpisodeEmbeddingStore
import com.homeassistant.application.port.outbound.memoryextraction.component.PendingEpisode
import com.homeassistant.application.port.outbound.memoryextraction.group.ComponentEmbeddingKey
import com.homeassistant.application.port.outbound.memoryextraction.group.ComponentEmbeddingStore
import com.homeassistant.application.port.outbound.embedding.EmbeddingGenerator
import com.homeassistant.application.port.outbound.embedding.EmbeddingSpace
import com.homeassistant.application.usecase.memoryextraction.component.AssignEpisodeToComponents
import com.homeassistant.application.usecase.memoryextraction.component.PrepareEpisodeEmbedding
import com.homeassistant.application.usecase.memoryextraction.group.PrepareComponentEmbedding
import com.homeassistant.domain.embedding.EmbeddingVector
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.episode.EpisodeAccess
import com.homeassistant.domain.component.Component
import kotlin.test.*

class AssignEpisodeToComponentsTest {
    private val space = EmbeddingSpace("test", "v1")
    private val east = EmbeddingVector.normalized(listOf(1.0, 0.0))
    private val north = EmbeddingVector.normalized(listOf(0.0, 1.0))
    private val nearEast = EmbeddingVector.normalized(listOf(1.0, 0.2))
    private val onlyB = EpisodeAccess.restricted(listOf(UserId("b")))

    @Test fun `without components every long-term statement of the episode becomes a component`() {
        val fixture = Fixture()
        fixture.statements = mapOf(1 to listOf("홍승민은 임신기간 단축근로를 한다", " ", "홍승민은 임신기간 단축근로를 한다"))
        assertEquals(listOf(1), fixture.service.assign(fixture.episode(1)))
        assertEquals(listOf(Extraction(1, emptyList())), fixture.extractions)
        assertEquals(listOf(Component(1, "홍승민은 임신기간 단축근로를 한다", 1, EpisodeAccess.PUBLIC)), fixture.components)
    }

    @Test fun `known knowledge is the nearest components every reader of the episode can read`() {
        val fixture = Fixture(knownLimit = 2)
        fixture.add("north fact", north)
        fixture.add("east fact", east)
        fixture.add("near east fact", nearEast)
        fixture.add("hidden east fact", east, onlyB)
        fixture.service.assign(fixture.episode(1))
        assertEquals(listOf("east fact", "near east fact"), fixture.extractions.single().known)
    }

    @Test fun `an episode with no new knowledge is assigned to no component`() {
        val fixture = Fixture()
        assertEquals(emptyList(), fixture.service.assign(fixture.episode(1)))
        assertEquals(setOf(1), fixture.extracted)
    }

    @Test fun `a failed extraction writes no component`() {
        val fixture = Fixture()
        fixture.failing = setOf(1)
        assertFails { fixture.service.assign(fixture.episode(1)) }
        assertTrue(fixture.extracted.isEmpty())
    }

    @Test fun `readiness comes from the episode embedding`() {
        val fixture = Fixture()
        assertTrue(fixture.service.isReady())
        fixture.embeddingReady = false
        assertFalse(fixture.service.isReady())
    }

    private inner class Fixture(knownLimit: Int = 10) {
        var vectors = emptyMap<String, EmbeddingVector>()
        var statements = emptyMap<Int, List<String>>()
        var failing = emptySet<Int>()
        val components = mutableListOf<Component>()
        val extracted = mutableSetOf<Int>()
        val extractions = mutableListOf<Extraction>()
        var embeddingReady = true

        fun add(statement: String, vector: EmbeddingVector, access: EpisodeAccess = EpisodeAccess.PUBLIC): Component {
            vectors = vectors + (statement to vector)
            return Component(components.size + 1, statement, 50 + components.size, access).also(components::add)
        }

        private val texts = object : EmbeddingGenerator {
            override val space = this@AssignEpisodeToComponentsTest.space
            override fun isAvailable() = embeddingReady
            override fun embedDocument(text: String) = vectors[text] ?: east
            override fun embedQuery(text: String) = east
        }
        private val episodeCache = mutableMapOf<EpisodeEmbeddingKey, Embedding<EpisodeEmbeddingKey>>()
        private val episodeEmbeddings = PrepareEpisodeEmbedding(texts, object : EpisodeEmbeddingStore {
            override fun find(key: EpisodeEmbeddingKey) = episodeCache[key]
            override fun save(embedding: Embedding<EpisodeEmbeddingKey>) = episodeCache.getOrPut(embedding.key) { embedding }
        })
        private val componentRepository = object : ComponentRepository {
            override fun findByIds(ids: Collection<Int>) = components.filter { it.id in ids }
            override fun findCovering(access: EpisodeAccess) = components.filter { it.access.covers(access) }
            override fun findVisibleTo(userId: UserId) = components.filter { it.isVisibleTo(userId) }
            override fun save(extraction: EpisodeComponents): List<Component> {
                check(extracted.add(extraction.episodeId))
                return extraction.statements.map {
                    Component(components.size + 1, it, extraction.episodeId, EpisodeAccess.PUBLIC).also(components::add)
                }
            }
        }
        private val componentCache = mutableMapOf<ComponentEmbeddingKey, Embedding<ComponentEmbeddingKey>>()
        private val componentEmbeddings = PrepareComponentEmbedding(componentRepository, texts, object :
            ComponentEmbeddingStore {
            override fun find(key: ComponentEmbeddingKey) = componentCache[key]
            override fun save(embedding: Embedding<ComponentEmbeddingKey>) = componentCache.getOrPut(embedding.key) { embedding }
        })
        private val extractor = ComponentExtractor { episode, known ->
            extractions.add(Extraction(episode.id, known))
            if (episode.id in failing) error("model unavailable")
            statements[episode.id].orEmpty()
        }

        val service = AssignEpisodeToComponents(componentRepository, episodeEmbeddings, componentEmbeddings, extractor, knownLimit)

        fun episode(id: Int) = PendingEpisode(id, "title $id", "$id", EpisodeAccess.PUBLIC, listOf("message $id"))
    }

    private data class Extraction(val episodeId: Int, val known: List<String>)
}
