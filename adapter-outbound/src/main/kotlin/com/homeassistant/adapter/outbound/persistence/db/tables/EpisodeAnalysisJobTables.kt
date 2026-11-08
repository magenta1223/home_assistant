package com.homeassistant.adapter.outbound.persistence.db.tables

import org.jetbrains.exposed.sql.Table

internal object EpisodeAnalysisJobTable : Table("episode_analysis_jobs") {
    val id = integer("id").autoIncrement()
    val requestedByUserId = text("requested_by_user_id")
    val sourceType = text("source_type")
    val sourceName = text("source_name")
    val referenceId = integer("reference_id").references(SourceReferenceTable.id).nullable()
    val visibility = text("visibility")
    val status = text("status")
    val attempts = integer("attempts")
    val episodeCount = integer("episode_count").nullable()
    val lastError = text("last_error").nullable()
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

internal object EpisodeAnalysisJobRecordTable : Table("episode_analysis_job_records") {
    val jobId = integer("job_id").references(EpisodeAnalysisJobTable.id)
    val sourceRecordId = integer("source_record_id").references(SourceRecordTable.id)
    override val primaryKey = PrimaryKey(jobId, sourceRecordId)
}

internal object EpisodeAnalysisJobViewerTable : Table("episode_analysis_job_viewers") {
    val jobId = integer("job_id").references(EpisodeAnalysisJobTable.id)
    val userId = text("user_id")
    override val primaryKey = PrimaryKey(jobId, userId)
}
