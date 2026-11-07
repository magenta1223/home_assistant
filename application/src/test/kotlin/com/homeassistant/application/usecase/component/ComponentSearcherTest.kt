package com.homeassistant.application.usecase.component

import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentRepository
import com.homeassistant.application.port.outbound.memoryextraction.component.EpisodeComponents
import com.homeassistant.application.port.outbound.embedding.Embedding
import com.homeassistant.application.port.outbound.memoryextraction.group.ComponentEmbeddingKey
import com.homeassistant.application.port.outbound.memoryextraction.group.ComponentEmbeddingStore
import com.homeassistant.application.port.outbound.embedding.EmbeddingGenerator
import com.homeassistant.application.port.outbound.embedding.EmbeddingSpace
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupChange
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupRepository
import com.homeassistant.application.port.outbound.memoryextraction.group.StoredGroup
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeBatch
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeRepository
import com.homeassistant.application.usecase.contextprovider.component.ComponentSearchSettings
import com.homeassistant.application.usecase.contextprovider.component.ComponentSearcher
import com.homeassistant.application.usecase.embedding.PrepareComponentEmbedding
import com.homeassistant.domain.group.Group
import com.homeassistant.domain.embedding.EmbeddingVector
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
    private val east = EmbeddingVector.normalized(listOf(1.0, 0.0))
    private val north = EmbeddingVector.normalized(listOf(0.0, 1.0))
    private val northEast = EmbeddingVector.normalized(listOf(1.0, 1.0))
    private val onlyB = EpisodeAccess.restricted(listOf(UserId("b")))
    private val ab = EpisodeAccess.restricted(listOf(a, UserId("b")))

    @Test fun `backbone components the requester can read are kept`() {
        val fixture = Fixture(
            component(1, east, 10),
            component(2, east, 20, onlyB),
            component(3, northEast, 30),
            component(4, north, 40),
        )

        val matches = fixture.searcher(ComponentSearchSettings(componentLimit = 2)).search(a, "  question  ")

        assertEquals(listOf("question"), fixture.queries)
        assertEquals(listOf(1, 3), matches.map { it.id })
    }

    @Test fun `a component outside the nearest components is found through its backbone group`() {
        val fixture = Fixture(component(1, east, 10), component(2, north, 20))
        val narrow = ComponentSearchSettings(componentPool = 1)

        assertEquals(listOf(1), fixture.searcher(narrow).search(a, "q").map { it.id })
        fixture.groups += StoredGroup(Group(1, setOf(2)), 1, east)
        assertEquals(listOf(1, 2), fixture.searcher(narrow).search(a, "q").map { it.id })
    }

    @Test fun `a component of a neighbor group outside the nearest groups is found`() {
        val fixture = Fixture(component(1, east, 10), component(2, north, 20), component(3, north, 30))
        fixture.groups += listOf(
            StoredGroup(Group(1, setOf(1)), 1, east),
            StoredGroup(Group(2, setOf(2)), 1, northEast),
            StoredGroup(Group(3, setOf(3)), 1, north),
        )

        val matches = fixture.searcher(ComponentSearchSettings(groupPool = 1, groupLimit = 2, componentPool = 1)).search(a, "q")

        assertEquals(listOf(1, 2), matches.map { it.id })
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

        assertEquals(setOf(1, 2), fixture.searcher(narrow).search(a, "q").map { it.id }.toSet())
    }

    @Test fun `components from the same source episode are all kept`() {
        val fixture = Fixture(component(1, east, 10), component(2, northEast, 10), component(3, north, 20))

        assertEquals(setOf(1, 2, 3), fixture.searcher().search(a, "q").map { it.id }.toSet())
    }

    @Test fun `a component whose source episode the reader no longer shows is dropped`() {
        val fixture = Fixture(component(1, east, 11, ab), component(2, north, 12))
        fixture.visible = setOf(12)
        assertEquals(listOf(2), fixture.searcher().search(a, "q").map { it.id })
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
        EmbeddingVector.normalized(listOf(kotlin.math.cos(Math.toRadians(angle)), kotlin.math.sin(Math.toRadians(angle))))

    private fun component(id: Int, vector: EmbeddingVector, sourceEpisodeId: Int, access: EpisodeAccess = EpisodeAccess.PUBLIC) =
        Component(id, "c$id", sourceEpisodeId, access) to vector

    private inner class Fixture(vararg components: Pair<Component, EmbeddingVector>) {
        private val byId = components.associate { it.first.id to it.first }
        private val vectors = components.associate { it.first.statement to it.second }
        val queries = mutableListOf<String>()
        val groups = mutableListOf<StoredGroup>()
        var visible: Set<Int>? = null

        private val reader = object : ComponentRepository {
            override fun findCovering(access: EpisodeAccess) = byId.values.filter { it.access.covers(access) }
            override fun findVisibleTo(userId: UserId) = byId.values.filter { it.isVisibleTo(userId) }
            override fun findUngrouped(limit: Int) = error("search never places Components")
            override fun save(extraction: EpisodeComponents) = error("search never saves Components")
        }
        private val cache = mutableMapOf<ComponentEmbeddingKey, Embedding<ComponentEmbeddingKey>>()
        private val embeddings = object : EmbeddingGenerator {
            override val space = this@ComponentSearcherTest.space
            override fun isAvailable() = true
            override fun embedDocument(text: String) = vectors.getValue(text)
            override fun embedQuery(text: String): EmbeddingVector { queries.add(text); return east }
        }
        private val componentEmbeddings = PrepareComponentEmbedding(
            embeddings,
            object : ComponentEmbeddingStore {
                override fun find(key: ComponentEmbeddingKey) = cache[key]
                override fun save(embedding: Embedding<ComponentEmbeddingKey>) = cache.getOrPut(embedding.key) { embedding }
            },
        )

        fun searcher(settings: ComponentSearchSettings = ComponentSearchSettings(), authorized: Boolean = true) =
            ComponentSearcher(
                reader,
                object : GroupRepository {
                    override fun findSnapshot() = error("search never reads the snapshot")
                    override fun findVisibleTo(userId: UserId) = groups.mapNotNull { stored ->
                        val visible = stored.group.componentIds.filter { byId.getValue(it).isVisibleTo(userId) }.toSet()
                        if (visible.isEmpty()) null else stored.copy(group = Group(stored.group.id, visible))
                    }
                    override fun save(change: GroupChange) = error("search never saves Groups")
                },
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
