package com.homeassistant.adapter.outbound.persistence.repo.memory.xmemory.episode

import kotlin.test.assertIs
import com.homeassistant.application.port.outbound.source.SourceRecordSaveResult
import com.homeassistant.feature.memory.xmemory.port.outbound.episode.EpisodeBatch
import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.db.tables.memory.xmemory.ComponentAssignmentOutboxTable
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.xmemory.episode.EpisodeProposal
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.adapter.outbound.persistence.db.SqliteDatabases
import org.jetbrains.exposed.sql.selectAll
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class CanonicalEpisodeBatchRepositoryTest {
    @Test
    fun `second episode failure rolls back episodes evidence outbox and extraction mark`() = withDatabase("batch-rollback") { db ->
        val sourceRecords = SourceRecordRepositoryImpl(db)
        val records = assertIs<SourceRecordSaveResult.Saved>(sourceRecords.save(SOURCE, listOf(draft("one"), draft("two")), SUBMITTER)).newRecords
        db.write {
            exec(
                """
                CREATE TRIGGER fail_second_episode
                BEFORE INSERT ON episodes
                WHEN NEW.content = 'fail'
                BEGIN SELECT RAISE(ABORT, 'forced second insert failure'); END
                """.trimIndent(),
            )
        }
        val repository = EpisodeRepositoryImpl(db)

        assertFails {
            repository.save(EpisodeBatch(
                USER,
                listOf(
                    proposal("ok", records[0].id),
                    proposal("fail", records[1].id),
                ),
                records.map { it.id },
            ))
        }

        assertTrue(repository.findVisibleTo(USER).isEmpty())
        assertEquals(0L, db.write { ComponentAssignmentOutboxTable.selectAll().count() })
        assertEquals(records.map { it.id }, waitingRecordIds(db))
    }

    @Test
    fun `retry reuses exact episodes while different content or evidence is preserved`() =
        withDatabase("batch-idempotency") { db ->
            val sourceRecords = SourceRecordRepositoryImpl(db)
            val records = assertIs<SourceRecordSaveResult.Saved>(sourceRecords.save(SOURCE, listOf(draft("one"), draft("two")), SUBMITTER)).newRecords
            val repository = EpisodeRepositoryImpl(db)
            val firstRequest = listOf(item("stable-key", proposal("same", records[0].id)))

            val first = repository.save(EpisodeBatch(USER, firstRequest, records.map { it.id }))
            val retried = repository.save(EpisodeBatch(USER, firstRequest, records.map { it.id }))
            val variants = repository.save(EpisodeBatch(
                USER,
                listOf(
                    item("different-evidence", proposal("same", records[1].id)),
                    item(
                        "different-content",
                        proposal("another fact", records[0].id),
                    ),
                ),
                records.map { it.id },
            ))

            assertEquals(first.single().id, retried.single().id)
            assertEquals(3, repository.findVisibleTo(USER).size)
            assertEquals(2, variants.size)
            assertEquals(3L, db.write { ComponentAssignmentOutboxTable.selectAll().count() })
            assertEquals(emptyList(), waitingRecordIds(db))
        }

    private fun item(key: String, proposal: EpisodeProposal) = proposal

    private fun proposal(content: String, evidenceId: Int) = EpisodeProposal(
        title = "title",
        content = content,
        evidenceIds = listOf(evidenceId),
    )

    private fun draft(key: String) = SourceRecordDraft(key, "content-$key")

    /** The ids of the records still waiting for xMemory extraction. */
    private fun waitingRecordIds(db: SqliteDatabases): List<Int> =
        EpisodeExtractionQueueRepository(db).claimNext(now = 0, retryBefore = 0, staleProcessingBefore = -1, maxAttempts = 5)
            ?.document?.records?.map { it.id }.orEmpty()

    private inline fun withDatabase(prefix: String, block: (SqliteDatabases) -> Unit) {
        val path = Files.createTempFile(prefix, ".db")
        try {
            block(DatabaseFactory.init(path.toString()))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private companion object {
        val USER = UserId("member-1")
        val SOURCE = SourceDescriptor("test", "source")
    }
}

private val SUBMITTER = UserId("submitter")
