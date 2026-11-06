package com.homeassistant.adapter.outbound.persistence.repo.episodeconversation

import com.homeassistant.application.port.inbound.chatservice.ChatRequestKey
import com.homeassistant.application.port.outbound.chatgateway.ChatReceipt
import com.homeassistant.application.port.outbound.chatgateway.ChatRequestStatus
import com.homeassistant.application.port.outbound.chatgateway.ChatSession
import com.homeassistant.application.port.outbound.chatgateway.ChatSessionLease
import com.homeassistant.application.port.outbound.chatgateway.ChatSessionStore
import com.homeassistant.application.port.outbound.chatgateway.ChatId
import com.homeassistant.domain.identity.UserId
import com.homeassistant.adapter.outbound.persistence.db.tables.EpisodeConversationActiveSessionTable
import com.homeassistant.adapter.outbound.persistence.db.tables.EpisodeConversationSessionTable
import com.homeassistant.adapter.outbound.persistence.db.tables.EpisodeConversationReceiptTable
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
                EpisodeConversationReceiptTable.insert {
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
        EpisodeConversationReceiptTable.selectAll()
            .where {
                (EpisodeConversationReceiptTable.userId eq key.userId.value) and
                    (EpisodeConversationReceiptTable.requestId eq key.requestId)
            }
            .singleOrNull()
            ?.toReceipt()
    }

    override fun attachSession(key: ChatRequestKey, sessionId: Int, now: Long) {
        updateReceipt(key, now) {
            it[EpisodeConversationReceiptTable.sessionId] = sessionId
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
        val id = EpisodeConversationSessionTable.insert {
            it[EpisodeConversationSessionTable.userId] = userId.value
            it[EpisodeConversationSessionTable.conversationId] = chatId.value
            it[createdAt] = now
            it[lastActiveAt] = now
        }[EpisodeConversationSessionTable.id]

        deleteActive(userId)
        EpisodeConversationActiveSessionTable.insert {
            it[EpisodeConversationActiveSessionTable.userId] = userId.value
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
        val pointer = EpisodeConversationActiveSessionTable.selectAll()
            .where {
                EpisodeConversationActiveSessionTable.userId eq userId.value
            }
            .singleOrNull()
            ?: return@transaction ChatSessionLease.None
        val row = EpisodeConversationSessionTable.selectAll()
            .where { EpisodeConversationSessionTable.id eq pointer[EpisodeConversationActiveSessionTable.sessionId] }
            .singleOrNull()
        if (row == null) {
            deleteActive(userId)
            return@transaction ChatSessionLease.None
        }
        val session = row.toSession()
        if (now - row[EpisodeConversationSessionTable.lastActiveAt] >= idleTimeoutMillis) {
            deleteActive(userId)
            return@transaction ChatSessionLease.Expired(session)
        }
        EpisodeConversationSessionTable.update({ EpisodeConversationSessionTable.id eq session.id }) {
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
            val updated = EpisodeConversationSessionTable.update({
                (EpisodeConversationSessionTable.id eq sessionId) and
                    (EpisodeConversationSessionTable.userId eq userId.value)
            }) {
                it[lastActiveAt] = now
            }
            check(updated == 1) { "Session ownership mismatch" }
        }
    }

    override fun expireIdle(beforeInclusive: Long): List<ChatSession> = transaction(db) {
        val expired = (EpisodeConversationActiveSessionTable innerJoin EpisodeConversationSessionTable)
            .selectAll()
            .where { EpisodeConversationSessionTable.lastActiveAt lessEq beforeInclusive }
            .map { it.toSession() }
        expired.forEach { deleteActive(it.userId) }
        expired
    }

    override fun failStaleProcessing(before: Long, now: Long): Int = transaction(db) {
        EpisodeConversationReceiptTable.update({
            (EpisodeConversationReceiptTable.status eq ChatRequestStatus.PROCESSING.name) and
                (EpisodeConversationReceiptTable.updatedAt lessEq before)
        }) {
            it[status] = ChatRequestStatus.FAILED.name
            it[updatedAt] = now
        }
    }

    private fun updateReceipt(
        key: ChatRequestKey,
        now: Long,
        update: EpisodeConversationReceiptTable.(org.jetbrains.exposed.sql.statements.UpdateBuilder<*>) -> Unit,
    ) {
        transaction(db) {
            val count = EpisodeConversationReceiptTable.update({
                (EpisodeConversationReceiptTable.userId eq key.userId.value) and
                    (EpisodeConversationReceiptTable.requestId eq key.requestId)
            }) {
                update(EpisodeConversationReceiptTable, it)
                it[updatedAt] = now
            }
            check(count == 1) { "Message receipt not found" }
        }
    }

    private fun deleteActive(userId: UserId) {
        EpisodeConversationActiveSessionTable.deleteWhere {
            EpisodeConversationActiveSessionTable.userId eq userId.value
        }
    }

    private fun receiptRow(key: ChatRequestKey): ResultRow =
        EpisodeConversationReceiptTable.selectAll()
            .where {
                (EpisodeConversationReceiptTable.userId eq key.userId.value) and
                    (EpisodeConversationReceiptTable.requestId eq key.requestId)
            }
            .single()

    private fun sessionRow(id: Int): ResultRow =
        EpisodeConversationSessionTable.selectAll()
            .where { EpisodeConversationSessionTable.id eq id }
            .single()

    private fun ResultRow.toSession(): ChatSession =
        ChatSession(
            id = this[EpisodeConversationSessionTable.id],
            userId = UserId(this[EpisodeConversationSessionTable.userId]),
            chatId = ChatId(this[EpisodeConversationSessionTable.conversationId]),
            createdAt = this[EpisodeConversationSessionTable.createdAt],
            lastActiveAt = this[EpisodeConversationSessionTable.lastActiveAt],
        )

    private fun ResultRow.toReceipt(): ChatReceipt =
        ChatReceipt(
            key = ChatRequestKey(
                userId = UserId(this[EpisodeConversationReceiptTable.userId]),
                requestId = this[EpisodeConversationReceiptTable.requestId],
            ),
            status = ChatRequestStatus.valueOf(this[EpisodeConversationReceiptTable.status]),
            sessionId = this[EpisodeConversationReceiptTable.sessionId],
            answerText = this[EpisodeConversationReceiptTable.answerText],
            createdAt = this[EpisodeConversationReceiptTable.createdAt],
            updatedAt = this[EpisodeConversationReceiptTable.updatedAt],
        )
}
