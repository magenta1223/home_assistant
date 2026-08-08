package com.homeassistant.adapter.outbound.persistence.repo.source

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecordAnalysisStatus
import com.homeassistant.domain.source.SourceRecordDraft
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceRecordRepositoryTest {
    @Test
    fun `incremental import returns only the recent analyzed prefix as context`() {
        val databasePath = Files.createTempFile("source-record-context", ".db")
        try {
            val repository = SourceRecordRepositoryImpl(DatabaseFactory.init(databasePath.toString()))
            val source = SourceDescriptor("kakao", "conversation")
            val existingDrafts = (1..25).map { draft("old-$it") }
            val initial = repository.saveAll(source, existingDrafts)
            repository.markAnalyzed(initial.recordsToAnalyze.map { it.id })

            assertEquals(
                listOf("old-23", "old-24", "old-25"),
                repository.findRecentAnalyzed(source, limit = 3).map { it.deduplicationKey },
            )

            val incremental = repository.saveAll(
                source.copy(name = "renamed-conversation"),
                existingDrafts + draft("new") + draft("old-1"),
            )

            assertEquals((6..25).map { "old-$it" }, incremental.contextRecords.map { it.deduplicationKey })
            assertEquals(listOf("new"), incremental.recordsToAnalyze.map { it.deduplicationKey })
            assertTrue(incremental.contextRecords.all { it.analysisStatus == SourceRecordAnalysisStatus.ANALYZED })
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `returns pending records for retry alongside newly imported records`() {
        val databasePath = Files.createTempFile("source-record-retry", ".db")
        try {
            val repository = SourceRecordRepositoryImpl(DatabaseFactory.init(databasePath.toString()))
            val source = SourceDescriptor("test", "source")
            val first = repository.saveAll(source, listOf(draft("a"), draft("b")))
            repository.markAnalyzed(listOf(first.recordsToAnalyze.single { it.deduplicationKey == "b" }.id))

            val second = repository.saveAll(source, listOf(draft("a"), draft("b"), draft("c")))

            assertEquals(1, second.importedRecordCount)
            assertEquals(1, second.retriedRecordCount)
            assertEquals(1, second.alreadyAnalyzedRecordCount)
            assertEquals(listOf("a", "c"), second.recordsToAnalyze.map { it.deduplicationKey })
            assertTrue(second.recordsToAnalyze.all { it.analysisStatus == SourceRecordAnalysisStatus.PENDING })

            repository.markAnalyzed(second.recordsToAnalyze.map { it.id })
            val completed = repository.saveAll(source, listOf(draft("a"), draft("b"), draft("c")))
            assertTrue(completed.recordsToAnalyze.isEmpty())
            assertEquals(3, completed.alreadyAnalyzedRecordCount)
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `existing database records migrate as analyzed`() {
        val databasePath = Files.createTempFile("source-record-migration", ".db")
        try {
            DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE source_records (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            source_type TEXT NOT NULL,
                            source_name TEXT NOT NULL,
                            content TEXT NOT NULL,
                            deduplication_key TEXT NOT NULL,
                            created_at INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        INSERT INTO source_records
                            (source_type, source_name, content, deduplication_key, created_at)
                        VALUES ('test', 'legacy', 'legacy content', 'legacy-key', 1)
                        """.trimIndent(),
                    )
                }
            }

            val repository = SourceRecordRepositoryImpl(DatabaseFactory.init(databasePath.toString()))
            val source = SourceDescriptor("test", "legacy")
            val saved = repository.saveAll(source, listOf(SourceRecordDraft("legacy-key", "legacy content")))

            assertTrue(saved.recordsToAnalyze.isEmpty())
            assertEquals(1, saved.alreadyAnalyzedRecordCount)
            assertEquals(SourceRecordAnalysisStatus.ANALYZED, repository.findBySource(source).single().analysisStatus)
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    private fun draft(key: String) = SourceRecordDraft(key, "content-$key")
}
