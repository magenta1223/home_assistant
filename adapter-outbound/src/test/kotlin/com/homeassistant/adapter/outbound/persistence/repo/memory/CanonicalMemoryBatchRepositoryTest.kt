package com.homeassistant.adapter.outbound.persistence.repo.memory

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.db.tables.IndexingOutboxTable
import com.homeassistant.adapter.outbound.persistence.db.tables.MemoryTable
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.application.port.output.memory.write.IdempotentMemoryProposal
import com.homeassistant.application.usecase.memory.write.MemoryIndexingOutboxProcessor
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryProposal
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.memory.MemoryVisibility
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecordAnalysisStatus
import com.homeassistant.domain.source.SourceRecordDraft
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class CanonicalMemoryBatchRepositoryTest {
    @Test
    fun `second memory failure rolls back memories evidence outbox and source status`() = withDatabase("batch-rollback") { db ->
        val sourceRecords = SourceRecordRepositoryImpl(db)
        val records = sourceRecords.saveAll(SOURCE, listOf(draft("one"), draft("two"))).recordsToAnalyze
        transaction(db) {
            exec(
                """
                CREATE TRIGGER fail_second_memory
                BEFORE INSERT ON memories
                WHEN NEW.content = 'fail'
                BEGIN SELECT RAISE(ABORT, 'forced second insert failure'); END
                """.trimIndent(),
            )
        }
        val repository = MemoryRepository(db)

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

        assertTrue(repository.getMemories(USER).isEmpty())
        assertEquals(0L, transaction(db) { IndexingOutboxTable.selectAll().count() })
        assertTrue(sourceRecords.findBySource(SOURCE).all {
            it.analysisStatus == SourceRecordAnalysisStatus.PENDING
        })
    }

    @Test
    fun `retry reuses exact memories while same content with different meaning or evidence is preserved`() =
        withDatabase("batch-idempotency") { db ->
            val sourceRecords = SourceRecordRepositoryImpl(db)
            val records = sourceRecords.saveAll(SOURCE, listOf(draft("one"), draft("two"))).recordsToAnalyze
            val repository = MemoryRepository(db)
            val firstRequest = listOf(item("stable-key", proposal("same", records[0].id)))

            val first = repository.commit(USER, firstRequest, records.map { it.id })
            val retried = repository.commit(USER, firstRequest, records.map { it.id })
            val variants = repository.commit(
                USER,
                listOf(
                    item("different-evidence", proposal("same", records[1].id)),
                    item(
                        "different-type",
                        proposal("same", records[0].id).copy(memoryType = MemoryType.EVENT),
                    ),
                ),
                records.map { it.id },
            )

            assertEquals(first.single().id, retried.single().id)
            assertEquals(3, repository.getMemories(USER).size)
            assertEquals(2, variants.size)
            assertEquals(3L, transaction(db) { IndexingOutboxTable.selectAll().count() })
            assertTrue(sourceRecords.findBySource(SOURCE).all {
                it.analysisStatus == SourceRecordAnalysisStatus.ANALYZED
            })
        }

    @Test
    fun `failed indexing is durable and retried after the retry delay`() = withDatabase("outbox-retry") { db ->
        val sourceRecords = SourceRecordRepositoryImpl(db)
        val record = sourceRecords.saveAll(SOURCE, listOf(draft("one"))).recordsToAnalyze.single()
        val repository = MemoryRepository(db)
        val memory = repository.commit(
            USER,
            listOf(item("stable-key", proposal("memory", record.id))),
            listOf(record.id),
        ).single()
        val clock = MutableClock(1_000L)
        var shouldFail = true
        val indexed = mutableListOf<Int>()
        val processor = MemoryIndexingOutboxProcessor(
            outbox = MemoryIndexingOutboxRepository(db),
            indexWriter = { candidate ->
                if (shouldFail) false else true.also { indexed += candidate.id }
            },
            clock = clock,
            retryDelayMillis = 100,
        )

        assertEquals(1, processor.processAvailable().failed)
        assertEquals(0, processor.processAvailable().completed)
        shouldFail = false
        clock.millis = 1_101L

        assertEquals(1, processor.processAvailable().completed)
        assertEquals(listOf(memory.id), indexed)
        transaction(db) {
            val row = IndexingOutboxTable.selectAll().single()
            assertEquals("COMPLETED", row[IndexingOutboxTable.status])
            assertEquals(2, row[IndexingOutboxTable.attempts])
            assertEquals(null, row[IndexingOutboxTable.lastError])
        }
    }

    @Test
    fun `full reindex recreates missing outbox rows and visits every canonical memory`() =
        withDatabase("outbox-reindex") { db ->
            val sourceRecords = SourceRecordRepositoryImpl(db)
            val records = sourceRecords.saveAll(SOURCE, listOf(draft("one"), draft("two"))).recordsToAnalyze
            val repository = MemoryRepository(db)
            val memories = repository.commit(
                USER,
                listOf(
                    item("key-1", proposal("one", records[0].id)),
                    item("key-2", proposal("two", records[1].id)),
                ),
                records.map { it.id },
            )
            transaction(db) { IndexingOutboxTable.deleteAll() }
            val indexed = mutableListOf<Int>()
            val processor = MemoryIndexingOutboxProcessor(
                outbox = MemoryIndexingOutboxRepository(db),
                indexWriter = { memory -> true.also { indexed += memory.id } },
                clock = MutableClock(2_000L),
                retryDelayMillis = 0,
            )

            val result = processor.reindexAll()

            assertEquals(2, result.completed)
            assertEquals(0, result.failed)
            assertEquals(memories.map { it.id }, indexed)
            assertEquals(2L, transaction(db) { IndexingOutboxTable.selectAll().count() })
        }

    private fun item(key: String, proposal: MemoryProposal) = IdempotentMemoryProposal(key, proposal)

    private fun proposal(content: String, evidenceId: Int) = MemoryProposal(
        content = content,
        subject = "subject",
        memoryType = MemoryType.REFERENCE,
        certainty = MemoryCertainty.OBSERVED,
        evidenceIds = listOf(evidenceId),
        visibility = MemoryVisibility.PUBLIC,
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

    private class MutableClock(var millis: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(millis)
        override fun millis(): Long = millis
    }

    private companion object {
        val USER = UserId("member-1")
        val SOURCE = SourceDescriptor("test", "source")
    }
}
