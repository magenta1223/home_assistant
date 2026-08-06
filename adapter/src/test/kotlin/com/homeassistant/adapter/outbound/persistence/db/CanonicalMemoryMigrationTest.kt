package com.homeassistant.adapter.outbound.persistence.db

import com.homeassistant.adapter.outbound.persistence.db.tables.CategoryTable
import com.homeassistant.adapter.outbound.persistence.db.tables.IndexingOutboxTable
import com.homeassistant.adapter.outbound.persistence.db.tables.KakaoImportedMessageTable
import com.homeassistant.adapter.outbound.persistence.db.tables.MemoryEvidenceTable
import com.homeassistant.adapter.outbound.persistence.db.tables.MemoryTable
import com.homeassistant.adapter.outbound.persistence.db.tables.SchemaMigrationTable
import com.homeassistant.adapter.outbound.persistence.db.tables.TopicCandidateTable
import com.homeassistant.adapter.outbound.persistence.db.tables.TopicCategoryTable
import com.homeassistant.adapter.outbound.persistence.db.tables.TopicTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CanonicalMemoryMigrationTest {
    private val dbUrl = "jdbc:sqlite:file:${UUID.randomUUID()}?mode=memory&cache=shared"
    private lateinit var keepAlive: java.sql.Connection
    private lateinit var db: Database

    @BeforeTest
    fun setup() {
        keepAlive = DriverManager.getConnection(dbUrl)
        db = Database.connect(dbUrl, driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.create(
                KakaoImportedMessageTable,
                TopicCandidateTable,
                IndexingOutboxTable,
            )
            exec(
                """
                CREATE TABLE memories (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    family_id TEXT NOT NULL,
                    content TEXT NOT NULL
                )
                """.trimIndent(),
            )
            exec("INSERT INTO memories (id, family_id, content) VALUES (7, 'legacy-family', '보존할 레거시 메모리')")
            KakaoImportedMessageTable.insert {
                it[id] = 41
                it[sourceFileName] = "family-chat.txt"
                it[sender] = "dad"
                it[displayTime] = "2026-08-06 10:00"
                it[content] = "차단기 리모컨은 벽장 위칸에 있어"
                it[lineStart] = 1
                it[lineEnd] = 1
                it[fingerprint] = "migration-evidence-41"
                it[createdAt] = 1_000L
            }
            TopicCandidateTable.insert {
                it[id] = 23
                it[familyId] = "legacy-family"
                it[createdByUserId] = "dad"
                it[sourceType] = "kakao"
                it[sourceName] = "family-chat.txt"
                it[title] = "주차장 리모컨 위치"
                it[summary] = "주차장 차단기 리모컨 위치를 공유했다."
                it[status] = "APPROVED"
                it[memoryTypesJson] = "[\"LOCATION\"]"
                it[domainsJson] = "[\"Home\",\"home\"]"
                it[evidenceJson] = "[41]"
                it[claimsJson] =
                    """[{"text":"차단기 리모컨은 벽장 위칸에 있다.","subject":"차단기 리모컨","memoryType":"LOCATION","certainty":"SAID","evidenceRefs":[41]}]"""
                it[createdAt] = 2_000L
                it[updatedAt] = 3_000L
            }
        }
    }

    @AfterTest
    fun teardown() {
        keepAlive.close()
    }

    @Test
    fun `approved legacy topic migrates losslessly and only once`() {
        migrate()
        migrate()

        transaction(db) {
            assertTrue(tableExists("legacy_memories"))
            assertEquals("보존할 레거시 메모리", queryString("SELECT content FROM legacy_memories WHERE id = 7"))
            assertEquals(1L, TopicTable.selectAll().count())
            assertEquals(23, TopicTable.selectAll().single()[TopicTable.id])
            assertEquals("dad", TopicTable.selectAll().single()[TopicTable.createdByUserId])
            assertEquals(listOf("home"), CategoryTable.selectAll().map { it[CategoryTable.name] })
            assertEquals(1L, TopicCategoryTable.selectAll().count())

            val memory = MemoryTable.selectAll().single()
            assertEquals(23, memory[MemoryTable.topicId])
            assertEquals("dad", memory[MemoryTable.createdByUserId])
            assertEquals("차단기 리모컨은 벽장 위칸에 있다.", memory[MemoryTable.content])
            assertEquals("차단기 리모컨", memory[MemoryTable.subject])
            assertEquals("LOCATION", memory[MemoryTable.memoryType])
            assertEquals("SAID", memory[MemoryTable.certainty])
            assertEquals("FAMILY", memory[MemoryTable.visibility])

            assertEquals(41, MemoryEvidenceTable.selectAll().single()[MemoryEvidenceTable.sourceRecordId])
            val outbox = IndexingOutboxTable.selectAll().single()
            assertEquals("MEMORY", outbox[IndexingOutboxTable.targetType])
            assertEquals(memory[MemoryTable.id], outbox[IndexingOutboxTable.targetId])
            assertEquals(1L, SchemaMigrationTable.selectAll().count())
        }
    }

    private fun migrate() {
        transaction(db) {
            archiveLegacyMemoryTable()
            SchemaUtils.createMissingTablesAndColumns(
                TopicTable,
                CategoryTable,
                TopicCategoryTable,
                MemoryTable,
                MemoryEvidenceTable,
                SchemaMigrationTable,
            )
            migrateLegacyTopics()
        }
    }

    private fun Transaction.tableExists(name: String): Boolean =
        exec("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '$name'") { it.next() } ?: false

    private fun Transaction.queryString(sql: String): String? = exec(sql) { result ->
        if (result.next()) result.getString(1) else null
    }
}
