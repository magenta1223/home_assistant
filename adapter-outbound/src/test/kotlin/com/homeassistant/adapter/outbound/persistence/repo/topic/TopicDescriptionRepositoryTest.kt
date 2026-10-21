package com.homeassistant.adapter.outbound.persistence.repo.topic

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.repo.memory.MemoryRepository
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.application.port.output.memory.write.IdempotentMemoryProposal
import com.homeassistant.domain.group.TopicVector
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.MemoryAccess
import com.homeassistant.domain.memory.MemoryProposal
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.domain.topic.TopicCentroid
import com.homeassistant.domain.topic.TopicDescription
import com.homeassistant.domain.topic.TopicViewKey
import org.jetbrains.exposed.sql.Database
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TopicDescriptionRepositoryTest {
    private val ab = MemoryAccess.restricted(listOf(UserId("b"), UserId("a")))
    private val ba = MemoryAccess.restricted(listOf(UserId("a"), UserId("b")))
    private val onlyA = MemoryAccess.restricted(listOf(UserId("a")))

    @Test
    fun `descriptions are kept per view, replaced on save and survive restarts`() = withDatabase { db, path ->
        val topicId = TopicRepository(db).create(createMemory(db), TopicCentroid.of(TopicVector.normalized(listOf(1.0, 0.0))))
        val repository = TopicDescriptionRepository(db)
        val shared = TopicViewKey(topicId, setOf(MemoryAccess.PUBLIC, ab))
        val private = TopicViewKey(topicId, setOf(MemoryAccess.PUBLIC, onlyA))

        repository.save(shared, TopicDescription("first", mapOf(MemoryAccess.PUBLIC to 1, ab to 2)))
        repository.save(shared, TopicDescription("second", mapOf(MemoryAccess.PUBLIC to 1, ab to 3)))

        val restarted = TopicDescriptionRepository(DatabaseFactory.init(path))
        assertEquals(TopicDescription("second", mapOf(MemoryAccess.PUBLIC to 1, ab to 3)),
            restarted.find(TopicViewKey(topicId, setOf(ba, MemoryAccess.PUBLIC))))
        assertNull(restarted.find(private))
        assertNull(restarted.find(TopicViewKey(topicId, setOf(MemoryAccess.PUBLIC))))
    }

    @Test
    fun `a description must cover exactly its view's slices`() = withDatabase { db, _ ->
        assertFailsWith<IllegalArgumentException> {
            TopicDescriptionRepository(db).save(TopicViewKey(1, setOf(MemoryAccess.PUBLIC, onlyA)),
                TopicDescription("text", mapOf(MemoryAccess.PUBLIC to 1)))
        }
    }

    private fun createMemory(db: Database): Int {
        val record = SourceRecordRepositoryImpl(db).saveAll(
            SourceDescriptor("test", "topic"), listOf(SourceRecordDraft("topic", "source")), MemoryAccess.PUBLIC,
        ).recordsToAnalyze.single()
        return MemoryRepository(db).commit(
            UserId("creator"),
            listOf(IdempotentMemoryProposal("topic", MemoryProposal("fact", listOf(record.id)))),
            listOf(record.id),
        ).single().id
    }

    private fun withDatabase(block: (Database, String) -> Unit) {
        val path = Files.createTempFile("topic-description-store", ".db")
        try {
            block(DatabaseFactory.init(path.toString()), path.toString())
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
