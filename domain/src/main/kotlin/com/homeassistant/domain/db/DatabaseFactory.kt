package com.homeassistant.domain.db

import com.homeassistant.domain.db.tables.*
import com.homeassistant.nlp.analysis.TopicCandidateTable
import com.homeassistant.nlp.analysis.TopicClaimEvidenceTable
import com.homeassistant.nlp.analysis.TopicClaimTable
import com.homeassistant.nlp.analysis.TopicDomainTable
import com.homeassistant.nlp.analysis.TopicEvidenceTable
import com.homeassistant.nlp.analysis.TopicClassificationTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseFactory {
    fun init(dbPath: String): Database {
        val db = Database.connect("jdbc:sqlite:$dbPath", driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.create(
                FamilyTable,
                FamilyMemberTable,
                DomainTable,
                ConversationMessageTable,
                MemoryCandidateTable,
                MemoryTable,
                AuditLogTable,
                KakaoImportedMessageTable,
                TopicCandidateTable,
                TopicClassificationTable,
                TopicDomainTable,
                TopicEvidenceTable,
                TopicClaimTable,
                TopicClaimEvidenceTable,
            )
        }
        return db
    }
}
