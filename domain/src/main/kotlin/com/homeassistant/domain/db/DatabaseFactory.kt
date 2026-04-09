package com.homeassistant.domain.db

import com.homeassistant.domain.db.tables.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseFactory {
    fun init(dbPath: String): Database {
        val db = Database.connect("jdbc:sqlite:$dbPath", driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.create(
                TaxonomyTable,
                MemoTable, MemoTaxonomyTable,
                TodoTable, SubtaskTable, TodoTaxonomyTable,
                AssetTable, AssetValueHistoryTable,
                GroceryItemTable, GroceryPurchaseTable,
            )
        }
        return db
    }
}
