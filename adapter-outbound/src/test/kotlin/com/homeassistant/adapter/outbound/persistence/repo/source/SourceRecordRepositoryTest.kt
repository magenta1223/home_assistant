package com.homeassistant.adapter.outbound.persistence.repo.source

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.db.tables.SourceRecordTable
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.access.AccessControl
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.application.port.outbound.source.SourceRecordSaveResult
import com.homeassistant.domain.source.SourceReferenceDraft
import org.jetbrains.exposed.sql.selectAll
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceRecordRepositoryTest {
    @Test
    fun `interpreted records share one persisted original reference`() {
        val databasePath = Files.createTempFile("source-reference", ".db")
        try {
            val repository = SourceRecordRepositoryImpl(DatabaseFactory.init(databasePath.toString()))
            val reference = SourceReferenceDraft("guide.pdf", "application/pdf", "original-pdf".toByteArray())
            val source = SourceDescriptor("text", "guide")

            val records = assertIs<SourceRecordSaveResult.Saved>(repository.save(
                source,
                listOf(
                    SourceRecordDraft("reference:${reference.sha256}:page-1", "page one", reference = reference),
                    SourceRecordDraft("reference:${reference.sha256}:page-2", "page two", reference = reference),
                ), SUBMITTER,
            )).newRecords

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
            assertIs<SourceRecordSaveResult.Saved>(SourceRecordRepositoryImpl(db).save(
                SourceDescriptor("kakao", "room"),
                listOf(SourceRecordDraft("key", "content", recordedAt = 1_234L)), SUBMITTER,
            ))

            assertEquals(1_234L, db.write { SourceRecordTable.selectAll().single()[SourceRecordTable.recordedAt] })
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
            assertIs<SourceRecordSaveResult.Saved>(repository.save(
                source,
                listOf(draft("same")), SUBMITTER,
                existingAccess,
            ))

            val otherAccess = AccessControl.restricted(listOf(UserId("member-2")))
            val result = repository.save(source, listOf(draft("new"), draft("same")), SUBMITTER, otherAccess)
            assertEquals(SourceRecordSaveResult.AccessConflict(existingAccess), result)

            // The conflict wrote nothing, so the new draft is still new.
            val retried = assertIs<SourceRecordSaveResult.Saved>(repository.save(source, listOf(draft("new")), SUBMITTER, otherAccess))
            assertEquals(1, retried.newRecords.size)
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `incremental import stores only the new records`() {
        val databasePath = Files.createTempFile("source-record-incremental", ".db")
        try {
            val db = DatabaseFactory.init(databasePath.toString())
            val repository = SourceRecordRepositoryImpl(db)
            val source = SourceDescriptor("kakao", "conversation")
            val existingDrafts = (1..25).map { draft("old-$it") }
            assertIs<SourceRecordSaveResult.Saved>(repository.save(source, existingDrafts, SUBMITTER))

            val incremental = assertIs<SourceRecordSaveResult.Saved>(repository.save(
                source.copy(name = "renamed-conversation"),
                existingDrafts + draft("new") + draft("old-1"), SUBMITTER,
            ))

            assertEquals(listOf("new"), incremental.newRecords.map { it.deduplicationKey })
            assertEquals(25, incremental.alreadyStoredRecords.size)
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `a resubmission returns the records stored before apart from the new ones`() {
        val databasePath = Files.createTempFile("source-record-resubmission", ".db")
        try {
            val repository = SourceRecordRepositoryImpl(DatabaseFactory.init(databasePath.toString()))
            val source = SourceDescriptor("test", "source")
            val first = assertIs<SourceRecordSaveResult.Saved>(repository.save(source, listOf(draft("a"), draft("b")), SUBMITTER))

            val second = assertIs<SourceRecordSaveResult.Saved>(repository.save(source, listOf(draft("b"), draft("c"), draft("a")), SUBMITTER))

            assertEquals(listOf("c"), second.newRecords.map { it.deduplicationKey })
            assertEquals(listOf("b", "a"), second.alreadyStoredRecords.map { it.deduplicationKey })
            assertEquals(first.newRecords.map { it.id }.toSet(), second.alreadyStoredRecords.map { it.id }.toSet())
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `a reference saved alone is stored once and read back with its file`() {
        val databasePath = Files.createTempFile("source-reference-alone", ".db")
        try {
            val repository = SourceRecordRepositoryImpl(DatabaseFactory.init(databasePath.toString()))
            val original = "original-pdf".toByteArray()
            val reference = SourceReferenceDraft("guide.pdf", "application/pdf", original)

            val saved = repository.saveReference(reference)
            val savedAgain = repository.saveReference(SourceReferenceDraft("renamed.pdf", "application/pdf", original))
            val content = repository.findReferenceContent(saved.id)

            assertEquals(saved, savedAgain)
            assertEquals("guide.pdf", saved.fileName)
            assertEquals(reference.sha256, saved.sha256)
            assertEquals("guide.pdf", content?.fileName)
            assertEquals("application/pdf", content?.mediaType)
            assertContentEquals(original, content?.bytes())
            assertNull(repository.findReferenceContent(saved.id + 1))
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    private fun draft(key: String) = SourceRecordDraft(key, "content-$key")
}

private val SUBMITTER = UserId("submitter")
