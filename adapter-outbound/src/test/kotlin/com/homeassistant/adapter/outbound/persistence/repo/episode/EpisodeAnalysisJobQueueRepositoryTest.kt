package com.homeassistant.adapter.outbound.persistence.repo.episode

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeAnalysisSubmission
import com.homeassistant.application.port.outbound.source.SourceRecordSaveResult
import com.homeassistant.domain.access.AccessControl
import com.homeassistant.domain.episode.EpisodeAnalysisJobStatus
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.domain.source.SourceReferenceDraft
import org.jetbrains.exposed.sql.Database
import java.nio.file.Files
import kotlin.test.*

class EpisodeAnalysisJobQueueRepositoryTest {
    private val owner = UserId("owner")
    private val source = SourceDescriptor("test", "chat")
    private val access = AccessControl.restricted(listOf(owner, UserId("member")))

    @Test fun `an enqueued job keeps its records, reference and audience and is found by its requester`() = withDatabase { db ->
        val jobs = EpisodeAnalysisJobQueueRepository(db)
        val submission = submissionWithReference(db)
        val enqueued = jobs.enqueue(submission, now = 0)
        assertEquals(submission, enqueued.submission)
        assertEquals(EpisodeAnalysisJobStatus.PENDING, enqueued.status)
        assertEquals(0, enqueued.attempt)
        assertNull(enqueued.episodeCount)
        assertNull(enqueued.lastError)
        assertEquals(enqueued, jobs.findRequestedBy(enqueued.id, owner))
    }

    @Test fun `another user cannot find the job`() = withDatabase { db ->
        val jobs = EpisodeAnalysisJobQueueRepository(db)
        val enqueued = jobs.enqueue(submission(db, "a"), now = 0)
        assertNull(jobs.findRequestedBy(enqueued.id, UserId("other")))
    }

    @Test fun `jobs are claimed one at a time, oldest first`() = withDatabase { db ->
        val jobs = EpisodeAnalysisJobQueueRepository(db)
        val first = jobs.enqueue(submission(db, "a"), now = 0)
        val second = jobs.enqueue(submission(db, "b"), now = 0)
        val claimed = assertNotNull(jobs.claimNext(now = 0, retryBefore = 0, staleProcessingBefore = -1, maxAttempts = 5))
        assertEquals(first.id, claimed.id)
        assertEquals(EpisodeAnalysisJobStatus.PROCESSING, claimed.status)
        assertEquals(1, claimed.attempt)
        assertEquals(second.id, jobs.claimNext(now = 0, retryBefore = 0, staleProcessingBefore = -1, maxAttempts = 5)?.id)
        assertNull(jobs.claimNext(now = 0, retryBefore = 0, staleProcessingBefore = -1, maxAttempts = 5))
    }

    @Test fun `a failed job waits for the retry delay and stops after the attempt limit`() = withDatabase { db ->
        val jobs = EpisodeAnalysisJobQueueRepository(db)
        val enqueued = jobs.enqueue(submission(db, "a"), now = 0)
        repeat(2) { attempt ->
            val claimed = assertNotNull(jobs.claimNext(now = 1_000L * attempt, retryBefore = 1_000L * attempt, staleProcessingBefore = 0, maxAttempts = 2))
            assertTrue(jobs.markFailed(claimed.id, claimed.attempt, "analysis failed", now = 1_000L * attempt))
            assertNull(jobs.claimNext(now = 1_000L * attempt, retryBefore = 1_000L * attempt - 1, staleProcessingBefore = 0, maxAttempts = 2))
        }
        assertNull(jobs.claimNext(now = 10_000L, retryBefore = 10_000L, staleProcessingBefore = 0, maxAttempts = 2))
        val failed = assertNotNull(jobs.findRequestedBy(enqueued.id, owner))
        assertEquals(EpisodeAnalysisJobStatus.FAILED, failed.status)
        assertEquals(2, failed.attempt)
        assertEquals("analysis failed", failed.lastError)
    }

    @Test fun `a stale claim is reclaimed and can no longer complete`() = withDatabase { db ->
        val jobs = EpisodeAnalysisJobQueueRepository(db)
        jobs.enqueue(submission(db, "a"), now = 0)
        val stale = assertNotNull(jobs.claimNext(now = 0, retryBefore = 0, staleProcessingBefore = -1, maxAttempts = 5))
        assertNull(jobs.claimNext(now = 500, retryBefore = 0, staleProcessingBefore = -1, maxAttempts = 5))
        val fresh = assertNotNull(jobs.claimNext(now = 1_000, retryBefore = 0, staleProcessingBefore = 0, maxAttempts = 5))
        assertEquals(stale.id, fresh.id)
        assertEquals(2, fresh.attempt)
        assertFalse(jobs.markCompleted(stale.id, stale.attempt, episodeCount = 1, now = 1_000))
        assertTrue(jobs.markCompleted(fresh.id, fresh.attempt, episodeCount = 1, now = 1_000))
    }

    @Test fun `a completed job reports its episode count and clears the last error`() = withDatabase { db ->
        val jobs = EpisodeAnalysisJobQueueRepository(db)
        val enqueued = jobs.enqueue(submission(db, "a"), now = 0)
        val failed = assertNotNull(jobs.claimNext(now = 0, retryBefore = 0, staleProcessingBefore = -1, maxAttempts = 5))
        jobs.markFailed(failed.id, failed.attempt, "analysis failed", now = 0)
        val retried = assertNotNull(jobs.claimNext(now = 1_000, retryBefore = 1_000, staleProcessingBefore = -1, maxAttempts = 5))
        assertTrue(jobs.markCompleted(retried.id, retried.attempt, episodeCount = 3, now = 1_000))
        val completed = assertNotNull(jobs.findRequestedBy(enqueued.id, owner))
        assertEquals(EpisodeAnalysisJobStatus.COMPLETED, completed.status)
        assertEquals(3, completed.episodeCount)
        assertNull(completed.lastError)
        assertNull(jobs.claimNext(now = 10_000, retryBefore = 10_000, staleProcessingBefore = 10_000, maxAttempts = 5))
    }

    private fun submission(db: Database, vararg contents: String): EpisodeAnalysisSubmission =
        EpisodeAnalysisSubmission(owner, source, saveRecords(db, contents.map { SourceRecordDraft(it, it) }).map { it.id }, referenceId = null, access)

    private fun submissionWithReference(db: Database): EpisodeAnalysisSubmission {
        val reference = SourceReferenceDraft("guide.pdf", "application/pdf", "pdf".toByteArray())
        val records = saveRecords(db, listOf(SourceRecordDraft("a", "a"), SourceRecordDraft("b", "b", reference)))
        return EpisodeAnalysisSubmission(owner, source, records.map { it.id }, records.last().reference?.id, access)
    }

    private fun saveRecords(db: Database, drafts: List<SourceRecordDraft>) =
        assertIs<SourceRecordSaveResult.Saved>(
            SourceRecordRepositoryImpl(db).save(source, drafts, access),
        ).recordsToAnalyze

    private fun withDatabase(block: (Database) -> Unit) {
        val path = Files.createTempFile("episode-analysis-jobs", ".db")
        try { block(DatabaseFactory.init(path.toString())) }
        finally { Files.deleteIfExists(path) }
    }
}
