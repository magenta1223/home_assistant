package com.homeassistant.adapter.outbound.persistence.repo.component

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.db.tables.ComponentMembershipTable
import com.homeassistant.adapter.outbound.persistence.db.tables.ComponentTable
import com.homeassistant.adapter.outbound.persistence.repo.episode.EpisodeRepository
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.application.port.output.episode.write.IdempotentEpisodeProposal
import com.homeassistant.application.port.output.component.ComponentJoin
import com.homeassistant.domain.group.ComponentVector
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.episode.EpisodeAccess
import com.homeassistant.domain.episode.EpisodeProposal
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.domain.component.ComponentCentroid
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComponentRepositoryTest {
    private val east = ComponentVector.normalized(listOf(1.0, 0.0))
    private val north = ComponentVector.normalized(listOf(0.0, 1.0))
    private val ab = EpisodeAccess.restricted(listOf(UserId("a"), UserId("b")))

    @Test
    fun `joins keep the centroid sum and list members by their access across restarts`() = withDatabase { db, path ->
        val public = createEpisode(db, "public")
        val shared = createEpisode(db, "shared", ab)
        val other = createEpisode(db, "other", ab)
        val repository = ComponentRepository(db)
        val episodesBefore = EpisodeRepository(db).getEpisodes(UserId("a"))

        val first = ComponentCentroid.of(east)
        val id = repository.create(public, first)
        val second = first + north
        assertTrue(repository.join(shared, listOf(ComponentJoin(id, first, second))))
        assertTrue(repository.join(other, listOf(ComponentJoin(id, second, second + east))))

        val restarted = ComponentRepository(DatabaseFactory.init(path))
        val component = restarted.read(listOf(id)).single()
        assertEquals(mapOf(EpisodeAccess.PUBLIC to listOf(public), ab to listOf(shared, other)), component.slices)
        assertEquals(second + east, component.centroid)
        assertEquals(mapOf(id to second + east), restarted.centroids())
        assertEquals(episodesBefore, EpisodeRepository(db).getEpisodes(UserId("a")))
    }

    @Test
    fun `a join from an outdated centroid changes nothing`() = withDatabase { db, _ ->
        val (first, second, third) = listOf("a", "b", "c").map { createEpisode(db, it) }
        val repository = ComponentRepository(db)
        val start = ComponentCentroid.of(east)
        val id = repository.create(first, start)
        assertTrue(repository.join(second, listOf(ComponentJoin(id, start, start + east))))

        assertFalse(repository.join(third, listOf(ComponentJoin(id, start, start + north))))
        assertEquals(start + east, repository.centroids().getValue(id))
        assertEquals(2L, transaction(db) { ComponentMembershipTable.selectAll().count() })
    }

    @Test
    fun `an episode joins several components and lists in each`() = withDatabase { db, _ ->
        val (a, b, shared) = listOf("a", "b", "shared").map { createEpisode(db, it) }
        val repository = ComponentRepository(db)
        val eastComponent = repository.create(a, ComponentCentroid.of(east))
        val northComponent = repository.create(b, ComponentCentroid.of(north))

        assertTrue(repository.join(shared, listOf(
            ComponentJoin(eastComponent, ComponentCentroid.of(east), ComponentCentroid.of(east) + east),
            ComponentJoin(northComponent, ComponentCentroid.of(north), ComponentCentroid.of(north) + east),
        )))

        val components = repository.read(listOf(eastComponent, northComponent)).associate { it.id to it.slices.getValue(EpisodeAccess.PUBLIC) }
        assertEquals(mapOf(eastComponent to listOf(a, shared), northComponent to listOf(b, shared)), components)
    }

    @Test
    fun `one outdated component leaves every component of the join unchanged`() = withDatabase { db, _ ->
        val (a, b, shared) = listOf("a", "b", "shared").map { createEpisode(db, it) }
        val repository = ComponentRepository(db)
        val eastComponent = repository.create(a, ComponentCentroid.of(east))
        val northComponent = repository.create(b, ComponentCentroid.of(north))

        assertFalse(repository.join(shared, listOf(
            ComponentJoin(eastComponent, ComponentCentroid.of(east), ComponentCentroid.of(east) + east),
            ComponentJoin(northComponent, ComponentCentroid.of(north) + north, ComponentCentroid.of(north) + north + east),
        )))

        assertEquals(mapOf(eastComponent to ComponentCentroid.of(east), northComponent to ComponentCentroid.of(north)), repository.centroids())
        assertEquals(2L, transaction(db) { ComponentMembershipTable.selectAll().count() })
    }

    @Test
    fun `memberships keyed by episode alone are rekeyed with their rows kept`() = withDatabase { db, path ->
        val (a, b) = listOf("a", "b").map { createEpisode(db, it) }
        val component = ComponentRepository(db).create(a, ComponentCentroid.of(east))
        transaction(db) {
            exec("DROP TABLE component_memberships")
            exec("CREATE TABLE component_memberships (component_id INT NOT NULL, episode_id INT NOT NULL PRIMARY KEY)")
            exec("INSERT INTO component_memberships (component_id, episode_id) VALUES ($component, $a)")
        }

        val restarted = ComponentRepository(DatabaseFactory.init(path))
        val other = restarted.create(b, ComponentCentroid.of(north))
        assertTrue(restarted.join(a, listOf(ComponentJoin(other, ComponentCentroid.of(north), ComponentCentroid.of(north) + east))))
        val members = restarted.read(listOf(component, other)).associate { it.id to it.slices.getValue(EpisodeAccess.PUBLIC) }
        assertEquals(mapOf(component to listOf(a), other to listOf(a, b)), members)
    }

    @Test
    fun `an episode already in the component or a missing episode rolls the write back`() = withDatabase { db, _ ->
        val episode = createEpisode(db, "a")
        val repository = ComponentRepository(db)
        val start = ComponentCentroid.of(east)
        val id = repository.create(episode, start)

        assertFails { repository.join(episode, listOf(ComponentJoin(id, start, start + north))) }
        assertFailsWith<IllegalArgumentException> { repository.create(999, ComponentCentroid.of(north)) }
        assertEquals(mapOf(id to start), repository.centroids())
        assertEquals(1L, transaction(db) { ComponentTable.selectAll().count() })
        assertTrue(repository.read(emptyList()).isEmpty())
    }

    private fun createEpisode(db: Database, name: String, access: EpisodeAccess = EpisodeAccess.PUBLIC): Int {
        val record = SourceRecordRepositoryImpl(db).saveAll(
            SourceDescriptor("test", name), listOf(SourceRecordDraft(name, "source-$name")), access,
        ).recordsToAnalyze.single()
        return EpisodeRepository(db).commit(
            UserId("creator"),
            listOf(IdempotentEpisodeProposal(name, EpisodeProposal("fact-$name", listOf(record.id)))),
            listOf(record.id),
        ).single().id
    }

    private fun withDatabase(block: (Database, String) -> Unit) {
        val path = Files.createTempFile("component-store", ".db")
        try {
            block(DatabaseFactory.init(path.toString()), path.toString())
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
