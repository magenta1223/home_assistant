package com.homeassistant.repository.memory

import com.homeassistant.core.identity.UserId
import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.core.memory.MemoryType
import com.homeassistant.datamodel.memory.AuditAction
import com.homeassistant.datamodel.memory.DEFAULT_FAMILY_ID
import com.homeassistant.repository.db.tables.*
import com.homeassistant.repository.repo.memory.MemoryRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*

class MemoryRepositoryTest {
    private val dbUrl = "jdbc:sqlite:file:${UUID.randomUUID()}?mode=memory&cache=shared"
    private lateinit var keepAlive: java.sql.Connection
    private lateinit var db: Database
    private lateinit var repo: MemoryRepository

    @BeforeTest
    fun setup() {
        keepAlive = DriverManager.getConnection(dbUrl)
        db = Database.connect(dbUrl, driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.create(
                FamilyTable,
                FamilyMemberTable,
                DomainTable,
                ConversationMessageTable,
                MemoryCandidateTable,
                MemoryTable,
                AuditLogTable,
            )
        }
        repo = MemoryRepository(db)
    }

    @AfterTest
    fun teardown() {
        keepAlive.close()
    }

    @Test
    fun `memory tables store memory type in a single column`() {
        assertEquals(
            setOf("memory_type"),
            memoryColumnNames("memory_candidates").filter { it.startsWith("memory_") }.toSet(),
        )
        assertEquals(
            setOf("memory_type"),
            memoryColumnNames("memories").filter { it.startsWith("memory_") }.toSet(),
        )
    }

    @Test
    fun `candidate creation records default family createdBy and pending status`() {
        val id = repo.createCandidate(
            userId = UserId("dad"),
            conversationId = "conv-1",
            domainName = "SCHOOL",
            memoryType = MemoryType.STATE,
            content = "Min has piano class on Friday",
            summary = "Min piano Friday",
            confidence = 0.82,
            sourceConversationMessageId = null,
        )

        val pending = repo.listPending(UserId("dad"), "conv-1")
        assertEquals(1, pending.size)
        assertEquals(id, pending.single().id)
        assertEquals("dad", pending.single().createdBy)
        assertEquals(DEFAULT_FAMILY_ID, pending.single().familyId)
        assertEquals(CandidateStatus.PENDING, pending.single().status)
    }

    @Test
    fun `approve candidate creates memory upserts domain and audit log`() {
        val candidateId = repo.createCandidate(
            userId = UserId("mom"),
            conversationId = "conv-2",
            domainName = "AFTER_SCHOOL",
            memoryType = MemoryType.ROUTINE,
            content = "Pick up Joon at 5pm",
            summary = "Joon pickup 5pm",
            confidence = 0.9,
            sourceConversationMessageId = null,
        )

        val memory = repo.approveCandidate(UserId("mom"), candidateId)

        assertEquals("Pick up Joon at 5pm", memory.content)
        assertEquals("mom", memory.createdBy)
        assertEquals("AFTER_SCHOOL", memory.domainName)
        assertEquals(CandidateStatus.APPROVED, repo.getCandidate(candidateId)?.status)
        assertTrue(repo.auditLogs().any { it.action == AuditAction.MEMORY_CREATED && it.memoryId == memory.id })
    }

    @Test
    fun `reject candidate does not create memory`() {
        val candidateId = repo.createCandidate(
            userId = UserId("dad"),
            conversationId = "conv-3",
            domainName = "HOME",
            memoryType = MemoryType.DECISION,
            content = "Do not save this",
            summary = "Reject me",
            confidence = 0.5,
            sourceConversationMessageId = null,
        )

        repo.rejectCandidate(UserId("dad"), candidateId)

        assertEquals(CandidateStatus.REJECTED, repo.getCandidate(candidateId)?.status)
        assertTrue(repo.listMemories().isEmpty())
    }

    @Test
    fun `approve candidate rejects a different user`() {
        val candidateId = createCandidate(UserId("mom"), "conv-owner-approve")
        val auditCount = repo.auditLogs().size

        assertFails {
            repo.approveCandidate(UserId("dad"), candidateId)
        }

        assertEquals(CandidateStatus.PENDING, repo.getCandidate(candidateId)?.status)
        assertTrue(repo.listMemories().isEmpty())
        assertEquals(auditCount, repo.auditLogs().size)
    }

    @Test
    fun `reject candidate rejects a different user`() {
        val candidateId = createCandidate(UserId("mom"), "conv-owner-reject")
        val auditCount = repo.auditLogs().size

        assertFails {
            repo.rejectCandidate(UserId("dad"), candidateId)
        }

        assertEquals(CandidateStatus.PENDING, repo.getCandidate(candidateId)?.status)
        assertEquals(auditCount, repo.auditLogs().size)
    }

    @Test
    fun `reject candidate rejects an already approved candidate`() {
        val userId = UserId("mom")
        val candidateId = createCandidate(userId, "conv-approved")
        val memory = repo.approveCandidate(userId, candidateId)
        val auditCount = repo.auditLogs().size

        assertFails {
            repo.rejectCandidate(userId, candidateId)
        }

        assertEquals(CandidateStatus.APPROVED, repo.getCandidate(candidateId)?.status)
        assertEquals(memory.id, repo.listMemories().single().id)
        assertEquals(auditCount, repo.auditLogs().size)
    }

    private fun createCandidate(userId: UserId, conversationId: String): Int =
        repo.createCandidate(
            userId = userId,
            conversationId = conversationId,
            domainName = "HOME",
            memoryType = MemoryType.STATE,
            content = "Owner-only candidate",
            summary = "Owner-only",
            confidence = 0.8,
            sourceConversationMessageId = null,
        )

    private fun memoryColumnNames(table: String): List<String> = transaction(db) {
        val names = mutableListOf<String>()
        exec("PRAGMA table_info($table)") { result ->
            while (result.next()) names += result.getString("name")
        }
        names
    }
}
