package com.homeassistant.adapter.outbound.persistence.db

import com.homeassistant.adapter.outbound.persistence.db.tables.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

internal object DatabaseFactory {
    fun init(dbPath: String): Database {
        val db = Database.connect("jdbc:sqlite:$dbPath", driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.createMissingTablesAndColumns(
                TopicTable,
                CategoryTable,
                TopicCategoryTable,
                MemoryTable,
                MemoryEvidenceTable,
                SourceRecordTable,
                TopicAnalysisReviewTable,
                IndexingOutboxTable,
                SlackCodexSessionTable,
                SlackCodexActiveSessionTable,
                SlackMessageReceiptTable,
            )
        }
        return db
    }
}
