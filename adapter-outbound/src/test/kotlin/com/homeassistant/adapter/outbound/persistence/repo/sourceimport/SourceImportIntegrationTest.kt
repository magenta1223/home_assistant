package com.homeassistant.adapter.outbound.persistence.repo.sourceimport

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.db.SqliteDatabases
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.memory.xmemory.episode.EpisodeExtractionQueueRepository
import com.homeassistant.adapter.outbound.persistence.repo.memory.xmemory.episode.EpisodeRepositoryImpl
import com.homeassistant.application.port.inbound.sourceimport.SourceImportRequest
import com.homeassistant.application.port.inbound.sourceimport.InvalidAllowedUsersException
import com.homeassistant.application.port.inbound.sourceimport.SourceAccessConflictException
import com.homeassistant.application.port.inbound.sourceimport.SourceAnalysisRoundResult
import com.homeassistant.application.port.outbound.memory.xmemory.episode.EpisodeExtractionClaim
import com.homeassistant.application.port.outbound.source.SourceRecordSaveResult
import com.homeassistant.application.port.outbound.source.SourceReferenceInterpretation
import com.homeassistant.application.port.outbound.source.SourceReferenceInterpretationResult
import com.homeassistant.application.port.outbound.source.SourceReferenceInterpreter
import com.homeassistant.application.usecase.sourceimport.AnalyzeSubmittedSourcesService
import com.homeassistant.application.usecase.sourceimport.SourceImportService
import com.homeassistant.domain.access.AccessControl
import com.homeassistant.domain.access.Visibility
import com.homeassistant.domain.source.SourceImportJobStatus
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceDocumentDraft
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.domain.source.SourceReferenceDraft
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceImportIntegrationTest {
    @Test
    fun `audience conflict reports the existing access scope`() = withDatabase { db ->
        val request = request("same", AccessControl.restricted(listOf(USER_ID)))
        assertIs<SourceRecordSaveResult.Saved>(SourceRecordRepositoryImpl(db).save(request.source.source, request.source.records, USER_ID, AccessControl.PUBLIC))

        val failure = assertFailsWith<SourceAccessConflictException> { submission(db).submit(request) }

        assertEquals(AccessControl.PUBLIC, failure.existingAccess)
    }

    @Test
    fun `unknown audience user is rejected before source data is stored`() = withDatabase { db ->
        val request = request("restricted", AccessControl.restricted(listOf(UserId("unknown-member"))))

        assertFailsWith<InvalidAllowedUsersException> { submission(db).submit(request) }

        assertEquals(1, resubmit(db, request).newRecords.size)
    }

    @Test
    fun `a completed job stores the submitter's records for extraction without extracting Episodes`() = withDatabase { db ->
        val submitted = submission(db).submit(request("a"))

        val round = worker(db).analyzeNextJob()

        assertEquals(SourceAnalysisRoundResult.Completed(submitted.jobId, 1), round)
        val claim = waitingRecords(db)
        assertEquals(USER_ID, claim.submittedBy)
        assertEquals(listOf("a"), claim.document.records.map { it.deduplicationKey })
        assertTrue(EpisodeRepositoryImpl(db).findVisibleTo(USER_ID).isEmpty())
    }

    @Test
    fun `renamed cumulative import stores and counts only the new records`() = withDatabase { db ->
        val original = SourceDescriptor("kakao", "original.txt")
        assertIs<SourceRecordSaveResult.Saved>(SourceRecordRepositoryImpl(db).save(original, listOf(draft("old-1"), draft("old-2")), USER_ID))

        val submitted = submission(db).submit(
            SourceImportRequest(USER_ID.value, SourceDocumentDraft(original.copy(name = "renamed.txt"), listOf(draft("old-1"), draft("old-2"), draft("new")))),
        )

        assertEquals(1, submitted.importedRecordCount)
        assertEquals(2, submitted.alreadyStoredRecordCount)
        assertEquals(Visibility.PUBLIC, submitted.visibility)
        assertEquals(SourceAnalysisRoundResult.Completed(submitted.jobId, 1), worker(db).analyzeNextJob())
    }

    @Test
    fun `only the submitter can see how the job went`() = withDatabase { db ->
        val submitted = submission(db).submit(request("a"))
        worker(db).analyzeNextJob()

        val report = submission(db).findJob(USER_ID.value, submitted.jobId)

        assertEquals(SourceImportJobStatus.COMPLETED, report?.status)
        assertEquals(1, report?.recordCount)
        assertNull(report?.lastError)
        assertNull(submission(db).findJob(OTHER_USER_ID.value, submitted.jobId))
    }

    @Test
    fun `reference interpretations are stored as records of the submitted source`() = withDatabase { db ->
        val reference = SourceReferenceDraft("manual.pdf", "application/pdf", "original".toByteArray())
        val interpreter = SourceReferenceInterpreter {
            SourceReferenceInterpretationResult.Interpreted(
                listOf(
                    SourceReferenceInterpretation("page-1", "첫 페이지 해석"),
                    SourceReferenceInterpretation("page-2", "둘째 페이지 해석"),
                ),
            )
        }
        val submitted = submission(db).submit(referenceRequest(reference))

        val round = worker(db, interpreter).analyzeNextJob()

        assertEquals(1, submitted.importedRecordCount)
        assertEquals(SourceAnalysisRoundResult.Completed(submitted.jobId, 3), round)
        val records = waitingRecords(db).document.records
        assertEquals(listOf("typed", "reference:${reference.sha256}:page-1", "reference:${reference.sha256}:page-2"), records.map { it.deduplicationKey })
        assertTrue(records[1].content.contains("첫 페이지 해석"))
        assertEquals("manual.pdf", records[1].reference?.fileName)
        assertEquals(AccessControl.restricted(listOf(USER_ID)), records[2].access)
    }

    @Test
    fun `a rejected reference fails the job and stores no reference record`() = withDatabase { db ->
        val reference = SourceReferenceDraft("broken.pdf", "application/pdf", "broken".toByteArray())
        val interpreter = SourceReferenceInterpreter { SourceReferenceInterpretationResult.Rejected("PDF reference could not be opened") }
        val submitted = submission(db).submit(referenceRequest(reference))

        val round = worker(db, interpreter).analyzeNextJob()

        assertEquals(SourceAnalysisRoundResult.Failed(submitted.jobId, "PDF reference could not be opened"), round)
        assertEquals(listOf("typed"), waitingRecords(db).document.records.map { it.deduplicationKey })
    }

    private fun submission(db: SqliteDatabases) = SourceImportService(
        sourceRecords = SourceRecordRepositoryImpl(db),
        jobs = SourceImportJobQueueRepository(db),
        accessPolicy = { it == USER_ID || it == OTHER_USER_ID },
    )

    private fun worker(
        db: SqliteDatabases,
        interpreter: SourceReferenceInterpreter = SourceReferenceInterpreter { error("no reference expected") },
    ) = AnalyzeSubmittedSourcesService(
        jobs = SourceImportJobQueueRepository(db),
        sourceRecords = SourceRecordRepositoryImpl(db),
        referenceInterpreter = interpreter,
        retryDelayMillis = 0,
    )

    /** The stored records waiting for xMemory extraction, claimed the way the memory update claims them. */
    private fun waitingRecords(db: SqliteDatabases): EpisodeExtractionClaim =
        assertNotNull(EpisodeExtractionQueueRepository(db).claimNext(now = 0, retryBefore = 0, staleProcessingBefore = -1, maxAttempts = 5))

    private fun request(key: String, access: AccessControl = AccessControl.PUBLIC) = SourceImportRequest(
        userId = USER_ID.value,
        source = SourceDocumentDraft(SourceDescriptor("test", "source"), listOf(draft(key))),
        access = access,
    )

    private fun referenceRequest(reference: SourceReferenceDraft) = SourceImportRequest(
        userId = USER_ID.value,
        source = SourceDocumentDraft(
            SourceDescriptor("text", "manual"),
            listOf(SourceRecordDraft("typed", "직접 입력한 내용", recordedAt = 0)),
            reference,
        ),
        access = AccessControl.restricted(listOf(USER_ID)),
    )

    private fun draft(key: String) = SourceRecordDraft(key, "content-$key")

    /** Submits [request]'s records again; the result tells whether they were stored before. */
    private fun resubmit(db: SqliteDatabases, request: SourceImportRequest) =
        assertIs<SourceRecordSaveResult.Saved>(SourceRecordRepositoryImpl(db).save(request.source.source, request.source.records, USER_ID, request.access))

    private fun withDatabase(block: suspend (SqliteDatabases) -> Unit) = runBlocking {
        val path = Files.createTempFile("source-import", ".db")
        try { block(DatabaseFactory.init(path.toString())) }
        finally { Files.deleteIfExists(path) }
    }

    private companion object {
        val USER_ID = UserId("member-1")
        val OTHER_USER_ID = UserId("member-2")
    }
}
