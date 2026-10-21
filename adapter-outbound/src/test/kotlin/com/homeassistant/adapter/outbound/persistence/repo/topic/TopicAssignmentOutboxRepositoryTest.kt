package com.homeassistant.adapter.outbound.persistence.repo.topic

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.db.tables.TopicAssignmentOutboxTable
import com.homeassistant.adapter.outbound.persistence.repo.memory.MemoryRepository
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.application.port.output.memory.write.IdempotentMemoryProposal
import com.homeassistant.application.port.output.topic.*
import com.homeassistant.domain.group.TopicVector
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.MemoryAccess
import com.homeassistant.domain.memory.MemoryProposal
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.domain.topic.TopicCentroid
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.*

class TopicAssignmentOutboxRepositoryTest {
    private val user = UserId("owner")

    @Test fun `saving a memory queues it for assignment`() = withDatabase { db ->
        val (memory) = seedMemories(db, "first", "화분 물주기")
        val task = TopicAssignmentOutboxRepository(db).claimReady(10, now = 0, retryBefore = 0, staleProcessingBefore = -1, maxAttempts = 5).single()
        assertEquals(PendingMemory(memory, "화분 물주기"), task.memory)
        assertEquals(1, task.attempt)
        assertEquals(OutboxRow("PROCESSING", 1, null), outboxRow(db, memory))
    }

    @Test fun `a failed memory waits for the retry delay and stops after the attempt limit`() = withDatabase { db ->
        val (memory) = seedMemories(db, "first", "a")
        val outbox = TopicAssignmentOutboxRepository(db)
        repeat(2) { attempt ->
            val task = outbox.claimReady(10, now = 1_000L * attempt, retryBefore = 1_000L * attempt, staleProcessingBefore = 0, maxAttempts = 2).single()
            assertTrue(outbox.markFailed(task.outboxId, task.attempt, "invalid decision", now = 1_000L * attempt))
            assertTrue(outbox.claimReady(10, now = 1_000L * attempt, retryBefore = 1_000L * attempt - 1, staleProcessingBefore = 0, maxAttempts = 2).isEmpty())
        }
        assertTrue(outbox.claimReady(10, now = 10_000L, retryBefore = 10_000L, staleProcessingBefore = 0, maxAttempts = 2).isEmpty())
        assertEquals(OutboxRow("FAILED", 2, "invalid decision"), outboxRow(db, memory))
    }

    @Test fun `a stale claim cannot complete after the work was reclaimed`() = withDatabase { db ->
        seedMemories(db, "first", "a")
        val outbox = TopicAssignmentOutboxRepository(db)
        val stale = outbox.claimReady(10, now = 0, retryBefore = 0, staleProcessingBefore = -1, maxAttempts = 5).single()
        val fresh = outbox.claimReady(10, now = 1_000, retryBefore = 0, staleProcessingBefore = 0, maxAttempts = 5).single()
        assertFalse(outbox.markCompleted(stale.outboxId, stale.attempt, now = 1_000))
        assertTrue(outbox.markCompleted(fresh.outboxId, fresh.attempt, now = 1_000))
    }

    @Test fun `a memory assigned before its outbox completed is completed on claim`() = withDatabase { db ->
        val (memory) = seedMemories(db, "first", "a")
        TopicRepository(db).create(memory, TopicCentroid.of(TopicVector.normalized(listOf(1.0, 0.0))))
        assertTrue(TopicAssignmentOutboxRepository(db).claimReady(10, now = 0, retryBefore = 0, staleProcessingBefore = -1, maxAttempts = 5).isEmpty())
        assertEquals(OutboxRow("COMPLETED", 0, null), outboxRow(db, memory))
    }

    @Test fun `memories saved before the outbox existed are queued once`() = withDatabase { db ->
        val (memory) = seedMemories(db, "first", "a")
        transaction(db) { TopicAssignmentOutboxTable.deleteWhere { TopicAssignmentOutboxTable.memoryId eq memory } }
        val outbox = TopicAssignmentOutboxRepository(db)
        assertEquals(1, outbox.enqueueUnassigned(now = 0))
        assertEquals(0, outbox.enqueueUnassigned(now = 0))
        assertEquals(OutboxRow("PENDING", 0, null), outboxRow(db, memory))
    }

    private data class OutboxRow(val status: String, val attempts: Int, val lastError: String?)

    private fun outboxRow(db: Database, memoryId: Int): OutboxRow? = transaction(db) {
        TopicAssignmentOutboxTable.selectAll().where { TopicAssignmentOutboxTable.memoryId eq memoryId }.singleOrNull()?.let {
            OutboxRow(it[TopicAssignmentOutboxTable.status], it[TopicAssignmentOutboxTable.attempts], it[TopicAssignmentOutboxTable.lastError])
        }
    }

    private fun seedMemories(db: Database, key: String, vararg contents: String): List<Int> {
        val record = SourceRecordRepositoryImpl(db).saveAll(SourceDescriptor("test", key), listOf(SourceRecordDraft(key, contents.joinToString())),
            MemoryAccess.restricted(listOf(user))).recordsToAnalyze.single()
        return MemoryRepository(db).commit(user, contents.mapIndexed { index, content ->
            IdempotentMemoryProposal("$key-$index", MemoryProposal(content, listOf(record.id)))
        }, listOf(record.id)).map { it.id }
    }

    private fun withDatabase(block: (Database) -> Unit) {
        val path = Files.createTempFile("topic-assignment-outbox", ".db")
        try { block(DatabaseFactory.init(path.toString())) }
        finally { Files.deleteIfExists(path) }
    }
}
