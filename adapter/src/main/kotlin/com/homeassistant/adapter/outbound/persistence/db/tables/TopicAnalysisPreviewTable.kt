package com.homeassistant.adapter.outbound.persistence.db.tables

import org.jetbrains.exposed.sql.Table

/** Stores short-lived topic analysis previews before the user saves them. */
internal object TopicAnalysisPreviewTable : Table("topic_analysis_previews") {
    val previewId = text("preview_id")
    val requestedByUserId = text("requested_by_user_id")
    val sourceType = text("source_type")
    val sourceName = text("source_name")
    val topicsJson = text("topics_json")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(previewId)
}
