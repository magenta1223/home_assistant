package com.homeassistant.adapter.outbound.persistence.repo.source

import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeBatch
import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.db.tables.SourceRecordTable
import com.homeassistant.adapter.outbound.persistence.repo.episode.EpisodeRepositoryImpl
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.episode.EpisodeAccess
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecordAnalysisStatus
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.domain.source.SourceAccessConflictException
import com.homeassistant.domain.source.SourceReferenceDraft
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SourceRecordRepositoryTest {
    @Test
    fun `interpreted records share one persisted original reference`() {
        val databasePath = Files.createTempFile("source-reference", ".db")
        try {
            val repository = SourceRecordRepositoryImpl(DatabaseFactory.init(databasePath.toString()))
            val reference = SourceReferenceDraft("guide.pdf", "application/pdf", "original-pdf".toByteArray())
            val source = SourceDescriptor("text", "guide")

            repository.saveAll(
                source,
                listOf(
                    SourceRecordDraft("reference:${reference.sha256}:page-1", "page one", reference = reference),
                    SourceRecordDraft("reference:${reference.sha256}:page-2", "page two", reference = reference),
                ),
            )

            val records = repository.findBySource(source)
            assertEquals(2, records.size)
            assertEquals(1, records.mapNotNull { it.reference?.id }.distinct().size)
            assertTrue(records.all { it.reference?.fileName == "guide.pdf" })
            DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*), length(content) FROM source_references").use { result ->
                        assertTrue(result.next())
                        assertEquals(1, result.getInt(1))
                        assertEquals("original-pdf".toByteArray().size, result.getInt(2))
                    }
                }
            }
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `a record keeps the time its draft was recorded at`() {
        val databasePath = Files.createTempFile("source-record-time", ".db")
        try {
            val db = DatabaseFactory.init(databasePath.toString())
            SourceRecordRepositoryImpl(db).saveAll(
                SourceDescriptor("kakao", "room"),
                listOf(SourceRecordDraft("key", "content", recordedAt = 1_234L)),
            )

            assertEquals(1_234L, transaction(db) { SourceRecordTable.selectAll().single()[SourceRecordTable.recordedAt] })
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `an existing source cannot be silently reopened with a different audience`() {
        val databasePath = Files.createTempFile("source-record-access", ".db")
        try {
            val repository = SourceRecordRepositoryImpl(DatabaseFactory.init(databasePath.toString()))
            val source = SourceDescriptor("text", "knowledge")
            val existingAccess = EpisodeAccess.restricted(listOf(UserId("member-1")))
            repository.saveAll(
                source,
                listOf(draft("same")),
                existingAccess,
            )

            val failure = assertFailsWith<SourceAccessConflictException> {
                repository.saveAll(
                    source,
                    listOf(draft("same")),
                    EpisodeAccess.restricted(listOf(UserId("member-2"))),
                )
            }
            assertEquals(existingAccess, failure.existingAccess)
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `incremental import analyzes only the new records`() {
        val databasePath = Files.createTempFile("source-record-incremental", ".db")
        try {
            val db = DatabaseFactory.init(databasePath.toString())
            val repository = SourceRecordRepositoryImpl(db)
            val source = SourceDescriptor("kakao", "conversation")
            val existingDrafts = (1..25).map { draft("old-$it") }
            val initial = repository.saveAll(source, existingDrafts)
            markAnalyzed(db, initial.recordsToAnalyze.map { it.id })

            val incremental = repository.saveAll(
                source.copy(name = "renamed-conversation"),
                existingDrafts + draft("new") + draft("old-1"),
            )

            assertEquals(listOf("new"), incremental.recordsToAnalyze.map { it.deduplicationKey })
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `returns pending records for retry alongside newly imported records`() {
        val databasePath = Files.createTempFile("source-record-retry", ".db")
        try {
            val db = DatabaseFactory.init(databasePath.toString())
            val repository = SourceRecordRepositoryImpl(db)
            val source = SourceDescriptor("test", "source")
            val first = repository.saveAll(source, listOf(draft("a"), draft("b")))
            markAnalyzed(db, listOf(first.recordsToAnalyze.single { it.deduplicationKey == "b" }.id))

            val second = repository.saveAll(source, listOf(draft("a"), draft("b"), draft("c")))

            assertEquals(1, second.importedRecordCount)
            assertEquals(1, second.retriedRecordCount)
            assertEquals(1, second.alreadyAnalyzedRecordCount)
            assertEquals(listOf("a", "c"), second.recordsToAnalyze.map { it.deduplicationKey })
            assertTrue(second.recordsToAnalyze.all { it.analysisStatus == SourceRecordAnalysisStatus.PENDING })

            markAnalyzed(db, second.recordsToAnalyze.map { it.id })
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
            val access = EpisodeAccess.restricted(listOf(UserId("member-1"), UserId("member-2")))
            val saved = repository.saveAll(
                source,
                listOf(SourceRecordDraft("legacy-key", "legacy content")),
                access,
            )

            assertTrue(saved.recordsToAnalyze.isEmpty())
            assertEquals(1, saved.alreadyAnalyzedRecordCount)
            assertEquals(SourceRecordAnalysisStatus.ANALYZED, repository.findBySource(source).single().analysisStatus)
            assertEquals(access, repository.findBySource(source).single().access)
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

    private fun markAnalyzed(db: org.jetbrains.exposed.sql.Database, recordIds: Collection<Int>) {
        EpisodeRepositoryImpl(db).save(EpisodeBatch(UserId("test-member"), emptyList(), recordIds))
    }
}
