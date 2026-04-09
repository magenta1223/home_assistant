package com.homeassistant.domain.taxonomy

import com.homeassistant.domain.db.tables.TaxonomyTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaxonomyRepositoryTest {
    private val dbUrl = "jdbc:sqlite:file:${UUID.randomUUID()}?mode=memory&cache=shared"
    private lateinit var keepAlive: java.sql.Connection
    private lateinit var db: Database
    private lateinit var repo: TaxonomyRepository

    @BeforeTest
    fun setup() {
        keepAlive = DriverManager.getConnection(dbUrl)
        db = Database.connect(dbUrl, driver = "org.sqlite.JDBC")
        transaction(db) { SchemaUtils.create(TaxonomyTable) }
        repo = TaxonomyRepository(db)
    }

    @AfterTest
    fun teardown() {
        keepAlive.close()
    }

    @Test
    fun `create returns new id`() {
        val id = repo.create("Work", "CATEGORY", null)
        assertTrue(id > 0)
    }

    @Test
    fun `list root nodes when parentId is null`() {
        repo.create("Root", "CATEGORY", null)
        val nodes = repo.list(null)
        assertEquals(1, nodes.size)
        assertEquals("Root", nodes[0].name)
        assertNull(nodes[0].parentId)
    }

    @Test
    fun `list child nodes by parentId`() {
        val parentId = repo.create("Parent", "CATEGORY", null)
        repo.create("Child", "TAG", parentId)
        val children = repo.list(parentId)
        assertEquals(1, children.size)
        assertEquals("Child", children[0].name)
        assertEquals(parentId, children[0].parentId)
    }

    @Test
    fun `search finds nodes by name substring`() {
        repo.create("Shopping", "CATEGORY", null)
        repo.create("Work", "CATEGORY", null)
        val results = repo.search("shop")
        assertEquals(1, results.size)
        assertEquals("Shopping", results[0].name)
    }

    @Test
    fun `search is case-insensitive`() {
        repo.create("Shopping", "CATEGORY", null)
        val results = repo.search("SHOP")
        assertEquals(1, results.size)
    }
}
