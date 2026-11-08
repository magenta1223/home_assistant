package com.homeassistant.adapter.outbound.persistence.db

import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.application.port.outbound.source.SourceRecordSaveResult
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecordDraft
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SqliteDatabasesTest {
    private val source = SourceDescriptor("test", "reader")

    @Test fun `a read does not wait for a writer that holds the write lock`() = withDatabase { db ->
        val records = SourceRecordRepositoryImpl(db)
        val committed = assertIs<SourceRecordSaveResult.Saved>(records.save(source, listOf(SourceRecordDraft("committed", "content"))))
            .recordsToAnalyze.single()
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = CompletableFuture.runAsync {
            db.write {
                records.save(source, listOf(SourceRecordDraft("uncommitted", "content")))
                writing.countDown()
                release.await()
            }
        }
        try {
            assertTrue(writing.await(10, TimeUnit.SECONDS))
            val elapsed = measureTimeMillis {
                assertEquals(listOf(committed), records.findPendingByIds((1..10).toList()))
            }
            assertTrue(elapsed < READ_WITHOUT_WAITING_MILLIS, "read waited $elapsed ms for the writer")
        } finally {
            release.countDown()
            writer.get(10, TimeUnit.SECONDS)
        }
    }

    @Test fun `the read side refuses to write`() = withDatabase { db ->
        assertFails { db.read { exec("CREATE TABLE probe(x INTEGER)") } }
    }

    private fun withDatabase(block: (SqliteDatabases) -> Unit) {
        val path = Files.createTempFile("sqlite-databases", ".db")
        try { block(DatabaseFactory.init(path.toString())) }
        finally { listOf("", "-wal", "-shm").forEach { Files.deleteIfExists(path.resolveSibling(path.fileName.toString() + it)) } }
    }

    private companion object {
        /** Far below the 5 s busy timeout a waiting read would take. */
        const val READ_WITHOUT_WAITING_MILLIS = 1_000L
    }
}
