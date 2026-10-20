package com.homeassistant.adapter.outbound.persistence.db.tables

import org.jetbrains.exposed.sql.Table

/** [vectorSum] is the unnormalized sum of member memory vectors; [memoryCount] counts them and only grows. */
internal object TopicTable : Table("topics") {
    val id = integer("id").autoIncrement()
    val vectorSum = text("vector_sum")
    val memoryCount = integer("memory_count")
    override val primaryKey = PrimaryKey(id)
}

/** A memory belongs to at most one Topic. Its slice is read from the memory's current access. */
internal object TopicMembershipTable : Table("topic_memberships") {
    val topicId = integer("topic_id").references(TopicTable.id).index()
    val memoryId = integer("memory_id").references(MemoryTable.id)
    override val primaryKey = PrimaryKey(memoryId)
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
