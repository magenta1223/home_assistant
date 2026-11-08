package com.homeassistant.adapter.outbound.persistence.repo.source

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.repo.episode.EpisodeAnalysisJobQueueRepository
import com.homeassistant.adapter.outbound.persistence.repo.episode.EpisodeRepositoryImpl
import com.homeassistant.application.port.inbound.memoryextraction.DuplicateSourceRecordsException
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysisRequest
import com.homeassistant.application.port.inbound.memoryextraction.InvalidAllowedUsersException
import com.homeassistant.application.port.inbound.memoryextraction.SourceAccessConflictException
import com.homeassistant.application.port.inbound.memoryextraction.SourceAnalysisRoundResult
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeBatch
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeExtractor
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeRepository
import com.homeassistant.application.port.outbound.source.SourceRecordSaveResult
import com.homeassistant.application.port.outbound.source.SourceReferenceInterpretation
import com.homeassistant.application.port.outbound.source.SourceReferenceInterpretationResult
import com.homeassistant.application.port.outbound.source.SourceReferenceInterpreter
import com.homeassistant.application.usecase.memoryextraction.episode.AnalyzeSubmittedSourcesService
import com.homeassistant.application.usecase.memoryextraction.episode.EpisodeAnalysisService
import com.homeassistant.domain.access.AccessControl
import com.homeassistant.domain.access.Visibility
import com.homeassistant.domain.episode.Episode
import com.homeassistant.domain.episode.EpisodeAnalysisJobStatus
import com.homeassistant.domain.episode.EpisodeProposal
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.source.SourceDocumentDraft
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.domain.source.SourceReferenceDraft
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpisodeAnalysisRetryTest {
    @Test
    fun `audience conflict reports the existing access scope`() = withDatabase { db ->
        val request = request("same", AccessControl.restricted(listOf(USER_ID)))
        assertIs<SourceRecordSaveResult.Saved>(SourceRecordRepositoryImpl(db).save(request.source.source, request.source.records, AccessControl.PUBLIC))

        val failure = assertFailsWith<SourceAccessConflictException> { submission(db).submit(request) }

        assertEquals(AccessControl.PUBLIC, failure.existingAccess)
    }

    @Test
    fun `unknown audience user is rejected before source data is stored`() = withDatabase { db ->
        val request = request("restricted", AccessControl.restricted(listOf(UserId("unknown-member"))))

        assertFailsWith<InvalidAllowedUsersException> { submission(db).submit(request) }

        assertEquals(1, resubmit(db, request).importedRecordCount)
    }

    @Test
    fun `renamed cumulative import analyzes only the new records`() = withDatabase { db ->
        val original = SourceDescriptor("kakao", "original.txt")
        val initial = assertIs<SourceRecordSaveResult.Saved>(SourceRecordRepositoryImpl(db).save(original, listOf(draft("old-1"), draft("old-2"))))
        markAnalyzed(db, initial.recordsToAnalyze.map { it.id })
        val extractor = RecordingExtractor { document -> listOf(proposal(document)) }
        val renamed = original.copy(name = "renamed.txt")

        val submitted = submission(db).submit(
            EpisodeAnalysisRequest(USER_ID.value, SourceDocumentDraft(renamed, listOf(draft("old-1"), draft("old-2"), draft("new")))),
        )
        val round = worker(db, extractor).analyzeNextJob()

        assertEquals(1, submitted.importedRecordCount)
        assertEquals(2, submitted.alreadyAnalyzedRecordCount)
        assertEquals(SourceAnalysisRoundResult.Completed(submitted.jobId, 1), round)
        assertEquals(listOf("new"), extractor.documents.single().records.map { it.deduplicationKey })
    }

    @Test
    fun `episode persistence failure fails the job and leaves the source pending`() = withDatabase { db ->
        val extractor = RecordingExtractor { document -> listOf(proposal(document)) }
        val request = request("a")
        val submitted = submission(db).submit(request)

        val round = worker(db, extractor, RecordingEpisodeRepository(IllegalStateException("persistence failed"))).analyzeNextJob()

        assertEquals(SourceAnalysisRoundResult.Failed(submitted.jobId, "persistence failed"), round)
        val report = submission(db).findJob(USER_ID.value, submitted.jobId)
        assertEquals(EpisodeAnalysisJobStatus.FAILED, report?.status)
        assertEquals("persistence failed", report?.lastError)
        assertEquals(1, resubmit(db, request).retriedRecordCount)
    }

    @Test
    fun `extractor failure is retried by the same job and a later upload is duplicate`() = withDatabase { db ->
        var shouldFail = true
        val extractor = RecordingExtractor { document ->
            if (shouldFail) {
                shouldFail = false
                error("temporary failure")
            }
            listOf(proposal(document))
        }
        val writer = RecordingEpisodeRepository()
        val worker = worker(db, extractor, writer)
        val request = request("a", AccessControl.restricted(listOf(USER_ID)))
        val submitted = submission(db).submit(request)

        assertEquals(SourceAnalysisRoundResult.Failed(submitted.jobId, "temporary failure"), worker.analyzeNextJob())
        assertEquals(SourceAnalysisRoundResult.Completed(submitted.jobId, 1), worker.analyzeNextJob())
        assertEquals(SourceAnalysisRoundResult.Idle, worker.analyzeNextJob())

        val duplicate = assertFailsWith<DuplicateSourceRecordsException> { submission(db).submit(request) }
        assertEquals(1, duplicate.recordCount)
        assertEquals(2, extractor.documents.size)
        assertEquals(1, writer.episodes.size)
    }

    @Test
    fun `partial new upload analyzes pending and new records together and reports counts`() = withDatabase { db ->
        val source = SourceDescriptor("test", "source")
        val initial = assertIs<SourceRecordSaveResult.Saved>(SourceRecordRepositoryImpl(db).save(source, listOf(draft("pending"), draft("analyzed"))))
        markAnalyzed(db, listOf(initial.recordsToAnalyze.single { it.deduplicationKey == "analyzed" }.id))
        val extractor = RecordingExtractor { document ->
            document.records.map { record -> proposal(document.copy(records = listOf(record))) }
        }

        val submitted = submission(db).submit(
            EpisodeAnalysisRequest(USER_ID.value, SourceDocumentDraft(source, listOf(draft("analyzed"), draft("pending"), draft("new")))),
        )
        val round = worker(db, extractor).analyzeNextJob()

        assertEquals(1, submitted.importedRecordCount)
        assertEquals(1, submitted.retriedRecordCount)
        assertEquals(1, submitted.alreadyAnalyzedRecordCount)
        assertEquals(Visibility.PUBLIC, submitted.visibility)
        assertEquals(SourceAnalysisRoundResult.Completed(submitted.jobId, 2), round)
        assertEquals(listOf("pending", "new"), extractor.documents.single().records.map { it.deduplicationKey })
    }

    @Test
    fun `only the submitter can see how the job went`() = withDatabase { db ->
        val submitted = submission(db).submit(request("a"))
        worker(db, RecordingExtractor { document -> listOf(proposal(document)) }).analyzeNextJob()

        val report = submission(db).findJob(USER_ID.value, submitted.jobId)

        assertEquals(EpisodeAnalysisJobStatus.COMPLETED, report?.status)
        assertEquals(1, report?.episodeCount)
        assertNull(report?.lastError)
        assertNull(submission(db).findJob(OTHER_USER_ID.value, submitted.jobId))
    }

    @Test
    fun `a job whose records were analyzed by an earlier job completes with no episodes`() = withDatabase { db ->
        val extractor = RecordingExtractor { document -> listOf(proposal(document)) }
        val first = submission(db).submit(request("a"))
        val second = submission(db).submit(request("a"))
        val worker = worker(db, extractor)

        assertEquals(SourceAnalysisRoundResult.Completed(first.jobId, 1), worker.analyzeNextJob())
        assertEquals(SourceAnalysisRoundResult.Completed(second.jobId, 0), worker.analyzeNextJob())
        assertEquals(1, extractor.documents.size)
    }

    @Test
    fun `reference interpretations become evidence records analyzed after the typed records`() = withDatabase { db ->
        val extractor = RecordingExtractor { emptyList() }
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

        val round = worker(db, extractor, interpreter = interpreter).analyzeNextJob()

        assertEquals(1, submitted.importedRecordCount)
        assertEquals(SourceAnalysisRoundResult.Completed(submitted.jobId, 0), round)
        val records = extractor.documents.single().records
        assertEquals(listOf("typed", "reference:${reference.sha256}:page-1", "reference:${reference.sha256}:page-2"), records.map { it.deduplicationKey })
        assertTrue(records[1].content.contains("첫 페이지 해석"))
        assertEquals("manual.pdf", records[1].reference?.fileName)
        assertEquals(AccessControl.restricted(listOf(USER_ID)), records[2].access)
    }

    @Test
    fun `a rejected reference fails the job before any extraction`() = withDatabase { db ->
        val extractor = RecordingExtractor { document -> listOf(proposal(document)) }
        val reference = SourceReferenceDraft("broken.pdf", "application/pdf", "broken".toByteArray())
        val interpreter = SourceReferenceInterpreter { SourceReferenceInterpretationResult.Rejected("PDF reference could not be opened") }
        val request = referenceRequest(reference)
        val submitted = submission(db).submit(request)

        val round = worker(db, extractor, interpreter = interpreter).analyzeNextJob()

        assertEquals(SourceAnalysisRoundResult.Failed(submitted.jobId, "PDF reference could not be opened"), round)
        assertTrue(extractor.documents.isEmpty())
        assertEquals(1, resubmit(db, request).retriedRecordCount)
    }

    private fun submission(db: Database) = EpisodeAnalysisService(
        sourceRecords = SourceRecordRepositoryImpl(db),
        jobs = EpisodeAnalysisJobQueueRepository(db),
        accessPolicy = { it == USER_ID || it == OTHER_USER_ID },
    )

    private fun worker(
        db: Database,
        extractor: EpisodeExtractor,
        writer: RecordingEpisodeRepository = RecordingEpisodeRepository(),
        interpreter: SourceReferenceInterpreter = SourceReferenceInterpreter { error("no reference expected") },
    ) = AnalyzeSubmittedSourcesService(
        jobs = EpisodeAnalysisJobQueueRepository(db),
        sourceRecords = SourceRecordRepositoryImpl(db),
        episodes = object : EpisodeRepository by writer {
            override fun save(batch: EpisodeBatch) = writer.save(batch).also {
                markAnalyzed(db, batch.analyzedSourceRecordIds)
            }
        },
        episodeExtractor = extractor,
        referenceInterpreter = interpreter,
        retryDelayMillis = 0,
    )

    private fun request(key: String, access: AccessControl = AccessControl.PUBLIC) = EpisodeAnalysisRequest(
        userId = USER_ID.value,
        source = SourceDocumentDraft(SourceDescriptor("test", "source"), listOf(draft(key))),
        access = access,
    )

    private fun referenceRequest(reference: SourceReferenceDraft) = EpisodeAnalysisRequest(
        userId = USER_ID.value,
        source = SourceDocumentDraft(
            SourceDescriptor("text", "manual"),
            listOf(SourceRecordDraft("typed", "직접 입력한 내용")),
            reference,
        ),
        access = AccessControl.restricted(listOf(USER_ID)),
    )

    private fun draft(key: String) = SourceRecordDraft(key, "content-$key")

    /** Submits [request]'s records again; the counts tell whether they are stored, pending or analyzed. */
    private fun resubmit(db: Database, request: EpisodeAnalysisRequest) =
        assertIs<SourceRecordSaveResult.Saved>(SourceRecordRepositoryImpl(db).save(request.source.source, request.source.records, request.access))

    private fun markAnalyzed(db: Database, recordIds: Collection<Int>) {
        EpisodeRepositoryImpl(db).save(EpisodeBatch(USER_ID, emptyList(), recordIds))
    }

    private fun proposal(document: SourceDocument): EpisodeProposal = EpisodeProposal(
        title = "title",
        content = "episode-${document.records.joinToString { it.deduplicationKey }}",
        evidenceIds = document.records.map { it.id },
    )

    private fun withDatabase(block: suspend (Database) -> Unit) = runBlocking {
        val path = Files.createTempFile("episode-analysis-retry", ".db")
        try { block(DatabaseFactory.init(path.toString())) }
        finally { Files.deleteIfExists(path) }
    }

    private class RecordingExtractor(
        private val block: suspend (SourceDocument) -> List<EpisodeProposal>,
    ) : EpisodeExtractor {
        val documents = mutableListOf<SourceDocument>()

        override suspend fun analyze(document: SourceDocument): List<EpisodeProposal> {
            documents += document
            return block(document)
        }
    }

    private class RecordingEpisodeRepository(
        private val failure: RuntimeException? = null,
    ) : EpisodeRepository {
        val episodes = mutableListOf<Episode>()

        override fun findVisibleTo(userId: UserId) = episodes.toList()

        override fun save(batch: EpisodeBatch): List<Episode> {
            val (createdBy, proposals) = batch
            failure?.let { throw it }
            return proposals.map { item ->
                Episode(
                    id = episodes.size + 1,
                    createdByUserId = createdBy.value,
                    title = item.title,
                    content = item.content,
                    access = AccessControl.PUBLIC,
                    evidenceRefs = item.evidenceIds,
                    createdAt = (episodes.size + 1) * 1_000L,
                ).also(episodes::add)
            }
        }
    }

    private companion object {
        val USER_ID = UserId("member-1")
        val OTHER_USER_ID = UserId("member-2")
    }
}
