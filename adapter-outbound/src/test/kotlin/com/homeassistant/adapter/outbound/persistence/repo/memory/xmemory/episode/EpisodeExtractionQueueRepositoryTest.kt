package com.homeassistant.adapter.outbound.persistence.repo.memory.xmemory.episode

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.db.SqliteDatabases
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.application.port.outbound.memory.xmemory.episode.EpisodeBatch
import com.homeassistant.application.port.outbound.memory.xmemory.episode.EpisodeExtractionClaim
import com.homeassistant.application.port.outbound.source.SourceRecordSaveResult
import com.homeassistant.domain.access.AccessControl
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.xmemory.episode.EpisodeProposal
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecordDraft
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpisodeExtractionQueueRepositoryTest {
    @Test fun `one claim holds one source's records from one submitter in recorded order`() = withDatabase { db ->
        store(db, CHAT, OWNER, draft("late", recordedAt = 20), draft("early", recordedAt = 10))
        store(db, OTHER_CHAT, OWNER, draft("other", recordedAt = 15))
        store(db, CHAT, MEMBER, draft("member", recordedAt = 30))
        val queue = EpisodeExtractionQueueRepository(db)

        val first = claim(queue)
        assertEquals(OWNER, first.submittedBy)
        assertEquals(CHAT, first.document.source)
        assertEquals(listOf("early", "late"), first.keys())
        assertEquals(listOf("other"), claim(queue).keys())
        assertEquals(MEMBER, claim(queue).submittedBy)
        assertNull(claimNext(queue))
    }

    @Test fun `stored Episodes take every claimed record off the queue, cited or not`() = withDatabase { db ->
        val records = store(db, CHAT, OWNER, draft("cited"), draft("uncited"))
        val queue = EpisodeExtractionQueueRepository(db)
        val claimed = claim(queue)

        EpisodeRepositoryImpl(db).save(
            EpisodeBatch(OWNER, listOf(EpisodeProposal("title", "content", listOf(records.first()))), claimed.recordIds()),
        )

        assertNull(claimNext(queue, staleProcessingBefore = Long.MAX_VALUE))
        assertFalse(queue.markFailed(claimed.claimId, "too late", now = 0))
    }

    @Test fun `records stored after a source was extracted form the next claim`() = withDatabase { db ->
        store(db, CHAT, OWNER, draft("first"))
        val queue = EpisodeExtractionQueueRepository(db)
        EpisodeRepositoryImpl(db).save(EpisodeBatch(OWNER, emptyList(), claim(queue).recordIds()))

        store(db, CHAT, OWNER, draft("first"), draft("second"))

        assertEquals(listOf("second"), claim(queue).keys())
    }

    @Test fun `a failed claim waits for the retry delay and stops after the attempt limit`() = withDatabase { db ->
        store(db, CHAT, OWNER, draft("a"))
        val queue = EpisodeExtractionQueueRepository(db)
        repeat(2) { attempt ->
            val now = 1_000L * attempt
            assertTrue(queue.markFailed(claim(queue, now = now, retryBefore = now, maxAttempts = 2).claimId, "model unavailable", now))
            assertNull(claimNext(queue, now = now, retryBefore = now - 1, maxAttempts = 2))
        }
        assertNull(claimNext(queue, now = 10_000, retryBefore = 10_000, maxAttempts = 2))
    }

    @Test fun `records stored after a source failed for good are still claimed`() = withDatabase { db ->
        store(db, CHAT, OWNER, draft("exhausted"))
        val queue = EpisodeExtractionQueueRepository(db)
        queue.markFailed(claim(queue, maxAttempts = 1).claimId, "model unavailable", now = 0)

        store(db, CHAT, OWNER, draft("new"))

        assertEquals(listOf("new"), claim(queue, retryBefore = 10_000, maxAttempts = 1).keys())
    }

    @Test fun `a stale claim is reclaimed after the lease and can no longer fail its records`() = withDatabase { db ->
        store(db, CHAT, OWNER, draft("a"))
        val queue = EpisodeExtractionQueueRepository(db)
        val stale = claim(queue, now = 0)
        assertNull(claimNext(queue, now = 500, staleProcessingBefore = -1))

        val fresh = claim(queue, now = 1_000, staleProcessingBefore = 0)

        assertFalse(queue.markFailed(stale.claimId, "stale worker", now = 1_000))
        assertTrue(queue.markFailed(fresh.claimId, "model unavailable", now = 1_000))
    }

    private fun store(db: SqliteDatabases, source: SourceDescriptor, submittedBy: UserId, vararg drafts: SourceRecordDraft): List<Int> {
        val saved = SourceRecordRepositoryImpl(db).save(source, drafts.toList(), submittedBy, AccessControl.PUBLIC)
        return (saved as SourceRecordSaveResult.Saved).newRecords.map { it.id }
    }

    private fun claim(
        queue: EpisodeExtractionQueueRepository,
        now: Long = 0,
        retryBefore: Long = 0,
        staleProcessingBefore: Long = -1,
        maxAttempts: Int = 5,
    ): EpisodeExtractionClaim = assertNotNull(claimNext(queue, now, retryBefore, staleProcessingBefore, maxAttempts))

    private fun claimNext(
        queue: EpisodeExtractionQueueRepository,
        now: Long = 0,
        retryBefore: Long = 0,
        staleProcessingBefore: Long = -1,
        maxAttempts: Int = 5,
    ): EpisodeExtractionClaim? = queue.claimNext(now, retryBefore, staleProcessingBefore, maxAttempts)

    private fun EpisodeExtractionClaim.keys() = document.records.map { it.deduplicationKey }

    private fun EpisodeExtractionClaim.recordIds() = document.records.map { it.id }

    private fun draft(key: String, recordedAt: Long = 0) = SourceRecordDraft(key, "content-$key", recordedAt = recordedAt)

    private fun withDatabase(block: (SqliteDatabases) -> Unit) {
        val path = Files.createTempFile("episode-extraction-queue", ".db")
        try { block(DatabaseFactory.init(path.toString())) }
        finally { Files.deleteIfExists(path) }
    }

    private companion object {
        val OWNER = UserId("owner")
        val MEMBER = UserId("member")
        val CHAT = SourceDescriptor("test", "chat")
        val OTHER_CHAT = SourceDescriptor("test", "other chat")
    }
}
