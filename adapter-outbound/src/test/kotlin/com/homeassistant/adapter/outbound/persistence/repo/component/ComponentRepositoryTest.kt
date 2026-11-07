package com.homeassistant.adapter.outbound.persistence.repo.component

import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeBatch
import com.homeassistant.application.port.outbound.memoryextraction.component.EpisodeComponents
import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.db.tables.ComponentExtractionTable
import com.homeassistant.adapter.outbound.persistence.db.tables.ComponentTable
import com.homeassistant.adapter.outbound.persistence.repo.episode.EpisodeRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.domain.component.Component
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.episode.EpisodeAccess
import com.homeassistant.domain.episode.EpisodeProposal
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecordDraft
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ComponentRepositoryTest {
    private val ab = EpisodeAccess.restricted(listOf(UserId("a"), UserId("b")))

    @Test
    fun `an extraction stores its statements with the source episode's access across restarts`() = withDatabase { db, path ->
        val episode = createEpisode(db, "a", ab)
        val saved = ComponentRepositoryImpl(db).save(EpisodeComponents(episode, listOf("first fact", "second fact")))

        val expected = listOf(Component(saved[0].id, "first fact", episode, ab), Component(saved[1].id, "second fact", episode, ab))
        assertEquals(expected, saved)
        assertEquals(expected, ComponentRepositoryImpl(DatabaseFactory.init(path)).findVisibleTo(UserId("a")))
    }

    @Test
    fun `access conditions select the Components the domain access rules select`() = withDatabase { db, _ ->
        val repository = ComponentRepositoryImpl(db)
        val accesses = listOf(EpisodeAccess.PUBLIC, ab, EpisodeAccess.restricted(listOf(UserId("a"))))
        val stored = accesses.mapIndexed { index, access ->
            repository.save(EpisodeComponents(createEpisode(db, "e$index", access), listOf("fact $index"))).single()
        }

        listOf("a", "b", "c").map(::UserId).forEach { user ->
            assertEquals(stored.filter { it.isVisibleTo(user) }, repository.findVisibleTo(user))
        }
        accesses.forEach { access ->
            assertEquals(stored.filter { it.access.covers(access) }, repository.findCovering(access))
        }
    }

    @Test
    fun `an episode without long-term knowledge is still marked extracted`() = withDatabase { db, _ ->
        val episode = createEpisode(db, "a")

        assertTrue(ComponentRepositoryImpl(db).save(EpisodeComponents(episode, emptyList())).isEmpty())
        assertEquals(1L, transaction(db) { ComponentExtractionTable.selectAll().count() })
    }

    @Test
    fun `a second extraction or a missing episode rolls the write back`() = withDatabase { db, _ ->
        val episode = createEpisode(db, "a")
        val repository = ComponentRepositoryImpl(db)
        repository.save(EpisodeComponents(episode, listOf("fact")))

        assertFails { repository.save(EpisodeComponents(episode, listOf("again"))) }
        assertFailsWith<IllegalArgumentException> { repository.save(EpisodeComponents(999, listOf("orphan"))) }
        assertEquals(listOf("fact"), repository.findCovering(EpisodeAccess.PUBLIC).map { it.statement })
        assertEquals(1L, transaction(db) { ComponentTable.selectAll().count() })
    }

    private fun createEpisode(db: Database, name: String, access: EpisodeAccess = EpisodeAccess.PUBLIC): Int {
        val record = SourceRecordRepositoryImpl(db).saveAll(
            SourceDescriptor("test", name), listOf(SourceRecordDraft(name, "source-$name")), access,
        ).recordsToAnalyze.single()
        return EpisodeRepositoryImpl(db).save(EpisodeBatch(
            UserId("creator"),
            listOf(EpisodeProposal("title", "fact-$name", listOf(record.id))),
            listOf(record.id),
        )).single().id
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
