package com.homeassistant.adapter.outbound.persistence.repo.topic

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.db.tables.TopicMembershipTable
import com.homeassistant.adapter.outbound.persistence.db.tables.TopicTable
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

class TopicRepositoryTest {
    private val east = TopicVector.normalized(listOf(1.0, 0.0))
    private val north = TopicVector.normalized(listOf(0.0, 1.0))
    private val ab = MemoryAccess.restricted(listOf(UserId("a"), UserId("b")))

    @Test
    fun `joins keep the centroid sum and list members by their access across restarts`() = withDatabase { db, path ->
        val public = createMemory(db, "public")
        val shared = createMemory(db, "shared", ab)
        val other = createMemory(db, "other", ab)
        val repository = TopicRepository(db)
        val memoriesBefore = MemoryRepository(db).getMemories(UserId("a"))

        val first = TopicCentroid.of(east)
        val id = repository.create(public, first)
        val second = first + north
        assertTrue(repository.join(id, shared, first, second))
        assertTrue(repository.join(id, other, second, second + east))

        val restarted = TopicRepository(DatabaseFactory.init(path))
        val topic = restarted.readForMaintenance(listOf(id)).single()
        assertEquals(mapOf(MemoryAccess.PUBLIC to listOf(public), ab to listOf(shared, other)), topic.slices)
        assertEquals(second + east, topic.centroid)
        assertEquals(mapOf(id to second + east), restarted.centroids())
        assertEquals(memoriesBefore, MemoryRepository(db).getMemories(UserId("a")))
    }

    @Test
    fun `a join from an outdated centroid changes nothing`() = withDatabase { db, _ ->
        val (first, second, third) = listOf("a", "b", "c").map { createMemory(db, it) }
        val repository = TopicRepository(db)
        val start = TopicCentroid.of(east)
        val id = repository.create(first, start)
        assertTrue(repository.join(id, second, start, start + east))

        assertFalse(repository.join(id, third, start, start + north))
        assertEquals(start + east, repository.centroids().getValue(id))
        assertEquals(2L, transaction(db) { TopicMembershipTable.selectAll().count() })
    }

    @Test
    fun `a memory in a topic or a missing memory rolls the write back`() = withDatabase { db, _ ->
        val memory = createMemory(db, "a")
        val repository = TopicRepository(db)
        val start = TopicCentroid.of(east)
        val id = repository.create(memory, start)

        assertFails { repository.create(memory, TopicCentroid.of(north)) }
        assertFails { repository.join(id, memory, start, start + north) }
        assertFailsWith<IllegalArgumentException> { repository.create(999, TopicCentroid.of(north)) }
        assertEquals(mapOf(id to start), repository.centroids())
        assertEquals(1L, transaction(db) { TopicTable.selectAll().count() })
        assertTrue(repository.readForMaintenance(emptyList()).isEmpty())
    }

    private fun createMemory(db: Database, name: String, access: MemoryAccess = MemoryAccess.PUBLIC): Int {
        val record = SourceRecordRepositoryImpl(db).saveAll(
            SourceDescriptor("test", name), listOf(SourceRecordDraft(name, "source-$name")), access,
        ).recordsToAnalyze.single()
        return MemoryRepository(db).commit(
            UserId("creator"),
            listOf(IdempotentMemoryProposal(name, MemoryProposal("fact-$name", listOf(record.id)))),
            listOf(record.id),
        ).single().id
    }

    private fun withDatabase(block: (Database, String) -> Unit) {
        val path = Files.createTempFile("topic-store", ".db")
        try {
            block(DatabaseFactory.init(path.toString()), path.toString())
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
