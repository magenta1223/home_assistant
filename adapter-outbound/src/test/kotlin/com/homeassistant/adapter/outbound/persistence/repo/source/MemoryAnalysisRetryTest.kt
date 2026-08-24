package com.homeassistant.adapter.outbound.persistence.repo.source

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.repo.memory.MemoryRepository
import com.homeassistant.application.port.input.memory.analysis.DuplicateSourceRecordsException
import com.homeassistant.application.port.input.memory.analysis.ConflictingSourceAudienceException
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisUnavailableException
import com.homeassistant.application.port.input.memory.analysis.InvalidMemoryAudienceException
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisRequest
import com.homeassistant.application.usecase.memory.analysis.MemoryAnalysisService
import com.homeassistant.application.port.output.memory.analysis.MemoryExtractor
import com.homeassistant.application.usecase.memory.write.MemoryProposalsPersister
import com.homeassistant.application.port.output.memory.write.CanonicalMemoryBatchWriter
import com.homeassistant.application.port.output.memory.write.IdempotentMemoryProposal
import com.homeassistant.domain.identity.UserAccessPolicies
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryAccess
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
import org.jetbrains.exposed.sql.Database
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MemoryAnalysisRetryTest {
    @Test
    fun `audience conflict reports the existing access scope`() = runBlocking {
        val databasePath = Files.createTempFile("analysis-audience-conflict", ".db")
        try {
            val db = DatabaseFactory.init(databasePath.toString())
            val sourceRecords = SourceRecordRepositoryImpl(db)
            val request = request("same", MemoryAccess.restricted(listOf(USER_ID)))
            sourceRecords.saveAll(request.source.source, request.source.records, MemoryAccess.PUBLIC)

            val failure = assertFailsWith<ConflictingSourceAudienceException> {
                service(sourceRecords, db, RecordingExtractor { emptyList() }, RecordingMemoryWriter())
                    .execute(request)
            }

            assertEquals(MemoryAccess.PUBLIC, failure.existingAccess)
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `unknown audience user is rejected before source data is stored`() = runBlocking {
        val databasePath = Files.createTempFile("analysis-invalid-audience", ".db")
        try {
            val db = DatabaseFactory.init(databasePath.toString())
            val sourceRecords = SourceRecordRepositoryImpl(db)
            val request = request(
                "restricted",
                MemoryAccess.restricted(listOf(UserId("unknown-member"))),
            )

            assertFailsWith<InvalidMemoryAudienceException> {
                service(sourceRecords, db, RecordingExtractor { emptyList() }, RecordingMemoryWriter())
                    .execute(request)
            }

            assertTrue(sourceRecords.findBySource(request.source.source).isEmpty())
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `renamed cumulative import passes analyzed history as context for its new tail`() = runBlocking {
        val databasePath = Files.createTempFile("analysis-renamed-context", ".db")
        try {
            val db = DatabaseFactory.init(databasePath.toString())
            val sourceRecords = SourceRecordRepositoryImpl(db)
            val original = SourceDescriptor("kakao", "original.txt")
            val initial = sourceRecords.saveAll(original, listOf(draft("old-1"), draft("old-2")))
            markAnalyzed(db, initial.recordsToAnalyze.map { it.id })
            val extractor = RecordingExtractor { document ->
                listOf(proposal(document))
            }
            val service = service(sourceRecords, db, extractor, RecordingMemoryWriter())
            val renamed = original.copy(name = "renamed.txt")

            val result = service.execute(
                MemoryAnalysisRequest(
                    userId = USER_ID.value,
                    source = SourceDocumentDraft(
                        renamed,
                        listOf(draft("old-1"), draft("old-2"), draft("new")),
                    ),
                ),
            )

            assertEquals(1, result.importedRecordCount)
            assertEquals(2, result.alreadyAnalyzedRecordCount)
            assertEquals(listOf("old-1", "old-2"), extractor.documents.single().contextRecords.map { it.deduplicationKey })
            assertEquals(listOf("new"), extractor.documents.single().records.map { it.deduplicationKey })
            assertEquals(
                SourceRecordAnalysisStatus.ANALYZED,
                sourceRecords.findBySource(renamed).single().analysisStatus,
            )
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `same source name with a different conversation does not inherit database context`() = runBlocking {
        val databasePath = Files.createTempFile("analysis-same-name-isolation", ".db")
        try {
            val db = DatabaseFactory.init(databasePath.toString())
            val sourceRecords = SourceRecordRepositoryImpl(db)
            val source = SourceDescriptor("kakao", "KakaoTalkChats.txt")
            val unrelated = sourceRecords.saveAll(source, listOf(draft("other-conversation")))
            markAnalyzed(db, unrelated.recordsToAnalyze.map { it.id })
            val extractor = RecordingExtractor { emptyList() }
            val service = service(sourceRecords, db, extractor, RecordingMemoryWriter())

            service.execute(
                MemoryAnalysisRequest(
                    userId = USER_ID.value,
                    source = SourceDocumentDraft(source, listOf(draft("new-conversation"))),
                ),
            )

            assertTrue(extractor.documents.single().contextRecords.isEmpty())
            assertEquals(listOf("new-conversation"), extractor.documents.single().records.map { it.deduplicationKey })
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `memory persistence failure leaves source pending`() = runBlocking {
        val databasePath = Files.createTempFile("analysis-persistence-failure", ".db")
        try {
            val db = DatabaseFactory.init(databasePath.toString())
            val sourceRecords = SourceRecordRepositoryImpl(db)
            val extractor = RecordingExtractor { document ->
                listOf(proposal(document))
            }
            val failure = IllegalStateException("persistence failed")
            val service = service(sourceRecords, db, extractor, RecordingMemoryWriter(failure))
            val request = request("a")

            val unavailable = assertFailsWith<MemoryAnalysisUnavailableException> { service.execute(request) }
            assertSame(failure, unavailable.cause)
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
            val db = DatabaseFactory.init(databasePath.toString())
            val sourceRecords = SourceRecordRepositoryImpl(db)
            var shouldFail = true
            val extractor = RecordingExtractor { document ->
                if (shouldFail) {
                    shouldFail = false
                    error("temporary failure")
                }
                listOf(proposal(document))
            }
            val writer = RecordingMemoryWriter()
            val service = service(sourceRecords, db, extractor, writer)
            val request = request("a", MemoryAccess.restricted(listOf(USER_ID)))

            assertFailsWith<MemoryAnalysisUnavailableException> { service.execute(request) }
            assertEquals(
                SourceRecordAnalysisStatus.PENDING,
                sourceRecords.findBySource(request.source.source).single().analysisStatus,
            )

            val retried = service.execute(request)
            assertEquals(0, retried.importedRecordCount)
            assertEquals(1, retried.retriedRecordCount)
            assertEquals(0, retried.alreadyAnalyzedRecordCount)
            assertEquals(1, retried.memoryCount)
            assertEquals(MemoryVisibility.RESTRICTED, retried.visibility)
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
            val db = DatabaseFactory.init(databasePath.toString())
            val sourceRecords = SourceRecordRepositoryImpl(db)
            val source = SourceDescriptor("test", "source")
            val initial = sourceRecords.saveAll(source, listOf(draft("pending"), draft("analyzed")))
            markAnalyzed(db,
                listOf(initial.recordsToAnalyze.single { it.deduplicationKey == "analyzed" }.id),
            )
            val extractor = RecordingExtractor { document ->
                listOf(
                    proposal(document.copy(records = listOf(document.records[0]))),
                    proposal(document.copy(records = listOf(document.records[1]))),
                )
            }
            val service = service(sourceRecords, db, extractor, RecordingMemoryWriter())

            val result = service.execute(
                MemoryAnalysisRequest(
                    userId = USER_ID.value,
                    source = SourceDocumentDraft(
                        source,
                        listOf(draft("analyzed"), draft("pending"), draft("new")),
                    ),
                ),
            )

            assertEquals(1, result.importedRecordCount)
            assertEquals(1, result.retriedRecordCount)
            assertEquals(1, result.alreadyAnalyzedRecordCount)
            assertEquals(2, result.memoryCount)
            assertEquals(MemoryVisibility.PUBLIC, result.visibility)
            assertEquals(listOf("analyzed"), extractor.documents.single().contextRecords.map { it.deduplicationKey })
            assertEquals(listOf("pending", "new"), extractor.documents.single().records.map { it.deduplicationKey })
            assertTrue(sourceRecords.findBySource(source).all { it.analysisStatus == SourceRecordAnalysisStatus.ANALYZED })
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `analyzed records after the first pending record are not used as future context`() = runBlocking {
        val databasePath = Files.createTempFile("analysis-pending-context-boundary", ".db")
        try {
            val db = DatabaseFactory.init(databasePath.toString())
            val sourceRecords = SourceRecordRepositoryImpl(db)
            val source = SourceDescriptor("kakao", "conversation.txt")
            val initial = sourceRecords.saveAll(
                source,
                listOf(draft("prefix"), draft("pending"), draft("future-analyzed")),
            )
            markAnalyzed(db,
                initial.recordsToAnalyze
                    .filter { it.deduplicationKey != "pending" }
                    .map { it.id },
            )
            val extractor = RecordingExtractor { emptyList() }
            val service = service(sourceRecords, db, extractor, RecordingMemoryWriter())

            service.execute(
                MemoryAnalysisRequest(
                    userId = USER_ID.value,
                    source = SourceDocumentDraft(
                        source,
                        listOf(draft("prefix"), draft("pending"), draft("future-analyzed"), draft("new")),
                    ),
                ),
            )

            val document = extractor.documents.single()
            assertEquals(listOf("prefix"), document.contextRecords.map { it.deduplicationKey })
            assertEquals(listOf("pending", "new"), document.records.map { it.deduplicationKey })
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    private fun service(
        sourceRecords: SourceRecordRepositoryImpl,
        db: Database,
        extractor: MemoryExtractor,
        writer: RecordingMemoryWriter,
    ) = MemoryAnalysisService(
        memoryExtractor = extractor,
        sourceRecords = sourceRecords,
        memorySaver = MemoryProposalsPersister { createdBy, proposals, recordIds ->
            writer.commit(createdBy, proposals, recordIds).also {
                markAnalyzed(db, recordIds)
            }
        },
        accessPolicy = UserAccessPolicies.fixed(listOf(USER_ID)),
    )

    private fun request(key: String, access: MemoryAccess = MemoryAccess.PUBLIC) = MemoryAnalysisRequest(
        userId = USER_ID.value,
        source = SourceDocumentDraft(SourceDescriptor("test", "source"), listOf(draft(key))),
        access = access,
    )

    private fun draft(key: String) = SourceRecordDraft(key, "content-$key")

    private fun markAnalyzed(db: Database, recordIds: Collection<Int>) {
        MemoryRepository(db).commit(USER_ID, emptyList(), recordIds)
    }

    private fun proposal(document: SourceDocument): MemoryProposal = MemoryProposal(
        content = "memory-${document.records.single().deduplicationKey}",
        subject = "subject",
        memoryType = MemoryType.REFERENCE,
        certainty = MemoryCertainty.OBSERVED,
        evidenceIds = document.records.map { it.id },
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
    ) : CanonicalMemoryBatchWriter {
        val memories = mutableListOf<Memory>()

        override fun commit(
            createdBy: UserId,
            proposals: List<IdempotentMemoryProposal>,
            analyzedSourceRecordIds: Collection<Int>,
        ): List<Memory> {
            failure?.let { throw it }
            val saved = proposals.map { item ->
                val proposal = item.proposal
                Memory(
                    id = memories.size + 1,
                    createdByUserId = createdBy.value,
                    content = proposal.content,
                    subject = proposal.subject,
                    memoryType = proposal.memoryType,
                    certainty = proposal.certainty,
                    visibility = MemoryVisibility.PUBLIC,
                    evidenceRefs = proposal.evidenceIds,
                    createdAt = (memories.size + 1) * 1_000L,
                ).also(memories::add)
            }
            return saved
        }
    }

    private companion object {
        val USER_ID = UserId("member-1")
    }
}
