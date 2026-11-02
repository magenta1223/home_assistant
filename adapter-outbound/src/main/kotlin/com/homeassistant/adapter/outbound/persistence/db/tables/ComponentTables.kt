package com.homeassistant.adapter.outbound.persistence.db.tables

import org.jetbrains.exposed.sql.Table

/** [vectorSum] is the unnormalized sum of member episode vectors; [episodeCount] counts them and only grows. */
internal object ComponentTable : Table("components") {
    val id = integer("id").autoIncrement()
    val vectorSum = text("vector_sum")
    val episodeCount = integer("episode_count")
    override val primaryKey = PrimaryKey(id)
}

/** An episode may belong to several Components, once each. Its slice is read from the episode's current access. */
internal object ComponentMembershipTable : Table("component_memberships") {
    val componentId = integer("component_id").references(ComponentTable.id)
    val episodeId = integer("episode_id").references(EpisodeTable.id).index()
    override val primaryKey = PrimaryKey(componentId, episodeId)
}

/** Regenerable read model: one description per Component and [view] (the set of slices a viewer reads). */
internal object ComponentDescriptionTable : Table("component_descriptions") {
    val componentId = integer("component_id").references(ComponentTable.id)
    val view = text("view")
    val sliceVersions = text("slice_versions")
    val description = text("description")
    override val primaryKey = PrimaryKey(componentId, view)
}

internal object EpisodeEmbeddingTable : Table("episode_embeddings") {
    val key = text("cache_key")
    val episodeId = integer("episode_id").references(EpisodeTable.id)
    val contentHash = text("content_hash")
    val model = text("model")
    val inputFormat = text("input_format")
    val vector = text("vector")
    override val primaryKey = PrimaryKey(key)
}
