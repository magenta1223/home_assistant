package com.homeassistant.domain.grocery

import com.homeassistant.domain.db.tables.GroceryItemTable
import com.homeassistant.domain.db.tables.GroceryPurchaseTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroceryRepositoryTest {
    private val dbUrl = "jdbc:sqlite:file:${UUID.randomUUID()}?mode=memory&cache=shared"
    private lateinit var keepAlive: java.sql.Connection
    private lateinit var db: Database
    private lateinit var repo: GroceryRepository

    @BeforeTest
    fun setup() {
        keepAlive = DriverManager.getConnection(dbUrl)
        db = Database.connect(dbUrl, driver = "org.sqlite.JDBC")
        transaction(db) { SchemaUtils.create(GroceryItemTable, GroceryPurchaseTable) }
        repo = GroceryRepository(db)
    }

    @AfterTest
    fun teardown() {
        keepAlive.close()
    }

    @Test
    fun `recordPurchase creates item and purchase`() {
        val id = repo.recordPurchase("Milk", 2.0, null)
        assertTrue(id > 0)
        val list = repo.list()
        assertEquals(1, list.size)
        assertEquals("Milk", list[0].name)
        assertNotNull(list[0].lastPurchasedAt)
    }

    @Test
    fun `recordPurchase reuses existing item`() {
        repo.recordPurchase("Milk", 1.0, null)
        repo.recordPurchase("Milk", 2.0, null)
        val list = repo.list()
        assertEquals(1, list.size)
    }

    @Test
    fun `avgIntervalDays is null with one purchase`() {
        repo.recordPurchase("Milk", 1.0, null)
        val list = repo.list()
        assertNull(list[0].avgIntervalDays)
    }

    @Test
    fun `avgIntervalDays calculated with two purchases`() {
        val t1 = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(10)
        val t2 = System.currentTimeMillis()
        repo.recordPurchase("Milk", 1.0, t1)
        repo.recordPurchase("Milk", 1.0, t2)
        val list = repo.list()
        val avg = list[0].avgIntervalDays
        assertNotNull(avg)
        assertTrue(avg in 9.0..11.0)
    }

    @Test
    fun `due returns items past average interval`() {
        val t1 = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(20)
        val t2 = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(12)
        repo.recordPurchase("Milk", 1.0, t1)
        repo.recordPurchase("Milk", 1.0, t2)
        // avg = 8 days, last purchased 12 days ago → due
        val due = repo.due()
        assertEquals(1, due.size)
        assertEquals("Milk", due[0].name)
    }

    @Test
    fun `due excludes items not yet past interval`() {
        val t1 = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(10)
        val t2 = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)
        repo.recordPurchase("Eggs", 1.0, t1)
        repo.recordPurchase("Eggs", 1.0, t2)
        // avg = 8 days, last purchased 2 days ago → not due
        val due = repo.due()
        assertTrue(due.isEmpty())
    }
}
