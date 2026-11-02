package com.homeassistant.adapter.outbound.persistence.repo.component

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.db.tables.ComponentAssignmentOutboxTable
import com.homeassistant.adapter.outbound.persistence.repo.episode.EpisodeRepository
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.application.port.output.episode.write.IdempotentEpisodeProposal
import com.homeassistant.application.port.output.component.*
import com.homeassistant.domain.group.ComponentVector
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.episode.EpisodeAccess
import com.homeassistant.domain.episode.EpisodeProposal
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.domain.component.ComponentCentroid
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.*

class ComponentAssignmentOutboxRepositoryTest {
    private val user = UserId("owner")

    @Test fun `saving an episode queues it for assignment`() = withDatabase { db ->
        val (episode) = seedEpisodes(db, "first", "화분 물주기")
        val task = ComponentAssignmentOutboxRepository(db).claimReady(10, now = 0, retryBefore = 0, staleProcessingBefore = -1, maxAttempts = 5).single()
        assertEquals(PendingEpisode(episode, "화분 물주기"), task.episode)
        assertEquals(1, task.attempt)
        assertEquals(OutboxRow("PROCESSING", 1, null), outboxRow(db, episode))
    }

    @Test fun `a failed episode waits for the retry delay and stops after the attempt limit`() = withDatabase { db ->
        val (episode) = seedEpisodes(db, "first", "a")
        val outbox = ComponentAssignmentOutboxRepository(db)
        repeat(2) { attempt ->
            val task = outbox.claimReady(10, now = 1_000L * attempt, retryBefore = 1_000L * attempt, staleProcessingBefore = 0, maxAttempts = 2).single()
            assertTrue(outbox.markFailed(task.outboxId, task.attempt, "invalid decision", now = 1_000L * attempt))
            assertTrue(outbox.claimReady(10, now = 1_000L * attempt, retryBefore = 1_000L * attempt - 1, staleProcessingBefore = 0, maxAttempts = 2).isEmpty())
        }
        assertTrue(outbox.claimReady(10, now = 10_000L, retryBefore = 10_000L, staleProcessingBefore = 0, maxAttempts = 2).isEmpty())
        assertEquals(OutboxRow("FAILED", 2, "invalid decision"), outboxRow(db, episode))
    }

    @Test fun `a stale claim cannot complete after the work was reclaimed`() = withDatabase { db ->
        seedEpisodes(db, "first", "a")
        val outbox = ComponentAssignmentOutboxRepository(db)
        val stale = outbox.claimReady(10, now = 0, retryBefore = 0, staleProcessingBefore = -1, maxAttempts = 5).single()
        val fresh = outbox.claimReady(10, now = 1_000, retryBefore = 0, staleProcessingBefore = 0, maxAttempts = 5).single()
        assertFalse(outbox.markCompleted(stale.outboxId, stale.attempt, now = 1_000))
        assertTrue(outbox.markCompleted(fresh.outboxId, fresh.attempt, now = 1_000))
    }

    @Test fun `an episode assigned before its outbox completed is completed on claim`() = withDatabase { db ->
        val (episode) = seedEpisodes(db, "first", "a")
        ComponentRepository(db).create(episode, ComponentCentroid.of(ComponentVector.normalized(listOf(1.0, 0.0))))
        assertTrue(ComponentAssignmentOutboxRepository(db).claimReady(10, now = 0, retryBefore = 0, staleProcessingBefore = -1, maxAttempts = 5).isEmpty())
        assertEquals(OutboxRow("COMPLETED", 0, null), outboxRow(db, episode))
    }

    @Test fun `episodes saved before the outbox existed are queued once`() = withDatabase { db ->
        val (episode) = seedEpisodes(db, "first", "a")
        transaction(db) { ComponentAssignmentOutboxTable.deleteWhere { ComponentAssignmentOutboxTable.episodeId eq episode } }
        val outbox = ComponentAssignmentOutboxRepository(db)
        assertEquals(1, outbox.enqueueUnassigned(now = 0))
        assertEquals(0, outbox.enqueueUnassigned(now = 0))
        assertEquals(OutboxRow("PENDING", 0, null), outboxRow(db, episode))
    }

    private data class OutboxRow(val status: String, val attempts: Int, val lastError: String?)

    private fun outboxRow(db: Database, episodeId: Int): OutboxRow? = transaction(db) {
        ComponentAssignmentOutboxTable.selectAll().where { ComponentAssignmentOutboxTable.episodeId eq episodeId }.singleOrNull()?.let {
            OutboxRow(it[ComponentAssignmentOutboxTable.status], it[ComponentAssignmentOutboxTable.attempts], it[ComponentAssignmentOutboxTable.lastError])
        }
    }

    private fun seedEpisodes(db: Database, key: String, vararg contents: String): List<Int> {
        val record = SourceRecordRepositoryImpl(db).saveAll(SourceDescriptor("test", key), listOf(SourceRecordDraft(key, contents.joinToString())),
            EpisodeAccess.restricted(listOf(user))).recordsToAnalyze.single()
        return EpisodeRepository(db).commit(user, contents.mapIndexed { index, content ->
            IdempotentEpisodeProposal("$key-$index", EpisodeProposal(content, listOf(record.id)))
        }, listOf(record.id)).map { it.id }
    }

    private fun withDatabase(block: (Database) -> Unit) {
        val path = Files.createTempFile("component-assignment-outbox", ".db")
        try { block(DatabaseFactory.init(path.toString())) }
        finally { Files.deleteIfExists(path) }
    }
}
