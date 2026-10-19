package com.homeassistant.adapter.outbound.persistence.db.tables

import org.jetbrains.exposed.sql.Table

internal object TopicTable : Table("topics") {
    val id = integer("id").autoIncrement()
    val creationKey = text("creation_key").uniqueIndex()
    val description = text("description")
    override val primaryKey = PrimaryKey(id)
}

internal object TopicMembershipTable : Table("topic_memberships") {
    val topicId = integer("topic_id").references(TopicTable.id)
    val category = text("category")
    val memoryId = integer("memory_id").references(MemoryTable.id)
    override val primaryKey = PrimaryKey(topicId, category, memoryId)
}

internal object TopicRelationTable : Table("topic_relations") {
    val firstTopicId = integer("first_topic_id").references(TopicTable.id)
    val secondTopicId = integer("second_topic_id").references(TopicTable.id)
    override val primaryKey = PrimaryKey(firstTopicId, secondTopicId)

    init {
        check { firstTopicId less secondTopicId }
    }
}

internal object MemoryEmbeddingTable : Table("memory_embeddings") {
    val key = text("cache_key")
    val memoryId = integer("memory_id").references(MemoryTable.id)
    val contentHash = text("content_hash")
    val model = text("model")
    val inputFormat = text("input_format")
    val vector = text("vector")
    override val primaryKey = PrimaryKey(key)
}

/** Topics created by Memory organization. Revision counts membership changes; summaries follow it. */
internal object TopicOrganizationTable : Table("topic_organization") {
    val topicId = integer("topic_id").references(TopicTable.id)
    val revision = long("revision")
    val describedRevision = long("described_revision")
    val centroid = text("centroid")
    override val primaryKey = PrimaryKey(topicId)
}

internal object TopicOrganizedMemoryTable : Table("topic_organized_memories") {
    val memoryId = integer("memory_id").references(MemoryTable.id)
    val topicId = integer("topic_id").references(TopicOrganizationTable.topicId).index()
    val embeddingKey = text("embedding_key").references(MemoryEmbeddingTable.key)
    override val primaryKey = PrimaryKey(memoryId)
}
