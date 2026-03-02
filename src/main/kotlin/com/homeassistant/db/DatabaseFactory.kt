package com.homeassistant.db

import com.homeassistant.db.schema.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

object DatabaseFactory {

    fun init(dbPath: String) {
        val absolutePath = File(dbPath).canonicalPath
        // Connect to SQLite file (creates it if not exists)
        Database.connect(
            url = "jdbc:sqlite:$absolutePath",
            driver = "org.sqlite.JDBC",
        )

        // Create tables if they don't exist (idempotent, mirrors migrate.ts)
        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                Memos,
                Schedules,
                HomeStatus,
                ItemLocations,
                Assets,
                Todos,
                Recipes,
                GroceryItems,
                GroceryPurchases,
            )
            // Virtual tables (vec0) are not supported by Exposed — they are created
            // by the TypeScript side via sqlite-vec extension. The Kotlin backend
            // reads from them directly via raw SQL when EmbeddingService is used.
        }
    }
}
