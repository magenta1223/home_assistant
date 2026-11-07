package com.homeassistant.adapter.outbound.persistence.repo.episodeconversation

import com.homeassistant.application.port.outbound.chatgateway.ChatSession
import com.homeassistant.application.port.outbound.chatgateway.ChatSessionLease
import com.homeassistant.application.port.outbound.chatgateway.ChatSessionStore
import com.homeassistant.application.port.outbound.chatgateway.ChatId
import com.homeassistant.domain.identity.UserId
import com.homeassistant.adapter.outbound.persistence.db.tables.ActiveChatSessionTable
import com.homeassistant.adapter.outbound.persistence.db.tables.ChatSessionTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

internal class ChatSessionRepository(
    private val db: Database,
) : ChatSessionStore {
    override fun createAndActivate(
        userId: UserId,
        chatId: ChatId,
        now: Long,
    ): ChatSession = transaction(db) {
        val id = ChatSessionTable.insert {
            it[ChatSessionTable.userId] = userId.value
            it[ChatSessionTable.conversationId] = chatId.value
            it[createdAt] = now
            it[lastActiveAt] = now
        }[ChatSessionTable.id]

        deleteActive(userId)
        ActiveChatSessionTable.insert {
            it[ActiveChatSessionTable.userId] = userId.value
            it[sessionId] = id
        }
        sessionRow(id).toSession()
    }

    override fun lease(
        userId: UserId,
        now: Long,
        idleTimeoutMillis: Long,
    ): ChatSessionLease = transaction(db) {
        require(idleTimeoutMillis > 0) { "idleTimeoutMillis must be positive" }
        val pointer = ActiveChatSessionTable.selectAll()
            .where {
                ActiveChatSessionTable.userId eq userId.value
            }
            .singleOrNull()
            ?: return@transaction ChatSessionLease.None
        val row = ChatSessionTable.selectAll()
            .where { ChatSessionTable.id eq pointer[ActiveChatSessionTable.sessionId] }
            .singleOrNull()
        if (row == null) {
            deleteActive(userId)
            return@transaction ChatSessionLease.None
        }
        val session = row.toSession()
        if (now - row[ChatSessionTable.lastActiveAt] >= idleTimeoutMillis) {
            deleteActive(userId)
            return@transaction ChatSessionLease.Expired(session)
        }
        ChatSessionTable.update({ ChatSessionTable.id eq session.id }) {
            it[lastActiveAt] = now
        }
        ChatSessionLease.Active(session.copy(lastActiveAt = now))
    }

    override fun clearActive(userId: UserId) {
        transaction(db) {
            deleteActive(userId)
        }
    }

    override fun touch(userId: UserId, sessionId: Int, now: Long) {
        transaction(db) {
            val updated = ChatSessionTable.update({
                (ChatSessionTable.id eq sessionId) and
                    (ChatSessionTable.userId eq userId.value)
            }) {
                it[lastActiveAt] = now
            }
            check(updated == 1) { "Session ownership mismatch" }
        }
    }

    override fun expireIdle(beforeInclusive: Long): List<ChatSession> = transaction(db) {
        val expired = (ActiveChatSessionTable innerJoin ChatSessionTable)
            .selectAll()
            .where { ChatSessionTable.lastActiveAt lessEq beforeInclusive }
            .map { it.toSession() }
        expired.forEach { deleteActive(it.userId) }
        expired
    }

    private fun deleteActive(userId: UserId) {
        ActiveChatSessionTable.deleteWhere {
            ActiveChatSessionTable.userId eq userId.value
        }
    }

    private fun sessionRow(id: Int): ResultRow =
        ChatSessionTable.selectAll()
            .where { ChatSessionTable.id eq id }
            .single()

    private fun ResultRow.toSession(): ChatSession =
        ChatSession(
            id = this[ChatSessionTable.id],
            userId = UserId(this[ChatSessionTable.userId]),
            chatId = ChatId(this[ChatSessionTable.conversationId]),
            createdAt = this[ChatSessionTable.createdAt],
            lastActiveAt = this[ChatSessionTable.lastActiveAt],
        )
}
