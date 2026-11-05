package com.homeassistant.adapter.outbound.persistence.db.tables

import org.jetbrains.exposed.sql.Table

internal object ComponentEmbeddingTable : Table("component_embeddings") {
    val key = text("cache_key")
    val componentId = integer("component_id").references(ComponentTable.id)
    val version = text("version")
    val model = text("model")
    val inputFormat = text("input_format")
    val vector = text("vector")
    override val primaryKey = PrimaryKey(key)
}

internal object GroupTable : Table("component_groups") {
    val id = integer("id").autoIncrement()
    val revision = long("revision")
    val centroid = text("centroid")
    override val primaryKey = PrimaryKey(id)
}

internal object GroupMembershipTable : Table("component_group_memberships") {
    val componentId = integer("component_id").references(ComponentTable.id)
    val groupId = integer("group_id").references(GroupTable.id).index()
    val embeddingKey = text("embedding_key").references(ComponentEmbeddingTable.key)
    override val primaryKey = PrimaryKey(componentId)
}

internal object GroupReceiptTable : Table("component_group_receipts") {
    val operationKey = text("operation_key")
    val request = text("request")
    val groupIds = text("group_ids")
    override val primaryKey = PrimaryKey(operationKey)
}
