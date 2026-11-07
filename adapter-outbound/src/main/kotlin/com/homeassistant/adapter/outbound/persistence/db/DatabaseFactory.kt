package com.homeassistant.adapter.outbound.persistence.db

import com.homeassistant.adapter.outbound.persistence.db.tables.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

internal object DatabaseFactory {
    fun init(dbPath: String): Database {
        val db = Database.connect("jdbc:sqlite:$dbPath", driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.createMissingTablesAndColumns(
                EpisodeTable,
                EpisodeEvidenceTable,
                EpisodeViewerTable,
                ComponentTable,
                ComponentExtractionTable,
                EpisodeEmbeddingTable,
                ComponentEmbeddingTable,
                GroupTable,
                GroupMembershipTable,
                SourceReferenceTable,
                SourceRecordTable,
                SourceRecordViewerTable,
                ComponentAssignmentOutboxTable,
                ChatSessionTable,
                ActiveChatSessionTable,
                ChatReceiptTable,
                UserTable,
                UserCredentialTable,
                UserSessionTable,
            )
        }
        return db
    }
}
