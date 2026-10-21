package com.homeassistant.adapter.outbound.persistence.repo.topic

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.db.tables.TopicMembershipTable
import com.homeassistant.adapter.outbound.persistence.db.tables.TopicTable
import com.homeassistant.adapter.outbound.persistence.repo.memory.MemoryRepository
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.application.port.output.memory.write.IdempotentMemoryProposal
import com.homeassistant.application.port.output.topic.TopicJoin
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
        assertTrue(repository.join(shared, listOf(TopicJoin(id, first, second))))
        assertTrue(repository.join(other, listOf(TopicJoin(id, second, second + east))))

        val restarted = TopicRepository(DatabaseFactory.init(path))
        val topic = restarted.read(listOf(id)).single()
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
        assertTrue(repository.join(second, listOf(TopicJoin(id, start, start + east))))

        assertFalse(repository.join(third, listOf(TopicJoin(id, start, start + north))))
        assertEquals(start + east, repository.centroids().getValue(id))
        assertEquals(2L, transaction(db) { TopicMembershipTable.selectAll().count() })
    }

    @Test
    fun `a memory joins several topics and lists in each`() = withDatabase { db, _ ->
        val (a, b, shared) = listOf("a", "b", "shared").map { createMemory(db, it) }
        val repository = TopicRepository(db)
        val eastTopic = repository.create(a, TopicCentroid.of(east))
        val northTopic = repository.create(b, TopicCentroid.of(north))

        assertTrue(repository.join(shared, listOf(
            TopicJoin(eastTopic, TopicCentroid.of(east), TopicCentroid.of(east) + east),
            TopicJoin(northTopic, TopicCentroid.of(north), TopicCentroid.of(north) + east),
        )))

        val topics = repository.read(listOf(eastTopic, northTopic)).associate { it.id to it.slices.getValue(MemoryAccess.PUBLIC) }
        assertEquals(mapOf(eastTopic to listOf(a, shared), northTopic to listOf(b, shared)), topics)
    }

    @Test
    fun `one outdated topic leaves every topic of the join unchanged`() = withDatabase { db, _ ->
        val (a, b, shared) = listOf("a", "b", "shared").map { createMemory(db, it) }
        val repository = TopicRepository(db)
        val eastTopic = repository.create(a, TopicCentroid.of(east))
        val northTopic = repository.create(b, TopicCentroid.of(north))

        assertFalse(repository.join(shared, listOf(
            TopicJoin(eastTopic, TopicCentroid.of(east), TopicCentroid.of(east) + east),
            TopicJoin(northTopic, TopicCentroid.of(north) + north, TopicCentroid.of(north) + north + east),
        )))

        assertEquals(mapOf(eastTopic to TopicCentroid.of(east), northTopic to TopicCentroid.of(north)), repository.centroids())
        assertEquals(2L, transaction(db) { TopicMembershipTable.selectAll().count() })
    }

    @Test
    fun `memberships keyed by memory alone are rekeyed with their rows kept`() = withDatabase { db, path ->
        val (a, b) = listOf("a", "b").map { createMemory(db, it) }
        val topic = TopicRepository(db).create(a, TopicCentroid.of(east))
        transaction(db) {
            exec("DROP TABLE topic_memberships")
            exec("CREATE TABLE topic_memberships (topic_id INT NOT NULL, memory_id INT NOT NULL PRIMARY KEY)")
            exec("INSERT INTO topic_memberships (topic_id, memory_id) VALUES ($topic, $a)")
        }

        val restarted = TopicRepository(DatabaseFactory.init(path))
        val other = restarted.create(b, TopicCentroid.of(north))
        assertTrue(restarted.join(a, listOf(TopicJoin(other, TopicCentroid.of(north), TopicCentroid.of(north) + east))))
        val members = restarted.read(listOf(topic, other)).associate { it.id to it.slices.getValue(MemoryAccess.PUBLIC) }
        assertEquals(mapOf(topic to listOf(a), other to listOf(a, b)), members)
    }

    @Test
    fun `a memory already in the topic or a missing memory rolls the write back`() = withDatabase { db, _ ->
        val memory = createMemory(db, "a")
        val repository = TopicRepository(db)
        val start = TopicCentroid.of(east)
        val id = repository.create(memory, start)

        assertFails { repository.join(memory, listOf(TopicJoin(id, start, start + north))) }
        assertFailsWith<IllegalArgumentException> { repository.create(999, TopicCentroid.of(north)) }
        assertEquals(mapOf(id to start), repository.centroids())
        assertEquals(1L, transaction(db) { TopicTable.selectAll().count() })
        assertTrue(repository.read(emptyList()).isEmpty())
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
