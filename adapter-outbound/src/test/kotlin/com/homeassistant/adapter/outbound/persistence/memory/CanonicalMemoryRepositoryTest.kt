package com.homeassistant.adapter.outbound.persistence.memory

import com.homeassistant.adapter.outbound.persistence.db.tables.*
import com.homeassistant.adapter.outbound.persistence.repo.memory.MemoryRepository
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*

class CanonicalMemoryRepositoryTest {
    private val dbUrl = "jdbc:sqlite:file:${UUID.randomUUID()}?mode=memory&cache=shared"
    private lateinit var keepAlive: java.sql.Connection
    private lateinit var db: Database
    private lateinit var memories: MemoryRepository

    @BeforeTest
    fun setup() {
        keepAlive = DriverManager.getConnection(dbUrl)
        db = Database.connect(dbUrl, driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.create(SourceRecordTable, MemoryTable, MemoryEvidenceTable, IndexingOutboxTable)
            SourceRecordTable.insert {
                it[sourceType] = "kakao"
                it[sourceName] = "family.txt"
                it[content] = "근거"
                it[deduplicationKey] = "r1"
                it[createdAt] = 1L
            }
        }
        memories = MemoryRepository(db)
    }

    @AfterTest
    fun teardown() = keepAlive.close()

    @Test
    fun `creates and loads flat memory`() {
        val memory = memories.create(proposal(), UserId("dad"))

        assertNull(memory.parentId)
        assertEquals(listOf(memory.id), memories.findByIds(listOf(memory.id)).map { it.id })
        assertEquals(memory.content, memories.findByIds(listOf(memory.id)).single().content)
    }

    @Test
    fun `filters private memory for a different user`() {
        val memory = memories.create(proposal(MemoryVisibility.PRIVATE), UserId("dad"))

        assertEquals(emptyList(), memories.findVisibleByIds(UserId("mom"), listOf(memory.id)))
        assertEquals(listOf(memory.id), memories.findVisibleByIds(UserId("dad"), listOf(memory.id)).map { it.id })
    }

    private fun proposal(visibility: MemoryVisibility = MemoryVisibility.FAMILY) = MemoryProposal(
        content = "동훈은 애정 표현을 했다.",
        subject = "동훈",
        memoryType = MemoryType.STATE,
        certainty = MemoryCertainty.OBSERVED,
        evidenceIds = listOf(1),
        visibility = visibility,
    )
}
