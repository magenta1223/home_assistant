package com.homeassistant.adapter.outbound.persistence.db

import com.homeassistant.adapter.outbound.persistence.db.tables.*
import com.homeassistant.adapter.outbound.persistence.repo.indexing.enqueueIndex
import com.homeassistant.adapter.shared.json.JsonSerializer.decodeFromString
import com.homeassistant.domain.indexing.IndexTargetType
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.memory.MemoryVisibility
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll

internal fun Transaction.archiveLegacyMemoryTable() {
    if (!tableExists("memories")) return
    if (tableColumns("memories").contains("topic_id")) return
    check(!tableExists("legacy_memories")) {
        "Both legacy memories and legacy_memories tables exist"
    }
    exec("ALTER TABLE memories RENAME TO legacy_memories")
}

internal fun Transaction.migrateLegacyTopics() {
    if (SchemaMigrationTable.selectAll().where { SchemaMigrationTable.version eq CANONICAL_MEMORY_VERSION }.any()) {
        return
    }

    if (tableExists("topic_candidates")) {
        LegacyTopicCandidateTable.selectAll()
            .where { LegacyTopicCandidateTable.status eq LEGACY_APPROVED_STATUS }
            .forEach(::migrateLegacyTopic)
    }

    SchemaMigrationTable.insert {
        it[version] = CANONICAL_MEMORY_VERSION
        it[appliedAt] = System.currentTimeMillis()
    }
}

private fun Transaction.migrateLegacyTopic(row: ResultRow) {
    val legacyTopicId = row[LegacyTopicCandidateTable.id]
    if (TopicTable.selectAll().where { TopicTable.id eq legacyTopicId }.any()) return

    TopicTable.insert {
        it[id] = legacyTopicId
        it[createdByUserId] = row[LegacyTopicCandidateTable.createdByUserId]
        it[sourceType] = row[LegacyTopicCandidateTable.sourceType]
        it[sourceName] = row[LegacyTopicCandidateTable.sourceName]
        it[title] = row[LegacyTopicCandidateTable.title]
        it[summary] = row[LegacyTopicCandidateTable.summary]
        it[createdAt] = row[LegacyTopicCandidateTable.createdAt]
        it[updatedAt] = row[LegacyTopicCandidateTable.updatedAt]
    }

    row[LegacyTopicCandidateTable.domainsJson]
        .decodeFromString<List<String>>()
        .distinct()
        .forEach { category -> linkCategory(legacyTopicId, category) }

    row[LegacyTopicCandidateTable.claimsJson]
        .decodeFromString<List<LegacyPersistedMemory>>()
        .forEach { memory ->
            val memoryId = MemoryTable.insert {
                it[topicId] = legacyTopicId
                it[createdByUserId] = row[LegacyTopicCandidateTable.createdByUserId]
                it[content] = memory.text
                it[subject] = memory.subject
                it[memoryType] = memory.memoryType.code
                it[certainty] = memory.certainty.name
                it[visibility] = memory.visibility.name
                it[createdAt] = row[LegacyTopicCandidateTable.createdAt]
                it[updatedAt] = row[LegacyTopicCandidateTable.updatedAt]
            }[MemoryTable.id]
            memory.evidenceRefs.distinct().forEach { sourceRecordId ->
                MemoryEvidenceTable.insert {
                    it[MemoryEvidenceTable.memoryId] = memoryId
                    it[MemoryEvidenceTable.sourceRecordId] = sourceRecordId
                }
            }
            enqueueIndex(IndexTargetType.MEMORY, memoryId)
        }
}

private fun Transaction.linkCategory(topicId: Int, rawCategory: String) {
    val category = rawCategory.trim().lowercase()
    if (category.isBlank()) return
    val categoryId = CategoryTable.selectAll()
        .where { CategoryTable.name eq category }
        .singleOrNull()
        ?.get(CategoryTable.id)
        ?: CategoryTable.insert {
            it[name] = category
        }[CategoryTable.id]
    if (TopicCategoryTable.selectAll().where {
            (TopicCategoryTable.topicId eq topicId) and
                (TopicCategoryTable.categoryId eq categoryId)
        }.none()
    ) {
        TopicCategoryTable.insert {
            it[TopicCategoryTable.topicId] = topicId
            it[TopicCategoryTable.categoryId] = categoryId
        }
    }
}

private fun Transaction.tableExists(name: String): Boolean =
    exec(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '$name' LIMIT 1",
    ) { result -> result.next() } ?: false

private fun Transaction.tableColumns(name: String): Set<String> =
    exec("PRAGMA table_info('$name')") { result ->
        buildSet {
            while (result.next()) add(result.getString("name"))
        }
    }.orEmpty()

@Serializable
private data class LegacyPersistedMemory(
    val text: String,
    val subject: String,
    val memoryType: MemoryType,
    val certainty: MemoryCertainty,
    val evidenceRefs: List<Int>,
    val visibility: MemoryVisibility = MemoryVisibility.FAMILY,
)

private const val CANONICAL_MEMORY_VERSION = 1
private const val LEGACY_APPROVED_STATUS = "APPROVED"
