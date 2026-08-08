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
    fun `incremental import returns only the analyzed prefix verified by the current upload`() {
        val databasePath = Files.createTempFile("source-record-context", ".db")
        try {
            val repository = SourceRecordRepositoryImpl(DatabaseFactory.init(databasePath.toString()))
            val source = SourceDescriptor("kakao", "conversation")
            val existingDrafts = (1..25).map { draft("old-$it") }
            val initial = repository.saveAll(source, existingDrafts)
            repository.markAnalyzed(initial.recordsToAnalyze.map { it.id })

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

    @Test
    fun `legacy deduplication alias upgrades an existing row without reimporting it`() {
        val databasePath = Files.createTempFile("source-record-legacy-key", ".db")
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
                        VALUES ('kakao', 'same-name.txt', 'legacy content', 'legacy-name-key', 1)
                        """.trimIndent(),
                    )
                }
            }

            val repository = SourceRecordRepositoryImpl(DatabaseFactory.init(databasePath.toString()))
            val source = SourceDescriptor("kakao", "same-name.txt")
            val saved = repository.saveAll(
                source,
                listOf(
                    SourceRecordDraft(
                        deduplicationKey = "canonical-key",
                        content = "canonical content",
                        deduplicationAliases = setOf("legacy-name-key"),
                    ),
                ),
            )

            assertTrue(saved.recordsToAnalyze.isEmpty())
            assertEquals(1, saved.alreadyAnalyzedRecordCount)
            val upgraded = repository.findBySource(source).single()
            assertEquals(1, upgraded.id)
            assertEquals("canonical-key", upgraded.deduplicationKey)
            assertEquals("canonical content", upgraded.content)
            assertEquals(SourceRecordAnalysisStatus.ANALYZED, upgraded.analysisStatus)
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    private fun draft(key: String) = SourceRecordDraft(key, "content-$key")
}
