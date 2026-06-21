package com.homeassistant.domain.db.tables

import org.jetbrains.exposed.sql.Table

/** Stores short-lived topic analysis previews before the user saves them. */
object TopicAnalysisPreviewTable : Table("topic_analysis_previews") {
    val previewId = text("preview_id")
    val sourceFileName = text("source_file_name")
    val text = text("text")
    val topicsJson = text("topics_json")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(previewId)
}
