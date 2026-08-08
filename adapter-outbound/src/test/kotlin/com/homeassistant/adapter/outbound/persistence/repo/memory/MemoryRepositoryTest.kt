package com.homeassistant.adapter.outbound.persistence.repo.memory

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.application.port.output.memory.placement.MemoryTreeAttachRequest
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryProposal
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.memory.MemoryVisibility
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecordDraft
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.jetbrains.exposed.sql.transactions.transaction

class MemoryRepositoryTest {
    @Test
    fun `stores one creation time and reads it from an existing memory`() {
        val databasePath = Files.createTempFile("memory-created-at", ".db")
        try {
            val database = DatabaseFactory.init(databasePath.toString())
            val sourceRecords = SourceRecordRepositoryImpl(database)
            val createdAt = Instant.parse("2026-08-08T01:02:03Z")
            val memories = MemoryRepository(database, Clock.fixed(createdAt, ZoneOffset.UTC))
            val userId = UserId("member-1")
            val record = sourceRecords.saveAll(
                SourceDescriptor(type = "test", name = "created-at"),
                listOf(SourceRecordDraft("memory", "memory")),
            ).recordsToAnalyze.single()

            val saved = memories.write(memoryProposal(record.id, "memory"), userId)
            val laterReader = MemoryRepository(
                database,
                Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC),
            )
            val stored = transaction(database) { laterReader.getMemories(userId).single() }

            assertEquals(createdAt.toEpochMilli(), saved.createdAt)
            assertEquals(saved.createdAt, stored.createdAt)
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `private memory remains visible only to the uploader`() {
        val databasePath = Files.createTempFile("private-memory", ".db")
        try {
            val database = DatabaseFactory.init(databasePath.toString())
            val sourceRecords = SourceRecordRepositoryImpl(database)
            val memories = MemoryRepository(database)
            val uploader = UserId("member-1")
            val otherMember = UserId("member-2")
            val record = sourceRecords.saveAll(
                SourceDescriptor(type = "test", name = "visibility"),
                listOf(SourceRecordDraft("private", "private")),
            ).recordsToAnalyze.single()
            memories.write(
                memoryProposal(record.id, "private").copy(visibility = MemoryVisibility.PRIVATE),
                uploader,
            )

            assertEquals(1, transaction(database) { memories.getMemories(uploader).size })
            assertEquals(0, transaction(database) { memories.getMemories(otherMember).size })
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `invalid assignment rolls back every update in the placement batch`() {
        val databasePath = Files.createTempFile("memory-placement", ".db")
        try {
            val database = DatabaseFactory.init(databasePath.toString())
            val sourceRecords = SourceRecordRepositoryImpl(database)
            val memories = MemoryRepository(database)
            val userId = UserId("member-1")
            val records = sourceRecords.saveAll(
                SourceDescriptor(type = "test", name = "placement"),
                listOf(
                    SourceRecordDraft("root", "root"),
                    SourceRecordDraft("child", "child"),
                    SourceRecordDraft("other", "other"),
                ),
            ).recordsToAnalyze
            val root = memories.write(memoryProposal(records[0].id, "root"), userId)
            val child = memories.write(memoryProposal(records[1].id, "child"), userId)
            val other = memories.write(memoryProposal(records[2].id, "other"), userId)

            assertFailsWith<IllegalArgumentException> {
                memories.attachChildren(
                    MemoryTreeAttachRequest(
                        userId = userId,
                        parentByChild = mapOf(
                            child.id to root.id,
                            other.id to 999_999,
                        ),
                    ),
                )
            }

            val storedRoot = transaction(database) {
                memories.getMemories(userId).single { it.id == root.id }
            }
            assertEquals(emptyList(), storedRoot.childrenIds)
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `batch placement is independent of parent assignment order`() {
        val databasePath = Files.createTempFile("memory-placement-order", ".db")
        try {
            val database = DatabaseFactory.init(databasePath.toString())
            val sourceRecords = SourceRecordRepositoryImpl(database)
            val memories = MemoryRepository(database)
            val userId = UserId("member-1")
            val records = sourceRecords.saveAll(
                SourceDescriptor(type = "test", name = "placement-order"),
                listOf(
                    SourceRecordDraft("root", "root"),
                    SourceRecordDraft("child", "child"),
                    SourceRecordDraft("grandchild", "grandchild"),
                ),
            ).recordsToAnalyze
            val root = memories.write(memoryProposal(records[0].id, "root"), userId)
            val child = memories.write(memoryProposal(records[1].id, "child"), userId)
            val grandchild = memories.write(memoryProposal(records[2].id, "grandchild"), userId)

            memories.attachChildren(
                MemoryTreeAttachRequest(
                    userId = userId,
                    parentByChild = linkedMapOf(
                        grandchild.id to child.id,
                        child.id to root.id,
                    ),
                ),
            )

            val stored = transaction(database) { memories.getMemories(userId).associateBy { it.id } }
            assertEquals(listOf(child.id), stored.getValue(root.id).childrenIds)
            assertEquals(listOf(grandchild.id), stored.getValue(child.id).childrenIds)
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `cycle against the stored graph is rejected without partial updates`() {
        val databasePath = Files.createTempFile("memory-placement-cycle", ".db")
        try {
            val database = DatabaseFactory.init(databasePath.toString())
            val sourceRecords = SourceRecordRepositoryImpl(database)
            val memories = MemoryRepository(database)
            val userId = UserId("member-1")
            val records = sourceRecords.saveAll(
                SourceDescriptor(type = "test", name = "placement-cycle"),
                listOf(
                    SourceRecordDraft("root", "root"),
                    SourceRecordDraft("child", "child"),
                    SourceRecordDraft("other", "other"),
                ),
            ).recordsToAnalyze
            val root = memories.write(memoryProposal(records[0].id, "root"), userId)
            val child = memories.write(memoryProposal(records[1].id, "child"), userId)
            val other = memories.write(memoryProposal(records[2].id, "other"), userId)
            memories.attachChildren(
                MemoryTreeAttachRequest(userId, mapOf(child.id to root.id)),
            )

            assertFailsWith<IllegalArgumentException> {
                memories.attachChildren(
                    MemoryTreeAttachRequest(
                        userId = userId,
                        parentByChild = linkedMapOf(
                            other.id to child.id,
                            root.id to child.id,
                        ),
                    ),
                )
            }

            val stored = transaction(database) { memories.getMemories(userId).associateBy { it.id } }
            assertEquals(listOf(child.id), stored.getValue(root.id).childrenIds)
            assertEquals(emptyList(), stored.getValue(child.id).childrenIds)
            assertEquals(emptyList(), stored.getValue(other.id).childrenIds)
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    private fun memoryProposal(evidenceId: Int, subject: String) = MemoryProposal(
        content = subject,
        subject = subject,
        memoryType = MemoryType.REFERENCE,
        certainty = MemoryCertainty.OBSERVED,
        evidenceIds = listOf(evidenceId),
        visibility = MemoryVisibility.PUBLIC,
    )
}
