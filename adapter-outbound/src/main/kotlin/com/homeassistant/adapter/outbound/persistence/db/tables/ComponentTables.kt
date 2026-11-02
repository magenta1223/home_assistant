package com.homeassistant.adapter.outbound.persistence.db.tables

import org.jetbrains.exposed.sql.Table

/** One long-term statement extracted from [sourceEpisodeId]; never updated. */
internal object ComponentTable : Table("components") {
    val id = integer("id").autoIncrement()
    val statement = text("statement")
    val sourceEpisodeId = integer("source_episode_id").references(EpisodeTable.id).index()
    override val primaryKey = PrimaryKey(id)
}

/** Episodes whose Components were extracted, including those that held none. */
internal object ComponentExtractionTable : Table("component_extractions") {
    val episodeId = integer("episode_id").references(EpisodeTable.id)
    override val primaryKey = PrimaryKey(episodeId)
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
