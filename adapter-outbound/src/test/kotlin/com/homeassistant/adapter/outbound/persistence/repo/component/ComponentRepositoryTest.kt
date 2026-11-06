package com.homeassistant.adapter.outbound.persistence.repo.component

import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeBatch
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
        val ids = ComponentRepository(db).writeComponents(episode, listOf("first fact", "second fact"))

        val expected = listOf(Component(ids[0], "first fact", episode, ab), Component(ids[1], "second fact", episode, ab))
        assertEquals(expected, ComponentRepository(db).all())
        assertEquals(expected.drop(1), ComponentRepository(DatabaseFactory.init(path)).read(listOf(ids[1])))
    }

    @Test
    fun `an episode without long-term knowledge is still marked extracted`() = withDatabase { db, _ ->
        val episode = createEpisode(db, "a")

        assertTrue(ComponentRepository(db).writeComponents(episode, emptyList()).isEmpty())
        assertEquals(1L, transaction(db) { ComponentExtractionTable.selectAll().count() })
    }

    @Test
    fun `a second extraction or a missing episode rolls the write back`() = withDatabase { db, _ ->
        val episode = createEpisode(db, "a")
        val repository = ComponentRepository(db)
        repository.writeComponents(episode, listOf("fact"))

        assertFails { repository.writeComponents(episode, listOf("again")) }
        assertFailsWith<IllegalArgumentException> { repository.writeComponents(999, listOf("orphan")) }
        assertEquals(listOf("fact"), repository.all().map { it.statement })
        assertEquals(1L, transaction(db) { ComponentTable.selectAll().count() })
        assertTrue(repository.read(emptyList()).isEmpty())
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
