package com.homeassistant.adapter.outbound.persistence.repo.source

import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeBatch
import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.db.tables.SourceRecordTable
import com.homeassistant.adapter.outbound.persistence.repo.episode.EpisodeRepositoryImpl
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.access.AccessControl
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecordAnalysisStatus
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.application.port.outbound.source.SourceRecordSaveResult
import com.homeassistant.domain.source.SourceReferenceDraft
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SourceRecordRepositoryTest {
    @Test
    fun `interpreted records share one persisted original reference`() {
        val databasePath = Files.createTempFile("source-reference", ".db")
        try {
            val repository = SourceRecordRepositoryImpl(DatabaseFactory.init(databasePath.toString()))
            val reference = SourceReferenceDraft("guide.pdf", "application/pdf", "original-pdf".toByteArray())
            val source = SourceDescriptor("text", "guide")

            val records = assertIs<SourceRecordSaveResult.Saved>(repository.saveAll(
                source,
                listOf(
                    SourceRecordDraft("reference:${reference.sha256}:page-1", "page one", reference = reference),
                    SourceRecordDraft("reference:${reference.sha256}:page-2", "page two", reference = reference),
                ),
            )).recordsToAnalyze

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
            assertIs<SourceRecordSaveResult.Saved>(SourceRecordRepositoryImpl(db).saveAll(
                SourceDescriptor("kakao", "room"),
                listOf(SourceRecordDraft("key", "content", recordedAt = 1_234L)),
            ))

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
            val existingAccess = AccessControl.restricted(listOf(UserId("member-1")))
            assertIs<SourceRecordSaveResult.Saved>(repository.saveAll(
                source,
                listOf(draft("same")),
                existingAccess,
            ))

            val otherAccess = AccessControl.restricted(listOf(UserId("member-2")))
            val result = repository.saveAll(source, listOf(draft("new"), draft("same")), otherAccess)
            assertEquals(SourceRecordSaveResult.AccessConflict(existingAccess), result)

            // The conflict wrote nothing, so the new draft is still new.
            val retried = assertIs<SourceRecordSaveResult.Saved>(repository.saveAll(source, listOf(draft("new")), otherAccess))
            assertEquals(1, retried.importedRecordCount)
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
            val initial = assertIs<SourceRecordSaveResult.Saved>(repository.saveAll(source, existingDrafts))
            markAnalyzed(db, initial.recordsToAnalyze.map { it.id })

            val incremental = assertIs<SourceRecordSaveResult.Saved>(repository.saveAll(
                source.copy(name = "renamed-conversation"),
                existingDrafts + draft("new") + draft("old-1"),
            ))

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
            val first = assertIs<SourceRecordSaveResult.Saved>(repository.saveAll(source, listOf(draft("a"), draft("b"))))
            markAnalyzed(db, listOf(first.recordsToAnalyze.single { it.deduplicationKey == "b" }.id))

            val second = assertIs<SourceRecordSaveResult.Saved>(repository.saveAll(source, listOf(draft("a"), draft("b"), draft("c"))))

            assertEquals(1, second.importedRecordCount)
            assertEquals(1, second.retriedRecordCount)
            assertEquals(1, second.alreadyAnalyzedRecordCount)
            assertEquals(listOf("a", "c"), second.recordsToAnalyze.map { it.deduplicationKey })
            assertTrue(second.recordsToAnalyze.all { it.analysisStatus == SourceRecordAnalysisStatus.PENDING })

            markAnalyzed(db, second.recordsToAnalyze.map { it.id })
            val completed = assertIs<SourceRecordSaveResult.Saved>(repository.saveAll(source, listOf(draft("a"), draft("b"), draft("c"))))
            assertTrue(completed.recordsToAnalyze.isEmpty())
            assertEquals(3, completed.alreadyAnalyzedRecordCount)
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    private fun draft(key: String) = SourceRecordDraft(key, "content-$key")

    private fun markAnalyzed(db: org.jetbrains.exposed.sql.Database, recordIds: Collection<Int>) {
        EpisodeRepositoryImpl(db).save(EpisodeBatch(UserId("test-member"), emptyList(), recordIds))
    }
}
