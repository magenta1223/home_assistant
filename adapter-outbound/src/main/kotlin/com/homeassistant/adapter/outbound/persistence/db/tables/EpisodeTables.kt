package com.homeassistant.adapter.outbound.persistence.db.tables

import org.jetbrains.exposed.sql.Table

internal object EpisodeTable : Table("episodes") {
    val id = integer("id").autoIncrement()
    val createdByUserId = text("created_by_user_id")
    val content = text("content")
    val visibility = text("visibility")
    /** Null only for canonical episodes created before durable batch persistence was introduced. */
    val idempotencyKey = text("idempotency_key").nullable()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)

    init {
        index(false, createdByUserId, visibility)
        uniqueIndex(idempotencyKey)
    }
}

internal object EpisodeEvidenceTable : Table("episode_evidence") {
    val episodeId = integer("episode_id").references(EpisodeTable.id)
    val sourceRecordId = integer("source_record_id").references(SourceRecordTable.id)
    override val primaryKey = PrimaryKey(episodeId, sourceRecordId)

    init {
        index(false, sourceRecordId)
    }
}

internal object EpisodeViewerTable : Table("episode_viewers") {
    val episodeId = integer("episode_id").references(EpisodeTable.id)
    val userId = text("user_id")
    override val primaryKey = PrimaryKey(episodeId, userId)

    init {
        index(false, userId, episodeId)
    }
}
