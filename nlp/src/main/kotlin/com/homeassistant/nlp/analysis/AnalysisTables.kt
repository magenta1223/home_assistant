package com.homeassistant.nlp.analysis

import org.jetbrains.exposed.sql.Table

/** Stores source-agnostic topic candidates produced by NLP analysis. */
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

/** Stores one or more memory types per topic candidate. */
object TopicClassificationTable : Table("topic_classifications") {
    val topicId = integer("topic_id").references(TopicCandidateTable.id)
    val memoryType = text("memory_type")
    override val primaryKey = PrimaryKey(topicId, memoryType)
}

/** Stores free-form domain tags attached to a topic candidate. */
object TopicDomainTable : Table("topic_domains") {
    val topicId = integer("topic_id").references(TopicCandidateTable.id)
    val domain = text("domain")
    override val primaryKey = PrimaryKey(topicId, domain)
}

/** Stores source record evidence links for each topic candidate. */
object TopicEvidenceTable : Table("topic_evidence") {
    val topicId = integer("topic_id").references(TopicCandidateTable.id)
    val sourceRecordId = text("source_record_id")
    val sourceRecordRef = integer("source_record_ref")
    override val primaryKey = PrimaryKey(topicId, sourceRecordId)
}

/** Stores evidence-backed claims extracted under topic candidates. */
object TopicClaimTable : Table("topic_claims") {
    val id = integer("id").autoIncrement()
    val topicId = integer("topic_id").references(TopicCandidateTable.id)
    val text = text("text")
    val subject = text("subject")
    val memoryType = text("memory_type")
    val certainty = text("certainty")
    override val primaryKey = PrimaryKey(id)
}

/** Stores source record evidence links for each topic claim. */
object TopicClaimEvidenceTable : Table("topic_claim_evidence") {
    val claimId = integer("claim_id").references(TopicClaimTable.id)
    val sourceRecordId = text("source_record_id")
    val sourceRecordRef = integer("source_record_ref")
    override val primaryKey = PrimaryKey(claimId, sourceRecordId)
}
