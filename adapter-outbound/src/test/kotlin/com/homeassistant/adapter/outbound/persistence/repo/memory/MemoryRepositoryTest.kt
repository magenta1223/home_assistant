package com.homeassistant.adapter.outbound.persistence.repo.memory

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.db.tables.MemoryTable
import com.homeassistant.adapter.outbound.persistence.db.tables.SourceRecordTable
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.application.port.output.memory.placement.MemoryTreeAttachRequest
import com.homeassistant.application.port.output.memory.write.IdempotentMemoryProposal
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryAccess
import com.homeassistant.domain.memory.MemoryProposal
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.memory.MemoryVisibility
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecordDraft
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.nio.file.Files
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MemoryRepositoryTest {
    @Test
    fun `legacy private memory migrates to creator-only restricted access`() {
        val databasePath = Files.createTempFile("legacy-private-memory", ".db")
        try {
            val database = DatabaseFactory.init(databasePath.toString())
            val sourceRecords = SourceRecordRepositoryImpl(database)
            val memories = MemoryRepository(database)
            val creator = UserId("member-1")
            val evidence = sourceRecords.saveAll(
                SourceDescriptor("test", "legacy"),
                listOf(SourceRecordDraft("legacy", "legacy")),
            ).recordsToAnalyze.single()
            val memory = memories.write(memoryProposal(evidence.id, "legacy"), creator)
            transaction(database) {
                MemoryTable.update({ MemoryTable.id eq memory.id }) { it[visibility] = "PRIVATE" }
                SourceRecordTable.update({ SourceRecordTable.id eq evidence.id }) {
                    it[audienceExplicit] = false
                }
            }

            val migratedDatabase = DatabaseFactory.init(databasePath.toString())
            val migratedMemories = MemoryRepository(migratedDatabase)
            val migratedSource = SourceRecordRepositoryImpl(migratedDatabase)

            assertEquals(MemoryVisibility.RESTRICTED, migratedMemories.getMemories(creator).single().visibility)
            assertEquals(setOf(creator.value), migratedMemories.getMemories(creator).single().allowedUserIds)
            assertEquals(0, migratedMemories.getMemories(UserId("member-2")).size)
            assertEquals(
                MemoryAccess.restricted(listOf(creator)),
                migratedSource.findBySource(SourceDescriptor("test", "legacy")).single().access,
            )
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

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
            val stored = laterReader.getMemories(userId).single()

            assertEquals(createdAt.toEpochMilli(), saved.createdAt)
            assertEquals(saved.createdAt, stored.createdAt)
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `restricted memory is visible to every selected user and nobody else`() {
        val databasePath = Files.createTempFile("private-memory", ".db")
        try {
            val database = DatabaseFactory.init(databasePath.toString())
            val sourceRecords = SourceRecordRepositoryImpl(database)
            val memories = MemoryRepository(database)
            val uploader = UserId("member-1")
            val participant = UserId("member-2")
            val otherMember = UserId("member-3")
            val record = sourceRecords.saveAll(
                SourceDescriptor(type = "test", name = "visibility"),
                listOf(SourceRecordDraft("private", "private")),
                MemoryAccess.restricted(listOf(uploader, participant)),
            ).recordsToAnalyze.single()
            memories.write(
                memoryProposal(record.id, "private"),
                uploader,
            )

            assertEquals(1, memories.getMemories(uploader).size)
            assertEquals(1, memories.getMemories(participant).size)
            assertEquals(0, memories.getMemories(otherMember).size)
        } finally {
            Files.deleteIfExists(databasePath)
        }
    }

    @Test
    fun `memory backed by multiple sources receives their viewer intersection`() {
        val databasePath = Files.createTempFile("memory-access-intersection", ".db")
        try {
            val database = DatabaseFactory.init(databasePath.toString())
            val sourceRecords = SourceRecordRepositoryImpl(database)
            val memories = MemoryRepository(database)
            val member1 = UserId("member-1")
            val member2 = UserId("member-2")
            val member3 = UserId("member-3")
            val first = sourceRecords.saveAll(
                SourceDescriptor("test", "first"),
                listOf(SourceRecordDraft("first", "first")),
                MemoryAccess.restricted(listOf(member1, member2)),
            ).recordsToAnalyze.single()
            val second = sourceRecords.saveAll(
                SourceDescriptor("test", "second"),
                listOf(SourceRecordDraft("second", "second")),
                MemoryAccess.restricted(listOf(member2, member3)),
            ).recordsToAnalyze.single()
            val proposal = memoryProposal(first.id, "combined").copy(evidenceIds = listOf(first.id, second.id))

            memories.write(proposal, member1)

            assertEquals(0, memories.getMemories(member1).size)
            assertEquals(1, memories.getMemories(member2).size)
            assertEquals(0, memories.getMemories(member3).size)
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

            val storedRoot = memories.getMemories(userId).single { it.id == root.id }
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

            val stored = memories.getMemories(userId).associateBy { it.id }
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

            val stored = memories.getMemories(userId).associateBy { it.id }
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
    )

    private fun MemoryRepository.write(proposal: MemoryProposal, createdBy: UserId) = commit(
        createdBy,
        listOf(IdempotentMemoryProposal("test:${proposal.evidenceIds}:${proposal.content}", proposal)),
        proposal.evidenceIds,
    ).single()
}
