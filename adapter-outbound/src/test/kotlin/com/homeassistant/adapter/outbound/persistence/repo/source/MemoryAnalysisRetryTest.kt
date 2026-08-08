package com.homeassistant.adapter.outbound.persistence.repo.source

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.application.memory.analysis.DuplicateSourceRecordsException
import com.homeassistant.application.memory.analysis.MemoryAnalysisRequest
import com.homeassistant.application.memory.analysis.MemoryAnalysisService
import com.homeassistant.application.memory.analysis.MemoryExtractionException
import com.homeassistant.application.memory.analysis.MemoryExtractor
import com.homeassistant.application.memory.write.MemoryProposalsPersister
import com.homeassistant.application.memory.write.MemoryWriter
import com.homeassistant.domain.identity.HouseholdAccessPolicies
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryProposal
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.memory.MemoryVisibility
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.source.SourceDocumentDraft
import com.homeassistant.domain.source.SourceRecordAnalysisStatus
import com.homeassistant.domain.source.SourceRecordDraft
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MemoryAnalysisRetryTest {
    @Test
    fun `memory persistence failure leaves source pending`() = runBlocking {
        val databasePath = Files.createTempFile("analysis-persistence-failure", ".db")
        try {
            val sourceRecords = SourceRecordRepositoryImpl(DatabaseFactory.init(databasePath.toString()))
            val extractor = RecordingExtractor { document ->
                listOf(proposal(document, MemoryVisibility.PUBLIC))
            }
            val failure = IllegalStateException("persistence failed")
            val service = service(sourceRecords, extractor, RecordingMemoryWriter(failure))
            val request = request("a")

            assertSame(failure, assertFailsWith<IllegalStateException> { service.execute(request) })
            assertEquals(
                SourceRecordAnalysisStatus.PENDING,
                sourceRecords.findBySource(request.source.source).single().analysisStatus,
            )
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `extractor failure remains pending then retry succeeds and later upload is duplicate`() = runBlocking {
        val databasePath = Files.createTempFile("analysis-retry", ".db")
        try {
            val sourceRecords = SourceRecordRepositoryImpl(DatabaseFactory.init(databasePath.toString()))
            var shouldFail = true
            val extractor = RecordingExtractor { document ->
                if (shouldFail) {
                    shouldFail = false
                    throw MemoryExtractionException("temporary failure")
                }
                listOf(proposal(document, MemoryVisibility.PRIVATE))
            }
            val writer = RecordingMemoryWriter()
            val service = service(sourceRecords, extractor, writer)
            val request = request("a")

            assertFailsWith<MemoryExtractionException> { service.execute(request) }
            assertEquals(
                SourceRecordAnalysisStatus.PENDING,
                sourceRecords.findBySource(request.source.source).single().analysisStatus,
            )

            val retried = service.execute(request)
            assertEquals(0, retried.importedRecordCount)
            assertEquals(1, retried.retriedRecordCount)
            assertEquals(0, retried.alreadyAnalyzedRecordCount)
            assertEquals(1, retried.privateMemoryCount)
            assertEquals(
                SourceRecordAnalysisStatus.ANALYZED,
                sourceRecords.findBySource(request.source.source).single().analysisStatus,
            )

            val duplicate = assertFailsWith<DuplicateSourceRecordsException> { service.execute(request) }
            assertEquals(1, duplicate.recordCount)
            assertEquals(2, extractor.documents.size)
            assertEquals(1, writer.memories.size)
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `partial new upload analyzes pending and new records together and reports counts`() = runBlocking {
        val databasePath = Files.createTempFile("analysis-partial-retry", ".db")
        try {
            val sourceRecords = SourceRecordRepositoryImpl(DatabaseFactory.init(databasePath.toString()))
            val source = SourceDescriptor("test", "source")
            val initial = sourceRecords.saveAll(source, listOf(draft("pending"), draft("analyzed")))
            sourceRecords.markAnalyzed(
                listOf(initial.recordsToAnalyze.single { it.deduplicationKey == "analyzed" }.id),
            )
            val extractor = RecordingExtractor { document ->
                listOf(
                    proposal(document.copy(records = listOf(document.records[0])), MemoryVisibility.PRIVATE),
                    proposal(document.copy(records = listOf(document.records[1])), MemoryVisibility.PUBLIC),
                )
            }
            val service = service(sourceRecords, extractor, RecordingMemoryWriter())

            val result = service.execute(
                MemoryAnalysisRequest(
                    userId = USER_ID.value,
                    source = SourceDocumentDraft(
                        source,
                        listOf(draft("pending"), draft("analyzed"), draft("new")),
                    ),
                ),
            )

            assertEquals(1, result.importedRecordCount)
            assertEquals(1, result.retriedRecordCount)
            assertEquals(1, result.alreadyAnalyzedRecordCount)
            assertEquals(1, result.privateMemoryCount)
            assertEquals(1, result.publicMemoryCount)
            assertEquals(listOf("pending", "new"), extractor.documents.single().records.map { it.deduplicationKey })
            assertTrue(sourceRecords.findBySource(source).all { it.analysisStatus == SourceRecordAnalysisStatus.ANALYZED })
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    private fun service(
        sourceRecords: SourceRecordRepositoryImpl,
        extractor: MemoryExtractor,
        writer: RecordingMemoryWriter,
    ) = MemoryAnalysisService(
        memoryExtractor = extractor,
        sourceRecords = sourceRecords,
        memorySaver = MemoryProposalsPersister(writer) { true },
        accessPolicy = HouseholdAccessPolicies.fixed(listOf(USER_ID)),
    )

    private fun request(key: String) = MemoryAnalysisRequest(
        userId = USER_ID.value,
        source = SourceDocumentDraft(SourceDescriptor("test", "source"), listOf(draft(key))),
    )

    private fun draft(key: String) = SourceRecordDraft(key, "content-$key")

    private fun proposal(document: SourceDocument, visibility: MemoryVisibility): MemoryProposal = MemoryProposal(
        content = "memory-${document.records.single().deduplicationKey}",
        subject = "subject",
        memoryType = MemoryType.REFERENCE,
        certainty = MemoryCertainty.OBSERVED,
        evidenceIds = document.records.map { it.id },
        visibility = visibility,
    )

    private class RecordingExtractor(
        private val block: suspend (SourceDocument) -> List<MemoryProposal>,
    ) : MemoryExtractor {
        val documents = mutableListOf<SourceDocument>()

        override suspend fun analyze(document: SourceDocument): List<MemoryProposal> {
            documents += document
            return block(document)
        }
    }

    private class RecordingMemoryWriter(
        private val failure: RuntimeException? = null,
    ) : MemoryWriter {
        val memories = mutableListOf<Memory>()

        override fun write(proposal: MemoryProposal, createdBy: UserId): Memory {
            failure?.let { throw it }
            return Memory(
                id = memories.size + 1,
                createdByUserId = createdBy.value,
                content = proposal.content,
                subject = proposal.subject,
                memoryType = proposal.memoryType,
                certainty = proposal.certainty,
                visibility = proposal.visibility,
                evidenceRefs = proposal.evidenceIds,
            ).also(memories::add)
        }
    }

    private companion object {
        val USER_ID = UserId("member-1")
    }
}
