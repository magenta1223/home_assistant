package com.homeassistant.adapter.outbound.persistence.db.tables

import org.jetbrains.exposed.sql.Table

/** [vectorSum] is the unnormalized sum of member memory vectors; [memoryCount] counts them and only grows. */
internal object TopicTable : Table("topics") {
    val id = integer("id").autoIncrement()
    val vectorSum = text("vector_sum")
    val memoryCount = integer("memory_count")
    override val primaryKey = PrimaryKey(id)
}

/** A memory may belong to several Topics, once each. Its slice is read from the memory's current access. */
internal object TopicMembershipTable : Table("topic_memberships") {
    val topicId = integer("topic_id").references(TopicTable.id)
    val memoryId = integer("memory_id").references(MemoryTable.id).index()
    override val primaryKey = PrimaryKey(topicId, memoryId)
}

/** Regenerable read model: one description per Topic and [view] (the set of slices a viewer reads). */
internal object TopicDescriptionTable : Table("topic_descriptions") {
    val topicId = integer("topic_id").references(TopicTable.id)
    val view = text("view")
    val sliceVersions = text("slice_versions")
    val description = text("description")
    override val primaryKey = PrimaryKey(topicId, view)
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
