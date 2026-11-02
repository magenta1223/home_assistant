package com.homeassistant.adapter.outbound.persistence.repo.episode

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.db.tables.ComponentAssignmentOutboxTable
import com.homeassistant.adapter.outbound.persistence.db.tables.EpisodeTable
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.application.port.output.episode.write.IdempotentEpisodeProposal
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.episode.EpisodeProposal
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecordAnalysisStatus
import com.homeassistant.domain.source.SourceRecordDraft
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class CanonicalEpisodeBatchRepositoryTest {
    @Test
    fun `second episode failure rolls back episodes evidence outbox and source status`() = withDatabase("batch-rollback") { db ->
        val sourceRecords = SourceRecordRepositoryImpl(db)
        val records = sourceRecords.saveAll(SOURCE, listOf(draft("one"), draft("two"))).recordsToAnalyze
        transaction(db) {
            exec(
                """
                CREATE TRIGGER fail_second_episode
                BEFORE INSERT ON episodes
                WHEN NEW.content = 'fail'
                BEGIN SELECT RAISE(ABORT, 'forced second insert failure'); END
                """.trimIndent(),
            )
        }
        val repository = EpisodeRepository(db)

        assertFails {
            repository.commit(
                USER,
                listOf(
                    item("key-1", proposal("ok", records[0].id)),
                    item("key-2", proposal("fail", records[1].id)),
                ),
                records.map { it.id },
            )
        }

        assertTrue(repository.getEpisodes(USER).isEmpty())
        assertEquals(0L, transaction(db) { ComponentAssignmentOutboxTable.selectAll().count() })
        assertTrue(sourceRecords.findBySource(SOURCE).all {
            it.analysisStatus == SourceRecordAnalysisStatus.PENDING
        })
    }

    @Test
    fun `retry reuses exact episodes while different content or evidence is preserved`() =
        withDatabase("batch-idempotency") { db ->
            val sourceRecords = SourceRecordRepositoryImpl(db)
            val records = sourceRecords.saveAll(SOURCE, listOf(draft("one"), draft("two"))).recordsToAnalyze
            val repository = EpisodeRepository(db)
            val firstRequest = listOf(item("stable-key", proposal("same", records[0].id)))

            val first = repository.commit(USER, firstRequest, records.map { it.id })
            val retried = repository.commit(USER, firstRequest, records.map { it.id })
            val variants = repository.commit(
                USER,
                listOf(
                    item("different-evidence", proposal("same", records[1].id)),
                    item(
                        "different-content",
                        proposal("another fact", records[0].id),
                    ),
                ),
                records.map { it.id },
            )

            assertEquals(first.single().id, retried.single().id)
            assertEquals(3, repository.getEpisodes(USER).size)
            assertEquals(2, variants.size)
            assertEquals(3L, transaction(db) { ComponentAssignmentOutboxTable.selectAll().count() })
            assertTrue(sourceRecords.findBySource(SOURCE).all {
                it.analysisStatus == SourceRecordAnalysisStatus.ANALYZED
            })
        }

    private fun item(key: String, proposal: EpisodeProposal) = IdempotentEpisodeProposal(key, proposal)

    private fun proposal(content: String, evidenceId: Int) = EpisodeProposal(
        content = content,
        evidenceIds = listOf(evidenceId),
    )

    private fun draft(key: String) = SourceRecordDraft(key, "content-$key")

    private inline fun withDatabase(prefix: String, block: (Database) -> Unit) {
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
