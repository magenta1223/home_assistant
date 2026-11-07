package com.homeassistant.adapter.outbound.persistence.repo.episodeconversation

import com.homeassistant.application.port.inbound.chatservice.ChatRequestKey
import com.homeassistant.application.port.outbound.chatgateway.ChatReceipt
import com.homeassistant.application.port.outbound.chatgateway.ChatRequestStatus
import com.homeassistant.application.port.outbound.chatgateway.ChatSession
import com.homeassistant.application.port.outbound.chatgateway.ChatSessionLease
import com.homeassistant.application.port.outbound.chatgateway.ChatSessionStore
import com.homeassistant.application.port.outbound.chatgateway.ChatId
import com.homeassistant.domain.identity.UserId
import com.homeassistant.adapter.outbound.persistence.db.tables.ActiveChatSessionTable
import com.homeassistant.adapter.outbound.persistence.db.tables.ChatSessionTable
import com.homeassistant.adapter.outbound.persistence.db.tables.ChatReceiptTable
import org.jetbrains.exposed.exceptions.ExposedSQLException
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
    override fun claimRequest(key: ChatRequestKey, now: Long): ChatReceipt? =
        try {
            transaction(db) {
                ChatReceiptTable.insert {
                    it[userId] = key.userId.value
                    it[requestId] = key.requestId
                    it[status] = ChatRequestStatus.PROCESSING.name
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                receiptRow(key).toReceipt()
            }
        } catch (error: ExposedSQLException) {
            if (receipt(key) != null) null else throw error
        }

    override fun receipt(key: ChatRequestKey): ChatReceipt? = transaction(db) {
        ChatReceiptTable.selectAll()
            .where {
                (ChatReceiptTable.userId eq key.userId.value) and
                    (ChatReceiptTable.requestId eq key.requestId)
            }
            .singleOrNull()
            ?.toReceipt()
    }

    override fun attachSession(key: ChatRequestKey, sessionId: Int, now: Long) {
        updateReceipt(key, now) {
            it[ChatReceiptTable.sessionId] = sessionId
        }
    }

    override fun markAnswerReady(key: ChatRequestKey, answer: String, now: Long) {
        require(answer.isNotBlank()) { "answer is required" }
        updateReceipt(key, now) {
            it[status] = ChatRequestStatus.ANSWER_READY.name
            it[answerText] = answer
        }
    }

    override fun markFailed(key: ChatRequestKey, now: Long) {
        updateReceipt(key, now) {
            it[status] = ChatRequestStatus.FAILED.name
        }
    }

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

    override fun failStaleProcessing(before: Long, now: Long): Int = transaction(db) {
        ChatReceiptTable.update({
            (ChatReceiptTable.status eq ChatRequestStatus.PROCESSING.name) and
                (ChatReceiptTable.updatedAt lessEq before)
        }) {
            it[status] = ChatRequestStatus.FAILED.name
            it[updatedAt] = now
        }
    }

    private fun updateReceipt(
        key: ChatRequestKey,
        now: Long,
        update: ChatReceiptTable.(org.jetbrains.exposed.sql.statements.UpdateBuilder<*>) -> Unit,
    ) {
        transaction(db) {
            val count = ChatReceiptTable.update({
                (ChatReceiptTable.userId eq key.userId.value) and
                    (ChatReceiptTable.requestId eq key.requestId)
            }) {
                update(ChatReceiptTable, it)
                it[updatedAt] = now
            }
            check(count == 1) { "Message receipt not found" }
        }
    }

    private fun deleteActive(userId: UserId) {
        ActiveChatSessionTable.deleteWhere {
            ActiveChatSessionTable.userId eq userId.value
        }
    }

    private fun receiptRow(key: ChatRequestKey): ResultRow =
        ChatReceiptTable.selectAll()
            .where {
                (ChatReceiptTable.userId eq key.userId.value) and
                    (ChatReceiptTable.requestId eq key.requestId)
            }
            .single()

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

    private fun ResultRow.toReceipt(): ChatReceipt =
        ChatReceipt(
            key = ChatRequestKey(
                userId = UserId(this[ChatReceiptTable.userId]),
                requestId = this[ChatReceiptTable.requestId],
            ),
            status = ChatRequestStatus.valueOf(this[ChatReceiptTable.status]),
            sessionId = this[ChatReceiptTable.sessionId],
            answerText = this[ChatReceiptTable.answerText],
            createdAt = this[ChatReceiptTable.createdAt],
            updatedAt = this[ChatReceiptTable.updatedAt],
        )
}
