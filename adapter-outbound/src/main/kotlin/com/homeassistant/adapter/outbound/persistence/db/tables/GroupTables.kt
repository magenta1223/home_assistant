package com.homeassistant.adapter.outbound.persistence.db.tables

import org.jetbrains.exposed.sql.Table

internal object TopicEmbeddingTable : Table("topic_embeddings") {
    val key = text("cache_key")
    val topicId = integer("topic_id").references(TopicTable.id)
    val version = text("version")
    val model = text("model")
    val inputFormat = text("input_format")
    val vector = text("vector")
    override val primaryKey = PrimaryKey(key)
}

internal object GroupTable : Table("topic_groups") {
    val id = integer("id").autoIncrement()
    val revision = long("revision")
    val centroid = text("centroid")
    override val primaryKey = PrimaryKey(id)
}

internal object GroupMembershipTable : Table("topic_group_memberships") {
    val topicId = integer("topic_id").references(TopicTable.id)
    val groupId = integer("group_id").references(GroupTable.id).index()
    val embeddingKey = text("embedding_key").references(TopicEmbeddingTable.key)
    override val primaryKey = PrimaryKey(topicId)
}

internal object GroupReceiptTable : Table("topic_group_receipts") {
    val operationKey = text("operation_key")
    val request = text("request")
    val groupIds = text("group_ids")
    override val primaryKey = PrimaryKey(operationKey)
}
