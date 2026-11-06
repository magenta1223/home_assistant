package com.homeassistant.application.usecase.component

import com.homeassistant.application.usecase.contextprovider.component.ComponentMatch
import com.homeassistant.application.usecase.contextprovider.component.ComponentMatchEpisode
import com.homeassistant.application.port.outbound.memoryextraction.group.ComponentEmbedding
import com.homeassistant.application.port.outbound.memoryextraction.group.ComponentEmbeddingKey
import com.homeassistant.application.port.outbound.memoryextraction.group.ComponentEmbeddingStore
import com.homeassistant.application.port.outbound.embedding.EmbeddingGenerator
import com.homeassistant.application.port.outbound.embedding.EmbeddingSpace
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupReader
import com.homeassistant.application.port.outbound.memoryextraction.group.StoredGroup
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeBatch
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeRepository
import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentReader
import com.homeassistant.application.usecase.contextprovider.component.ComponentSearchSettings
import com.homeassistant.application.usecase.contextprovider.component.ComponentSearcher
import com.homeassistant.application.usecase.memoryextraction.group.PrepareComponentEmbedding
import com.homeassistant.domain.group.Group
import com.homeassistant.domain.group.ComponentVector
import com.homeassistant.domain.identity.UserAccessDeniedException
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.episode.Episode
import com.homeassistant.domain.episode.EpisodeAccess
import com.homeassistant.domain.component.Component
import kotlin.test.*

class ComponentSearcherTest {
    private val a = UserId("a")
    private val space = EmbeddingSpace("test", "passage:v1")
    private val east = ComponentVector.normalized(listOf(1.0, 0.0))
    private val north = ComponentVector.normalized(listOf(0.0, 1.0))
    private val northEast = ComponentVector.normalized(listOf(1.0, 1.0))
    private val onlyB = EpisodeAccess.restricted(listOf(UserId("b")))
    private val ab = EpisodeAccess.restricted(listOf(a, UserId("b")))

    @Test fun `backbone components the requester can read come with their source episode`() {
        val fixture = Fixture(
            component(1, east, 10),
            component(2, east, 20, onlyB),
            component(3, northEast, 30),
            component(4, north, 40),
        )

        val matches = fixture.searcher(ComponentSearchSettings(componentLimit = 2)).search(a, "  question  ")

        assertEquals(listOf("question"), fixture.queries)
        assertEquals(listOf(1, 3), matches.map { it.componentId })
        assertEquals(1.0, matches.first().score, 1e-12)
        assertEquals(ComponentMatchEpisode(10, "episode 10", 10L), matches.first().episodes.single())
        assertEquals(ComponentMatchEpisode(30, "episode 30", 30L), matches.last().episodes.single())
    }

    @Test fun `a component outside the nearest components is found through its backbone group`() {
        val fixture = Fixture(component(1, east, 10), component(2, north, 20))
        val narrow = ComponentSearchSettings(componentPool = 1)

        assertEquals(listOf(1), fixture.searcher(narrow).search(a, "q").map { it.componentId })
        fixture.groups += StoredGroup(Group(1, setOf(2)), 1, east)
        assertEquals(listOf(1, 2), fixture.searcher(narrow).search(a, "q").map { it.componentId })
    }

    @Test fun `a component of a neighbor group outside the nearest groups is found`() {
        val fixture = Fixture(component(1, east, 10), component(2, north, 20), component(3, north, 30))
        fixture.groups += listOf(
            StoredGroup(Group(1, setOf(1)), 1, east),
            StoredGroup(Group(2, setOf(2)), 1, northEast),
            StoredGroup(Group(3, setOf(3)), 1, north),
        )

        val matches = fixture.searcher(ComponentSearchSettings(groupPool = 1, groupLimit = 2, componentPool = 1)).search(a, "q")

        assertEquals(listOf(1, 2), matches.map { it.componentId })
    }

    @Test fun `neighbor groups fill the candidates by their similarity to the group they neighbor, not to the question`() {
        val fixture = Fixture(component(1, east, 10), component(2, northEast, 20), component(3, north, 30))
        // At -20°, -60° and 30° from the question: group 3 is nearer to the question than group 2 but less similar to
        // group 1, so the cap of 2 keeps group 2.
        fixture.groups += listOf(
            StoredGroup(Group(1, setOf(1)), 1, degrees(-20.0)),
            StoredGroup(Group(2, setOf(2)), 1, degrees(-60.0)),
            StoredGroup(Group(3, setOf(3)), 1, degrees(30.0)),
        )
        val narrow = ComponentSearchSettings(groupPool = 1, groupCandidates = 2, groupLimit = 3, componentPool = 1)

        assertEquals(setOf(1, 2), fixture.searcher(narrow).search(a, "q").map { it.componentId }.toSet())
    }

    @Test fun `an episode is listed under its first component only`() {
        val fixture = Fixture(component(1, east, 10), component(2, northEast, 10), component(3, north, 20))

        val matches = fixture.searcher().search(a, "q")

        assertEquals(listOf(1, 3), matches.map { it.componentId })
        assertEquals(listOf(10, 20), matches.map { it.episodes.single().episodeId })
    }

    @Test fun `a component whose source episode the reader no longer shows is dropped`() {
        val fixture = Fixture(component(1, east, 11, ab), component(2, north, 12))
        fixture.visible = setOf(12)
        assertEquals(listOf(ComponentMatch(2, 0.0, listOf(ComponentMatchEpisode(12, "episode 12", 12L)))), fixture.searcher().search(a, "q"))
    }

    @Test fun `no component needs no embedding and an unauthorized user is rejected`() {
        val empty = Fixture()
        assertTrue(empty.searcher().search(a, "q").isEmpty())
        assertTrue(empty.queries.isEmpty())
        assertFailsWith<UserAccessDeniedException> {
            Fixture(component(1, east, 10)).searcher(authorized = false).search(a, "q")
        }
    }

    private fun degrees(angle: Double) =
        ComponentVector.normalized(listOf(kotlin.math.cos(Math.toRadians(angle)), kotlin.math.sin(Math.toRadians(angle))))

    private fun component(id: Int, vector: ComponentVector, sourceEpisodeId: Int, access: EpisodeAccess = EpisodeAccess.PUBLIC) =
        Component(id, "c$id", sourceEpisodeId, access) to vector

    private inner class Fixture(vararg components: Pair<Component, ComponentVector>) {
        private val byId = components.associate { it.first.id to it.first }
        private val vectors = components.associate { it.first.statement to it.second }
        val queries = mutableListOf<String>()
        val groups = mutableListOf<StoredGroup>()
        var visible: Set<Int>? = null

        private val reader = object : ComponentReader {
            override fun all() = byId.values.toList()
            override fun read(componentIds: Collection<Int>) = componentIds.mapNotNull { byId[it] }
        }
        private val cache = mutableMapOf<ComponentEmbeddingKey, ComponentEmbedding>()
        private val embeddings = object : EmbeddingGenerator {
            override val space = this@ComponentSearcherTest.space
            override fun isAvailable() = true
            override fun embedDocument(text: String) = vectors.getValue(text)
            override fun embedQuery(text: String): ComponentVector { queries.add(text); return east }
        }
        private val componentEmbeddings = PrepareComponentEmbedding(
            reader,
            embeddings,
            object : ComponentEmbeddingStore {
                override fun find(key: ComponentEmbeddingKey) = cache[key]
                override fun save(embedding: ComponentEmbedding) = cache.getOrPut(embedding.key) { embedding }
            },
        )

        fun searcher(settings: ComponentSearchSettings = ComponentSearchSettings(), authorized: Boolean = true) =
            ComponentSearcher(
                reader,
                GroupReader { groups.toList() },
                embeddings,
                object : EpisodeRepository {
                    override fun findVisibleTo(userId: UserId) = byId.values
                        .filter { it.isVisibleTo(userId) }
                        .map { it.sourceEpisodeId }
                        .distinct()
                        .filter { visible == null || it in visible!! }
                        .map {
                            Episode(
                                it,
                                "creator",
                                "title $it",
                                "episode $it",
                                EpisodeAccess.PUBLIC.visibility,
                                emptySet(),
                                listOf(1),
                                it.toLong()
                            )
                        }
                    override fun save(batch: EpisodeBatch) = error("search never saves episodes")
                },
                componentEmbeddings,
                UserAccessPolicy { authorized },
                settings,
            )
    }
}
