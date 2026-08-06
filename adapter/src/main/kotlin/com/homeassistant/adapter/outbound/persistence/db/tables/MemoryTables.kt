package com.homeassistant.adapter.outbound.persistence.db.tables

import org.jetbrains.exposed.sql.Table

internal object TopicTable : Table("topics") {
    val id = integer("id").autoIncrement()
    val createdByUserId = text("created_by_user_id")
    val sourceType = text("source_type")
    val sourceName = text("source_name")
    val title = text("title")
    val summary = text("summary")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)

    init {
        index(false, sourceType, sourceName, title)
    }
}

internal object CategoryTable : Table("categories") {
    val id = integer("id").autoIncrement()
    val name = text("name").uniqueIndex()
    override val primaryKey = PrimaryKey(id)
}

internal object TopicCategoryTable : Table("topic_categories") {
    val topicId = integer("topic_id").references(TopicTable.id)
    val categoryId = integer("category_id").references(CategoryTable.id)
    override val primaryKey = PrimaryKey(topicId, categoryId)
}

internal object MemoryTable : Table("memories") {
    val id = integer("id").autoIncrement()
    val topicId = integer("topic_id").references(TopicTable.id).nullable()
    val createdByUserId = text("created_by_user_id")
    val content = text("content")
    val subject = text("subject")
    val memoryType = text("memory_type")
    val certainty = text("certainty")
    val visibility = text("visibility")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)

    init {
        index(false, topicId)
        index(false, createdByUserId, visibility)
        index(false, memoryType)
    }
}

internal object MemoryEvidenceTable : Table("memory_evidence") {
    val memoryId = integer("memory_id").references(MemoryTable.id)
    val sourceRecordId = integer("source_record_id").references(KakaoImportedMessageTable.id)
    override val primaryKey = PrimaryKey(memoryId, sourceRecordId)

    init {
        index(false, sourceRecordId)
    }
}

internal object SchemaMigrationTable : Table("schema_migrations") {
    val version = integer("version")
    val appliedAt = long("applied_at")
    override val primaryKey = PrimaryKey(version)
}
