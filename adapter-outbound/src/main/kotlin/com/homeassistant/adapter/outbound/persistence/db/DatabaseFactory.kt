package com.homeassistant.adapter.outbound.persistence.db

import com.homeassistant.adapter.outbound.persistence.db.tables.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

internal object DatabaseFactory {
    fun init(dbPath: String): Database {
        val db = Database.connect("jdbc:sqlite:$dbPath", driver = "org.sqlite.JDBC")
        transaction(db) {
            migrateLegacyTableNames()
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
                GroupReceiptTable,
                SourceReferenceTable,
                SourceRecordTable,
                SourceRecordViewerTable,
                ComponentAssignmentOutboxTable,
                EpisodeConversationSessionTable,
                EpisodeConversationActiveSessionTable,
                EpisodeConversationReceiptTable,
                UserTable,
                UserCredentialTable,
                UserSessionTable,
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

    private fun Transaction.tableExists(name: String): Boolean =
        exec(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '${name.replace("'", "''")}'",
        ) { result -> result.next() } ?: false

    private fun migrateLegacyPrivateAccess() {
        val legacyPrivateEpisodes = EpisodeTable.selectAll()
            .where { EpisodeTable.visibility eq "PRIVATE" }
            .toList()
        legacyPrivateEpisodes.forEach { episode ->
            val episodeId = episode[EpisodeTable.id]
            val creator = episode[EpisodeTable.createdByUserId]
            EpisodeViewerTable.insertIgnore {
                it[EpisodeViewerTable.episodeId] = episodeId
                it[userId] = creator
            }
            EpisodeEvidenceTable.select(EpisodeEvidenceTable.sourceRecordId)
                .where { EpisodeEvidenceTable.episodeId eq episodeId }
                .forEach { evidence ->
                    val sourceRecordId = evidence[EpisodeEvidenceTable.sourceRecordId]
                    SourceRecordTable.update({ SourceRecordTable.id eq sourceRecordId }) {
                        it[visibility] = "RESTRICTED"
                    }
                    SourceRecordViewerTable.insertIgnore {
                        it[SourceRecordViewerTable.sourceRecordId] = sourceRecordId
                        it[userId] = creator
                    }
                }
        }
        if (legacyPrivateEpisodes.isNotEmpty()) {
            EpisodeTable.update({ EpisodeTable.visibility eq "PRIVATE" }) {
                it[visibility] = "RESTRICTED"
            }
        }
    }
}
