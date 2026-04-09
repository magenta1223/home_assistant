package com.homeassistant.domain.memo

import com.homeassistant.domain.db.tables.MemoTable
import com.homeassistant.domain.db.tables.MemoTaxonomyTable
import com.homeassistant.domain.db.tables.TaxonomyTable
import com.homeassistant.domain.taxonomy.TaxonomyRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoRepositoryTest {
    private val dbUrl = "jdbc:sqlite:file:${UUID.randomUUID()}?mode=memory&cache=shared"
    private lateinit var keepAlive: java.sql.Connection
    private lateinit var db: Database
    private lateinit var repo: MemoRepository
    private lateinit var taxRepo: TaxonomyRepository

    @BeforeTest
    fun setup() {
        keepAlive = DriverManager.getConnection(dbUrl)
        db = Database.connect(dbUrl, driver = "org.sqlite.JDBC")
        transaction(db) { SchemaUtils.create(TaxonomyTable, MemoTable, MemoTaxonomyTable) }
        repo = MemoRepository(db)
        taxRepo = TaxonomyRepository(db)
    }

    @AfterTest
    fun teardown() {
        keepAlive.close()
    }

    @Test
    fun `create and list memo without taxonomy`() {
        val id = repo.create("Title", "Content", emptyList())
        val memos = repo.list(null)
        assertEquals(1, memos.size)
        assertEquals("Title", memos[0].title)
        assertEquals(id, memos[0].id)
    }

    @Test
    fun `create memo with taxonomy node`() {
        val nodeId = taxRepo.create("Work", "CATEGORY", null)
        val memoId = repo.create("Meeting notes", "Discussed Q1 plans", listOf(nodeId))
        val memos = repo.list(nodeId)
        assertEquals(1, memos.size)
        assertEquals(memoId, memos[0].id)
        assertTrue(memos[0].nodeIds.contains(nodeId))
    }

    @Test
    fun `list with node filter excludes other memos`() {
        val nodeId = taxRepo.create("Work", "CATEGORY", null)
        repo.create("Work memo", "content", listOf(nodeId))
        repo.create("Personal memo", "content", emptyList())
        val memos = repo.list(nodeId)
        assertEquals(1, memos.size)
        assertEquals("Work memo", memos[0].title)
    }

    @Test
    fun `search finds by title`() {
        repo.create("Kotlin tips", "content", emptyList())
        repo.create("Shopping list", "content", emptyList())
        val results = repo.search("kotlin", null)
        assertEquals(1, results.size)
        assertEquals("Kotlin tips", results[0].title)
    }

    @Test
    fun `search finds by content`() {
        repo.create("Notes", "remember to buy milk", emptyList())
        val results = repo.search("milk", null)
        assertEquals(1, results.size)
    }

    @Test
    fun `update changes title and content`() {
        val id = repo.create("Old title", "Old content", emptyList())
        repo.update(id, "New title", "New content", null)
        val memos = repo.list(null)
        assertEquals("New title", memos[0].title)
        assertEquals("New content", memos[0].content)
    }

    @Test
    fun `delete removes memo`() {
        val id = repo.create("To delete", "content", emptyList())
        val deleted = repo.delete(id)
        assertTrue(deleted)
        assertTrue(repo.list(null).isEmpty())
    }

    @Test
    fun `delete returns false for nonexistent id`() {
        val deleted = repo.delete(999)
        assertTrue(!deleted)
    }
}
