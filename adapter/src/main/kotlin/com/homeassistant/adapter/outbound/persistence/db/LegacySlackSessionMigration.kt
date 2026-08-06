package com.homeassistant.adapter.outbound.persistence.db

import com.homeassistant.adapter.outbound.persistence.db.tables.SlackCodexActiveSessionTable
import com.homeassistant.adapter.outbound.persistence.db.tables.SlackCodexSessionTable
import com.homeassistant.adapter.outbound.persistence.db.tables.SlackMessageReceiptTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.insert

internal fun Transaction.migrateLegacySlackSessionSchema() {
    if (!tableColumns("slack_codex_sessions").contains("family_id")) return

    val sessions = queryLegacySessions()
    val activeSessions = queryLegacyActiveSessions()
    val receipts = queryLegacyReceipts()

    exec("DROP TABLE IF EXISTS slack_message_receipts")
    exec("DROP TABLE IF EXISTS slack_codex_active_sessions")
    exec("DROP TABLE slack_codex_sessions")
    SchemaUtils.create(
        SlackCodexSessionTable,
        SlackCodexActiveSessionTable,
        SlackMessageReceiptTable,
    )

    sessions.forEach { row ->
        SlackCodexSessionTable.insert {
            it[id] = row.id
            it[teamId] = row.teamId
            it[slackUserId] = row.slackUserId
            it[userId] = row.userId
            it[codexThreadId] = row.codexThreadId
            it[createdAt] = row.createdAt
            it[lastActiveAt] = row.lastActiveAt
        }
    }
    activeSessions.forEach { row ->
        SlackCodexActiveSessionTable.insert {
            it[teamId] = row.teamId
            it[slackUserId] = row.slackUserId
            it[sessionId] = row.sessionId
        }
    }
    receipts.forEach { row ->
        SlackMessageReceiptTable.insert {
            it[channelId] = row.channelId
            it[messageTs] = row.messageTs
            it[sessionId] = row.sessionId
            it[status] = row.status
            it[answerText] = row.answerText
            it[responseTs] = row.responseTs
            it[createdAt] = row.createdAt
            it[updatedAt] = row.updatedAt
        }
    }
}

private fun Transaction.queryLegacySessions(): List<LegacySession> =
    exec(
        "SELECT id, team_id, slack_user_id, user_id, codex_thread_id, created_at, last_active_at " +
            "FROM slack_codex_sessions",
    ) { result ->
        buildList {
            while (result.next()) {
                add(
                    LegacySession(
                        id = result.getInt("id"),
                        teamId = result.getString("team_id"),
                        slackUserId = result.getString("slack_user_id"),
                        userId = result.getString("user_id"),
                        codexThreadId = result.getString("codex_thread_id"),
                        createdAt = result.getLong("created_at"),
                        lastActiveAt = result.getLong("last_active_at"),
                    ),
                )
            }
        }
    }.orEmpty()

private fun Transaction.queryLegacyActiveSessions(): List<LegacyActiveSession> =
    if (!tableExists("slack_codex_active_sessions")) emptyList() else
        exec("SELECT team_id, slack_user_id, session_id FROM slack_codex_active_sessions") { result ->
            buildList {
                while (result.next()) {
                    add(LegacyActiveSession(result.getString(1), result.getString(2), result.getInt(3)))
                }
            }
        }.orEmpty()

private fun Transaction.queryLegacyReceipts(): List<LegacyReceipt> =
    if (!tableExists("slack_message_receipts")) emptyList() else
        exec(
            "SELECT channel_id, message_ts, session_id, status, answer_text, response_ts, created_at, updated_at " +
                "FROM slack_message_receipts",
        ) { result ->
            buildList {
                while (result.next()) {
                    add(
                        LegacyReceipt(
                            channelId = result.getString("channel_id"),
                            messageTs = result.getString("message_ts"),
                            sessionId = result.getInt("session_id").takeUnless { result.wasNull() },
                            status = result.getString("status"),
                            answerText = result.getString("answer_text"),
                            responseTs = result.getString("response_ts"),
                            createdAt = result.getLong("created_at"),
                            updatedAt = result.getLong("updated_at"),
                        ),
                    )
                }
            }
        }.orEmpty()

private fun Transaction.tableExists(name: String): Boolean =
    exec("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '$name' LIMIT 1") { it.next() } ?: false

private fun Transaction.tableColumns(name: String): Set<String> =
    exec("PRAGMA table_info('$name')") { result ->
        buildSet { while (result.next()) add(result.getString("name")) }
    }.orEmpty()

private data class LegacySession(
    val id: Int,
    val teamId: String,
    val slackUserId: String,
    val userId: String,
    val codexThreadId: String,
    val createdAt: Long,
    val lastActiveAt: Long,
)

private data class LegacyActiveSession(val teamId: String, val slackUserId: String, val sessionId: Int)

private data class LegacyReceipt(
    val channelId: String,
    val messageTs: String,
    val sessionId: Int?,
    val status: String,
    val answerText: String?,
    val responseTs: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
