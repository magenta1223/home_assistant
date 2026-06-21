package com.homeassistant.domain.db.tables

import org.jetbrains.exposed.sql.Table

/** Stores source-agnostic topic candidates produced by NLP analysis. */
object TopicCandidateTable : Table("topic_candidates") {
    val id = integer("id").autoIncrement()
    val sourceType = text("source_type")
    val sourceName = text("source_name")
    val title = text("title")
    val summary = text("summary")
    val status = text("status")
    val memoryTypesJson = text("memory_types_json")
    val domainsJson = text("domains_json")
    val evidenceJson = text("evidence_json")
    val claimsJson = text("claims_json")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}
