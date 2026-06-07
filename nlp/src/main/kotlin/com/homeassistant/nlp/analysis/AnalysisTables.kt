package com.homeassistant.nlp.analysis

import org.jetbrains.exposed.sql.Table

object TopicCandidateTable : Table("topic_candidates") {
    val id = integer("id").autoIncrement()
    val sourceType = text("source_type")
    val sourceName = text("source_name")
    val title = text("title")
    val summary = text("summary")
    val status = text("status")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object TopicMemoryTypeTable : Table("topic_memory_types") {
    val topicId = integer("topic_id").references(TopicCandidateTable.id)
    val memoryType = text("memory_type")
    override val primaryKey = PrimaryKey(topicId, memoryType)
}

object TopicDomainTable : Table("topic_domains") {
    val topicId = integer("topic_id").references(TopicCandidateTable.id)
    val domain = text("domain")
    override val primaryKey = PrimaryKey(topicId, domain)
}

object TopicEvidenceTable : Table("topic_evidence") {
    val topicId = integer("topic_id").references(TopicCandidateTable.id)
    val sourceRecordId = text("source_record_id")
    val sourceRecordRef = integer("source_record_ref")
    override val primaryKey = PrimaryKey(topicId, sourceRecordId)
}
