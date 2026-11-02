package com.homeassistant.adapter.outbound.persistence.repo.component

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.repo.episode.EpisodeRepository
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.application.port.output.episode.write.IdempotentEpisodeProposal
import com.homeassistant.domain.group.ComponentVector
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.episode.EpisodeAccess
import com.homeassistant.domain.episode.EpisodeProposal
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.domain.component.ComponentCentroid
import com.homeassistant.domain.component.ComponentDescription
import com.homeassistant.domain.component.ComponentViewKey
import org.jetbrains.exposed.sql.Database
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ComponentDescriptionRepositoryTest {
    private val ab = EpisodeAccess.restricted(listOf(UserId("b"), UserId("a")))
    private val ba = EpisodeAccess.restricted(listOf(UserId("a"), UserId("b")))
    private val onlyA = EpisodeAccess.restricted(listOf(UserId("a")))

    @Test
    fun `descriptions are kept per view, replaced on save and survive restarts`() = withDatabase { db, path ->
        val componentId = ComponentRepository(db).create(createEpisode(db), ComponentCentroid.of(ComponentVector.normalized(listOf(1.0, 0.0))))
        val repository = ComponentDescriptionRepository(db)
        val shared = ComponentViewKey(componentId, setOf(EpisodeAccess.PUBLIC, ab))
        val private = ComponentViewKey(componentId, setOf(EpisodeAccess.PUBLIC, onlyA))

        repository.save(shared, ComponentDescription("first", mapOf(EpisodeAccess.PUBLIC to 1, ab to 2)))
        repository.save(shared, ComponentDescription("second", mapOf(EpisodeAccess.PUBLIC to 1, ab to 3)))

        val restarted = ComponentDescriptionRepository(DatabaseFactory.init(path))
        assertEquals(ComponentDescription("second", mapOf(EpisodeAccess.PUBLIC to 1, ab to 3)),
            restarted.find(ComponentViewKey(componentId, setOf(ba, EpisodeAccess.PUBLIC))))
        assertNull(restarted.find(private))
        assertNull(restarted.find(ComponentViewKey(componentId, setOf(EpisodeAccess.PUBLIC))))
    }

    @Test
    fun `a description must cover exactly its view's slices`() = withDatabase { db, _ ->
        assertFailsWith<IllegalArgumentException> {
            ComponentDescriptionRepository(db).save(ComponentViewKey(1, setOf(EpisodeAccess.PUBLIC, onlyA)),
                ComponentDescription("text", mapOf(EpisodeAccess.PUBLIC to 1)))
        }
    }

    private fun createEpisode(db: Database): Int {
        val record = SourceRecordRepositoryImpl(db).saveAll(
            SourceDescriptor("test", "component"), listOf(SourceRecordDraft("component", "source")), EpisodeAccess.PUBLIC,
        ).recordsToAnalyze.single()
        return EpisodeRepository(db).commit(
            UserId("creator"),
            listOf(IdempotentEpisodeProposal("component", EpisodeProposal("title", "fact", listOf(record.id)))),
            listOf(record.id),
        ).single().id
    }

    private fun withDatabase(block: (Database, String) -> Unit) {
        val path = Files.createTempFile("component-description-store", ".db")
        try {
            block(DatabaseFactory.init(path.toString()), path.toString())
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
