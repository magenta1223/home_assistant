package com.homeassistant.adapter.outbound.persistence.db.tables

import org.jetbrains.exposed.sql.Table

internal object SlackCodexSessionTable : Table("slack_codex_sessions") {
    val id = integer("id").autoIncrement()
    val teamId = text("team_id")
    val slackUserId = text("slack_user_id")
    val userId = text("user_id")
    val familyId = text("family_id")
    val codexThreadId = text("codex_thread_id").uniqueIndex()
    val createdAt = long("created_at")
    val lastActiveAt = long("last_active_at")
    override val primaryKey = PrimaryKey(id)
}

internal object SlackCodexActiveSessionTable : Table("slack_codex_active_sessions") {
    val teamId = text("team_id")
    val slackUserId = text("slack_user_id")
    val sessionId = integer("session_id").references(SlackCodexSessionTable.id)
    override val primaryKey = PrimaryKey(teamId, slackUserId)
}

internal object SlackMessageReceiptTable : Table("slack_message_receipts") {
    val channelId = text("channel_id")
    val messageTs = text("message_ts")
    val sessionId = integer("session_id").references(SlackCodexSessionTable.id).nullable()
    val status = text("status")
    val answerText = text("answer_text").nullable()
    val responseTs = text("response_ts").nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(channelId, messageTs)
}
