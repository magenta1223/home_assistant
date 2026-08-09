package com.homeassistant.adapter.outbound.persistence.db

import com.homeassistant.adapter.outbound.persistence.db.tables.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

internal object DatabaseFactory {
    fun init(dbPath: String): Database {
        val db = Database.connect("jdbc:sqlite:$dbPath", driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.createMissingTablesAndColumns(
                MemoryTable,
                MemoryEvidenceTable,
                MemoryViewerTable,
                SourceRecordTable,
                SourceRecordViewerTable,
                IndexingOutboxTable,
                SlackCodexSessionTable,
                SlackCodexActiveSessionTable,
                SlackMessageReceiptTable,
                HouseholdMemberTable,
                ConversationIdentityTable,
            )
            migrateLegacyPrivateAccess()
        }
        return db
    }

    private fun migrateLegacyPrivateAccess() {
        val legacyPrivateMemories = MemoryTable.selectAll()
            .where { MemoryTable.visibility eq "PRIVATE" }
            .toList()
        legacyPrivateMemories.forEach { memory ->
            val memoryId = memory[MemoryTable.id]
            val creator = memory[MemoryTable.createdByUserId]
            MemoryViewerTable.insertIgnore {
                it[MemoryViewerTable.memoryId] = memoryId
                it[userId] = creator
            }
            MemoryEvidenceTable.select(MemoryEvidenceTable.sourceRecordId)
                .where { MemoryEvidenceTable.memoryId eq memoryId }
                .forEach { evidence ->
                    val sourceRecordId = evidence[MemoryEvidenceTable.sourceRecordId]
                    SourceRecordTable.update({ SourceRecordTable.id eq sourceRecordId }) {
                        it[visibility] = "RESTRICTED"
                    }
                    SourceRecordViewerTable.insertIgnore {
                        it[SourceRecordViewerTable.sourceRecordId] = sourceRecordId
                        it[userId] = creator
                    }
                }
        }
        if (legacyPrivateMemories.isNotEmpty()) {
            MemoryTable.update({ MemoryTable.visibility eq "PRIVATE" }) {
                it[visibility] = "RESTRICTED"
            }
        }
    }
}
