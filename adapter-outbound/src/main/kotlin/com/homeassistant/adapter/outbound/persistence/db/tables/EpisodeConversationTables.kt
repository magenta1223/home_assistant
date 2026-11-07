package com.homeassistant.adapter.outbound.persistence.db.tables

import org.jetbrains.exposed.sql.Table

internal object ChatSessionTable : Table("chat_sessions") {
    val id = integer("id").autoIncrement()
    val userId = text("user_id")
    val conversationId = text("codex_thread_id").uniqueIndex()
    val createdAt = long("created_at")
    val lastActiveAt = long("last_active_at")
    override val primaryKey = PrimaryKey(id)
}

internal object ActiveChatSessionTable : Table("chat_active_sessions") {
    val userId = text("user_id")
    val sessionId = integer("session_id").references(ChatSessionTable.id)
    override val primaryKey = PrimaryKey(userId)
}
