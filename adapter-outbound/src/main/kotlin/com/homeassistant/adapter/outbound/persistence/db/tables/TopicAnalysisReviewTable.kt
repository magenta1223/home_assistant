package com.homeassistant.adapter.outbound.persistence.db.tables

import org.jetbrains.exposed.sql.Table

/** Stores short-lived topic proposals while they await user review. */
internal object TopicAnalysisReviewTable : Table("topic_analysis_previews") {
    val reviewId = text("preview_id")
    val requestedByUserId = text("requested_by_user_id")
    val sourceType = text("source_type")
    val sourceName = text("source_name")
    val proposalsJson = text("topics_json")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(reviewId)
}
