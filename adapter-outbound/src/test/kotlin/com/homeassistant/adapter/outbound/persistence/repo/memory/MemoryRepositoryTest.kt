package com.homeassistant.adapter.outbound.persistence.repo.memory

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.application.memory.tree.MemoryTreeAttachRequest
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryProposal
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.memory.MemoryVisibility
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecordDraft
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.jetbrains.exposed.sql.transactions.transaction

class MemoryRepositoryTest {
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
            ).single()
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
            )
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

    private fun memoryProposal(evidenceId: Int, subject: String) = MemoryProposal(
        content = subject,
        subject = subject,
        memoryType = MemoryType.REFERENCE,
        certainty = MemoryCertainty.OBSERVED,
        evidenceIds = listOf(evidenceId),
        visibility = MemoryVisibility.PUBLIC,
    )
}
