package com.homeassistant.adapter.outbound.persistence.db.tables

import org.jetbrains.exposed.sql.Table

// Physical names are retained because HTTP sessions and request receipts already use this schema.
// These mappings do not enable any messaging-channel integration.
internal object MemoryConversationSessionTable : Table("slack_codex_sessions") {
    val id = integer("id").autoIncrement()
    val scopeId = text("team_id")
    val participantId = text("slack_user_id")
    val userId = text("user_id")
    val conversationId = text("codex_thread_id").uniqueIndex()
    val createdAt = long("created_at")
    val lastActiveAt = long("last_active_at")
    override val primaryKey = PrimaryKey(id)
}

internal object MemoryConversationActiveSessionTable : Table("slack_codex_active_sessions") {
    val scopeId = text("team_id")
    val participantId = text("slack_user_id")
    val sessionId = integer("session_id").references(MemoryConversationSessionTable.id)
    override val primaryKey = PrimaryKey(scopeId, participantId)
}

internal object MemoryConversationReceiptTable : Table("slack_message_receipts") {
    val streamId = text("channel_id")
    val requestId = text("message_ts")
    val sessionId = integer("session_id").references(MemoryConversationSessionTable.id).nullable()
    val status = text("status")
    val answerText = text("answer_text").nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(streamId, requestId)
}
