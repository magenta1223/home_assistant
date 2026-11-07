package com.homeassistant.adapter.outbound.persistence.repo.episodeconversation

import com.homeassistant.adapter.outbound.persistence.db.tables.ActiveChatSessionTable
import com.homeassistant.adapter.outbound.persistence.db.tables.ChatSessionTable
import com.homeassistant.application.port.outbound.chatgateway.ChatId
import com.homeassistant.application.port.outbound.chatgateway.ChatSession
import com.homeassistant.application.port.outbound.chatgateway.ChatSessionConflict
import com.homeassistant.application.port.outbound.chatgateway.ChatSessionDeactivation
import com.homeassistant.application.port.outbound.chatgateway.ChatSessionRenewal
import com.homeassistant.application.port.outbound.chatgateway.ChatSessionRepository
import com.homeassistant.application.port.outbound.chatgateway.ChatSessionStart
import com.homeassistant.domain.identity.UserId
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

internal class ChatSessionRepositoryImpl(
    private val db: Database,
) : ChatSessionRepository {
    override fun findActiveByUser(userId: UserId): ChatSession? = transaction(db) {
        findActive(ActiveChatSessionTable.userId eq userId.value).singleOrNull()
    }

    override fun save(start: ChatSessionStart): ChatSession = transaction(db) {
        val id = ChatSessionTable.insert {
            it[userId] = start.userId.value
            it[conversationId] = start.chatId.value
            it[createdAt] = start.now
            it[lastActiveAt] = start.now
        }[ChatSessionTable.id]
        ActiveChatSessionTable.deleteWhere { userId eq start.userId.value }
        ActiveChatSessionTable.insert {
            it[userId] = start.userId.value
            it[sessionId] = id
        }
        sessionRow(id).toSession()
    }

    override fun save(renewal: ChatSessionRenewal): ChatSession = transaction(db) {
        findActive(isActive(renewal.userId, renewal.sessionId)).singleOrNull()
            ?: throw ChatSessionConflict(renewal.sessionId)
        ChatSessionTable.update({ ChatSessionTable.id eq renewal.sessionId }) {
            it[lastActiveAt] = renewal.now
        }
        sessionRow(renewal.sessionId).toSession()
    }

    override fun save(deactivation: ChatSessionDeactivation): List<ChatSession> = transaction(db) {
        val deactivated = findActive(
            when (deactivation) {
                is ChatSessionDeactivation.OfSession -> isActive(deactivation.userId, deactivation.sessionId)
                is ChatSessionDeactivation.IdleSince -> ChatSessionTable.lastActiveAt lessEq deactivation.beforeInclusive
            },
        )
        deactivated.forEach { session ->
            ActiveChatSessionTable.deleteWhere { isActive(session.userId, session.id) }
        }
        deactivated
    }

    private fun findActive(condition: Op<Boolean>): List<ChatSession> =
        (ActiveChatSessionTable innerJoin ChatSessionTable)
            .selectAll()
            .where { condition }
            .map { it.toSession() }

    private fun isActive(userId: UserId, sessionId: Int): Op<Boolean> =
        (ActiveChatSessionTable.userId eq userId.value) and (ActiveChatSessionTable.sessionId eq sessionId)

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
