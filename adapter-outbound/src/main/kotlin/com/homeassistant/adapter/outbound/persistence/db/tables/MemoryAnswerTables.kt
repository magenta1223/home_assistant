package com.homeassistant.adapter.outbound.persistence.db.tables

import org.jetbrains.exposed.sql.Table

internal object PendingRegistrationQuestionTable : Table("pending_registration_questions") {
    val scopeId = text("scope_id")
    val participantId = text("participant_id")
    val streamId = text("stream_id")
    val requestId = text("request_id")
    val question = text("question")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(scopeId, participantId)
}
