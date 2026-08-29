package com.homeassistant.application.usecase.memory.analysis

import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisRequest
import com.homeassistant.application.port.output.memory.analysis.MemoryExtractor
import com.homeassistant.application.port.output.memory.write.CanonicalMemoryBatchWriter
import com.homeassistant.application.port.output.source.SourceReferenceInterpretation
import com.homeassistant.application.usecase.memory.write.MemoryProposalsPersister
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.memory.MemoryAccess
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.source.SourceDocumentDraft
import com.homeassistant.domain.source.SourceRecord
import com.homeassistant.domain.source.SourceRecordAnalysisStatus
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.domain.source.SourceRecordRepository
import com.homeassistant.domain.source.SourceRecordSaveResult
import com.homeassistant.domain.source.SourceReference
import com.homeassistant.domain.source.SourceReferenceDraft
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MemoryAnalysisReferenceTest {
    @Test
    fun `reference interpretations become evidence records linked to the original`() = runBlocking {
        val repository = RecordingSourceRepository()
        val extractor = RecordingExtractor()
        val reference = SourceReferenceDraft("manual.pdf", "application/pdf", "original".toByteArray())
        val service = MemoryAnalysisService(
            memoryExtractor = extractor,
            sourceRecords = repository,
            memorySaver = MemoryProposalsPersister(CanonicalMemoryBatchWriter { _, _, _ -> emptyList() }),
            accessPolicy = UserAccessPolicy { true },
            referenceInterpreter = {
                listOf(
                    SourceReferenceInterpretation("page-1", "첫 페이지 해석"),
                    SourceReferenceInterpretation("page-2", "둘째 페이지 해석"),
                )
            },
        )

        val result = service.execute(
            MemoryAnalysisRequest(
                userId = "member-1",
                source = SourceDocumentDraft(
                    source = SourceDescriptor("text", "manual"),
                    records = listOf(SourceRecordDraft("typed", "직접 입력한 내용")),
                    reference = reference,
                ),
            ),
        )

        assertEquals(3, result.importedRecordCount)
        assertEquals(listOf("typed", "reference:${reference.sha256}:page-1", "reference:${reference.sha256}:page-2"), repository.drafts.map { it.deduplicationKey })
        assertSame(reference, repository.drafts[1].reference)
        assertSame(reference, repository.drafts[2].reference)
        assertTrue(extractor.document.records[1].content.contains("첫 페이지 해석"))
        assertEquals("manual.pdf", extractor.document.records[1].reference?.fileName)
    }

    private class RecordingSourceRepository : SourceRecordRepository {
        lateinit var drafts: List<SourceRecordDraft>

        override fun saveAll(
            source: SourceDescriptor,
            records: List<SourceRecordDraft>,
            access: MemoryAccess,
        ): SourceRecordSaveResult {
            drafts = records
            val saved = records.mapIndexed { index, draft ->
                SourceRecord(
                    id = index + 1,
                    deduplicationKey = draft.deduplicationKey,
                    content = draft.content,
                    analysisStatus = SourceRecordAnalysisStatus.PENDING,
                    access = access,
                    reference = draft.reference?.let {
                        SourceReference(7, it.fileName, it.mediaType, it.size, it.sha256)
                    },
                )
            }
            return SourceRecordSaveResult(saved, emptyList(), saved.size, 0, 0)
        }

        override fun findBySource(source: SourceDescriptor): List<SourceRecord> = emptyList()
    }

    private class RecordingExtractor : MemoryExtractor {
        lateinit var document: SourceDocument

        override suspend fun analyze(document: SourceDocument) = emptyList<com.homeassistant.domain.memory.MemoryProposal>()
            .also { this.document = document }
    }
}
