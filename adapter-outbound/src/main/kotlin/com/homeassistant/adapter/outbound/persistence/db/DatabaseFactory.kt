package com.homeassistant.adapter.outbound.persistence.db

import com.homeassistant.adapter.outbound.persistence.db.tables.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

internal object DatabaseFactory {
    fun init(dbPath: String): Database {
        val db = Database.connect("jdbc:sqlite:$dbPath", driver = "org.sqlite.JDBC")
        transaction(db) {
            migrateLegacyTableNames()
            migrateTopicMembershipKey()
            SchemaUtils.createMissingTablesAndColumns(
                MemoryTable,
                MemoryEvidenceTable,
                MemoryViewerTable,
                TopicTable,
                TopicMembershipTable,
                TopicDescriptionTable,
                MemoryEmbeddingTable,
                TopicEmbeddingTable,
                GroupTable,
                GroupMembershipTable,
                GroupReceiptTable,
                SourceReferenceTable,
                SourceRecordTable,
                SourceRecordViewerTable,
                TopicAssignmentOutboxTable,
                MemoryConversationSessionTable,
                MemoryConversationActiveSessionTable,
                MemoryConversationReceiptTable,
                UserTable,
            )
            migrateLegacyPrivateAccess()
        }
        return db
    }

    private fun Transaction.migrateLegacyTableNames() {
        renameTableIfNeeded("household_members", "registered_users")
    }

    private fun Transaction.renameTableIfNeeded(legacyName: String, currentName: String) {
        if (!tableExists(legacyName) || tableExists(currentName)) return
        exec("ALTER TABLE \"$legacyName\" RENAME TO \"$currentName\"")
    }

    /** Memberships were keyed by memory alone (one Topic per memory). SQLite cannot change a key in place. */
    private fun Transaction.migrateTopicMembershipKey() {
        if (!tableExists("topic_memberships")) return
        val keyColumns = exec("PRAGMA table_info(\"topic_memberships\")") { result ->
            buildList { while (result.next()) if (result.getInt("pk") > 0) add(result.getString("name")) }
        }
        if (keyColumns != listOf("memory_id")) return
        exec("ALTER TABLE topic_memberships RENAME TO topic_memberships_single")
        SchemaUtils.create(TopicMembershipTable)
        exec("INSERT INTO topic_memberships (topic_id, memory_id) SELECT topic_id, memory_id FROM topic_memberships_single")
        exec("DROP TABLE topic_memberships_single")
    }

    private fun Transaction.tableExists(name: String): Boolean =
        exec(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '${name.replace("'", "''")}'",
        ) { result -> result.next() } ?: false

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
