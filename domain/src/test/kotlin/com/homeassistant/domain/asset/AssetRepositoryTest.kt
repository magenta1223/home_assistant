package com.homeassistant.domain.asset

import com.homeassistant.domain.db.tables.AssetTable
import com.homeassistant.domain.db.tables.AssetValueHistoryTable
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

class AssetRepositoryTest {
    private val dbUrl = "jdbc:sqlite:file:${UUID.randomUUID()}?mode=memory&cache=shared"
    private lateinit var keepAlive: java.sql.Connection
    private lateinit var db: Database
    private lateinit var repo: AssetRepository

    @BeforeTest
    fun setup() {
        keepAlive = DriverManager.getConnection(dbUrl)
        db = Database.connect(dbUrl, driver = "org.sqlite.JDBC")
        transaction(db) { SchemaUtils.create(AssetTable, AssetValueHistoryTable) }
        repo = AssetRepository(db)
    }

    @AfterTest
    fun teardown() {
        keepAlive.close()
    }

    @Test
    fun `add asset and list`() {
        val id = repo.add("Samsung stock", "FINANCIAL", null, 500000.0, "KRW", null)
        val assets = repo.list(null)
        assertEquals(1, assets.size)
        assertEquals("Samsung stock", assets[0].name)
        assertEquals(id, assets[0].id)
    }

    @Test
    fun `list filters by asset_type`() {
        repo.add("Apartment", "PHYSICAL", 300000000.0, 350000000.0, "KRW", null)
        repo.add("BTC", "FINANCIAL", null, 50000.0, "USD", null)
        val physical = repo.list("PHYSICAL")
        assertEquals(1, physical.size)
        assertEquals("Apartment", physical[0].name)
    }

    @Test
    fun `updateValue updates currentValue and records history`() {
        val id = repo.add("BTC", "FINANCIAL", null, 50000.0, "USD", null)
        val updated = repo.updateValue(id, 55000.0)
        assertTrue(updated)
        val assets = repo.list(null)
        assertEquals(55000.0, assets[0].currentValue)
    }

    @Test
    fun `summary groups by type and currency`() {
        repo.add("Stock A", "FINANCIAL", null, 1000.0, "KRW", null)
        repo.add("Stock B", "FINANCIAL", null, 2000.0, "KRW", null)
        repo.add("BTC", "FINANCIAL", null, 100.0, "USD", null)
        repo.add("Apartment", "PHYSICAL", null, 500000.0, "KRW", null)
        val summary = repo.summary()
        assertEquals(3000.0, summary["FINANCIAL"]?.get("KRW"))
        assertEquals(100.0, summary["FINANCIAL"]?.get("USD"))
        assertEquals(500000.0, summary["PHYSICAL"]?.get("KRW"))
    }

    @Test
    fun `updateValue returns false for nonexistent asset`() {
        val updated = repo.updateValue(999, 1000.0)
        assertTrue(!updated)
    }
}
