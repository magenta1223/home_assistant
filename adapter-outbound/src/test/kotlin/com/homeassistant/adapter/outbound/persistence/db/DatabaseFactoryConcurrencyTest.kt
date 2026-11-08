package com.homeassistant.adapter.outbound.persistence.db

import com.homeassistant.adapter.outbound.persistence.repo.episode.EpisodeAnalysisJobQueueRepository
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeAnalysisSubmission
import com.homeassistant.application.port.outbound.source.SourceRecordSaveResult
import com.homeassistant.domain.access.AccessControl
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.adapter.outbound.persistence.db.SqliteDatabases
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DatabaseFactoryConcurrencyTest {
    private val source = SourceDescriptor("test", "concurrent")

    @Test fun `concurrent writers that read before they write all succeed`() = withDatabase { db ->
        val records = SourceRecordRepositoryImpl(db)

        val saved = concurrently { thread ->
            (0 until SAVES_PER_THREAD).map { save ->
                records.save(source, listOf(SourceRecordDraft("record-$thread-$save", "content")))
            }
        }.flatten()

        assertEquals(THREADS * SAVES_PER_THREAD, saved.sumOf { assertIs<SourceRecordSaveResult.Saved>(it).importedRecordCount })
        val all = (0 until THREADS).flatMap { thread -> (0 until SAVES_PER_THREAD).map { SourceRecordDraft("record-$thread-$it", "content") } }
        assertEquals(THREADS * SAVES_PER_THREAD, assertIs<SourceRecordSaveResult.Saved>(records.save(source, all)).retriedRecordCount)
    }

    @Test fun `concurrent claims take every queued job exactly once`() = withDatabase { db ->
        val jobs = EpisodeAnalysisJobQueueRepository(db)
        val queued = (1..THREADS * SAVES_PER_THREAD).map {
            jobs.enqueue(EpisodeAnalysisSubmission(UserId("owner"), source, emptyList(), referenceId = null, AccessControl.PUBLIC), now = 0).id
        }

        val claimed = concurrently {
            generateSequence { jobs.claimNext(now = 0, retryBefore = 0, staleProcessingBefore = -1, maxAttempts = 5) }
                .map { it.id }
                .toList()
        }.flatten()

        assertEquals(queued.sorted(), claimed.sorted())
    }

    /** Runs [work] on [THREADS] threads released at the same moment and returns each thread's result. */
    private fun <T> concurrently(work: (thread: Int) -> T): List<T> {
        val executor = Executors.newFixedThreadPool(THREADS)
        try {
            val start = CountDownLatch(1)
            val results = (0 until THREADS).map { thread -> executor.submit<T> { start.await(); work(thread) } }
            start.countDown()
            return results.map { it.get(1, TimeUnit.MINUTES) }
        } finally {
            executor.shutdown()
            executor.awaitTermination(1, TimeUnit.MINUTES)
        }
    }

    private fun withDatabase(block: (SqliteDatabases) -> Unit) {
        val path = Files.createTempFile("concurrent-writes", ".db")
        try { block(DatabaseFactory.init(path.toString())) }
        finally { listOf("", "-wal", "-shm").forEach { Files.deleteIfExists(path.resolveSibling(path.fileName.toString() + it)) } }
    }

    private companion object {
        const val THREADS = 8
        const val SAVES_PER_THREAD = 25
    }
}
