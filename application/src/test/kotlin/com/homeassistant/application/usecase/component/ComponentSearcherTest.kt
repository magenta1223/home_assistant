package com.homeassistant.application.usecase.component

import com.homeassistant.application.port.input.component.ComponentMatch
import com.homeassistant.application.port.input.component.ComponentMatchEpisode
import com.homeassistant.application.port.output.group.EmbeddingSpace
import com.homeassistant.application.port.output.group.GroupReader
import com.homeassistant.application.port.output.group.StoredGroup
import com.homeassistant.application.port.output.episode.read.EpisodeReader
import com.homeassistant.application.port.output.component.EpisodeEmbedding
import com.homeassistant.application.port.output.component.EpisodeEmbeddingKey
import com.homeassistant.application.port.output.component.EpisodeEmbeddingStore
import com.homeassistant.application.port.output.component.QueryEmbeddingGenerator
import com.homeassistant.application.port.output.component.ComponentReader
import com.homeassistant.domain.group.Group
import com.homeassistant.domain.group.ComponentVector
import com.homeassistant.domain.identity.UserAccessDeniedException
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.episode.Episode
import com.homeassistant.domain.episode.EpisodeAccess
import com.homeassistant.domain.component.Component
import com.homeassistant.domain.component.ComponentCentroid
import kotlin.test.*

class ComponentSearcherTest {
    private val a = UserId("a")
    private val space = EmbeddingSpace("test", "passage:v1")
    private val east = ComponentVector.normalized(listOf(1.0, 0.0))
    private val north = ComponentVector.normalized(listOf(0.0, 1.0))
    private val northEast = ComponentVector.normalized(listOf(1.0, 1.0))
    private val onlyB = EpisodeAccess.restricted(listOf(UserId("b")))
    private val ab = EpisodeAccess.restricted(listOf(a, UserId("b")))

    @Test fun `backbone components come through readable slices only, episodes most relevant first`() {
        val fixture = Fixture(
            component(1, east, EpisodeAccess.PUBLIC to listOf(10), ab to listOf(11), onlyB to listOf(12)),
            component(2, east, onlyB to listOf(20)),
            component(3, northEast, EpisodeAccess.PUBLIC to listOf(30)),
            component(4, north, EpisodeAccess.PUBLIC to listOf(40)),
        )
        fixture.episodeVectors[10] = north

        val matches = fixture.searcher(ComponentSearchSettings(componentLimit = 2)).search(a, "  question  ")

        assertEquals(listOf("question"), fixture.queries)
        assertEquals(listOf(1, 3), matches.map { it.componentId })
        assertEquals(1.0, matches.first().score, 1e-12)
        assertEquals(listOf(11, 10), matches.first().episodes.map { it.episodeId })
        assertEquals(ComponentMatchEpisode(30, "episode 30", 30L), matches.last().episodes.single())
    }

    @Test fun `a component outside the nearest components is found through its backbone group`() {
        val fixture = Fixture(
            component(1, east, EpisodeAccess.PUBLIC to listOf(10)),
            component(2, north, EpisodeAccess.PUBLIC to listOf(20)),
        )
        val narrow = ComponentSearchSettings(componentPool = 1)

        assertEquals(listOf(1), fixture.searcher(narrow).search(a, "q").map { it.componentId })
        fixture.groups += StoredGroup(Group(1, setOf(2)), 1, east)
        assertEquals(listOf(1, 2), fixture.searcher(narrow).search(a, "q").map { it.componentId })
    }

    @Test fun `a component of a neighbor group outside the nearest groups is found`() {
        val fixture = Fixture(
            component(1, east, EpisodeAccess.PUBLIC to listOf(10)),
            component(2, north, EpisodeAccess.PUBLIC to listOf(20)),
            component(3, north, EpisodeAccess.PUBLIC to listOf(30)),
        )
        fixture.groups += listOf(
            StoredGroup(Group(1, setOf(1)), 1, east),
            StoredGroup(Group(2, setOf(2)), 1, northEast),
            StoredGroup(Group(3, setOf(3)), 1, north),
        )

        val matches = fixture.searcher(ComponentSearchSettings(groupPool = 1, groupLimit = 2, componentPool = 1)).search(a, "q")

        assertEquals(listOf(1, 2), matches.map { it.componentId })
    }

    @Test fun `neighbor groups fill the candidates by their similarity to the group they neighbor, not to the question`() {
        val fixture = Fixture(
            component(1, east, EpisodeAccess.PUBLIC to listOf(10)),
            component(2, northEast, EpisodeAccess.PUBLIC to listOf(20)),
            component(3, north, EpisodeAccess.PUBLIC to listOf(30)),
        )
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

    @Test fun `an episode is listed under its first component only and a component left empty is dropped`() {
        val fixture = Fixture(
            component(1, east, EpisodeAccess.PUBLIC to listOf(10, 11)),
            component(2, northEast, EpisodeAccess.PUBLIC to listOf(11, 20)),
            component(3, north, EpisodeAccess.PUBLIC to listOf(10)),
        )

        val matches = fixture.searcher().search(a, "q")

        assertEquals(listOf(1, 2), matches.map { it.componentId })
        assertEquals(listOf(listOf(10, 11), listOf(20)), matches.map { match -> match.episodes.map { it.episodeId } })
    }

    @Test fun `a slice member the reader no longer shows is dropped`() {
        val fixture = Fixture(component(1, east, ab to listOf(11, 12)))
        fixture.visible = setOf(11)
        assertEquals(listOf(ComponentMatch(1, 1.0, listOf(ComponentMatchEpisode(11, "episode 11", 11L)))), fixture.searcher().search(a, "q"))
    }

    @Test fun `no component needs no embedding and an unauthorized user is rejected`() {
        val empty = Fixture()
        assertTrue(empty.searcher().search(a, "q").isEmpty())
        assertTrue(empty.queries.isEmpty())
        assertFailsWith<UserAccessDeniedException> {
            Fixture(component(1, east, EpisodeAccess.PUBLIC to listOf(10))).searcher(authorized = false).search(a, "q")
        }
    }

    private fun degrees(angle: Double) =
        ComponentVector.normalized(listOf(kotlin.math.cos(Math.toRadians(angle)), kotlin.math.sin(Math.toRadians(angle))))

    private fun component(id: Int, vector: ComponentVector, vararg slices: Pair<EpisodeAccess, List<Int>>): Component {
        val count = slices.sumOf { it.second.size }
        val centroid = (2..count).fold(ComponentCentroid.of(vector)) { sum, _ -> sum + vector }
        return Component(id, centroid, slices.toMap())
    }

    private inner class Fixture(vararg components: Component) {
        private val byId = components.associateBy { it.id }
        val queries = mutableListOf<String>()
        val groups = mutableListOf<StoredGroup>()
        val episodeVectors = mutableMapOf<Int, ComponentVector>()
        var visible: Set<Int>? = null

        fun searcher(settings: ComponentSearchSettings = ComponentSearchSettings(), authorized: Boolean = true) = ComponentSearcher(
            object : ComponentReader {
                override fun centroids() = byId.mapValues { it.value.centroid }
                override fun read(componentIds: Collection<Int>) = componentIds.mapNotNull { byId[it] }
            },
            GroupReader { groups.toList() },
            QueryEmbeddingGenerator { queries.add(it); east },
            object : EpisodeReader {
                override fun getEpisodes(userId: UserId) = byId.values
                    .flatMap { component -> component.slices.filterKeys { it.isVisibleTo(userId) }.values.flatten() }
                    .distinct()
                    .filter { visible == null || it in visible!! }
                    .map { Episode(it, "creator", "episode $it", EpisodeAccess.PUBLIC.visibility, emptySet(), listOf(1), it.toLong()) }
            },
            object : EpisodeEmbeddingStore {
                override fun find(key: EpisodeEmbeddingKey) =
                    EpisodeEmbedding(key, episodeVectors[key.episodeId] ?: east).takeIf { key == EpisodeEmbeddingKey.of(key.episodeId, "episode ${key.episodeId}", space) }
                override fun save(embedding: EpisodeEmbedding) = embedding
            },
            space,
            UserAccessPolicy { authorized },
            settings,
        )
    }
}
