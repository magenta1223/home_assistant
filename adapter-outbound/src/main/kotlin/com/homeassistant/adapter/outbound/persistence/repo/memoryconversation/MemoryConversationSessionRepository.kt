package com.homeassistant.adapter.outbound.persistence.repo.memoryconversation

import com.homeassistant.application.port.input.memory.conversation.MemoryConversationParticipant
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationRequestKey
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationReceipt
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationRequestStatus
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationSession
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationSessionLease
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationSessionStore
import com.homeassistant.domain.identity.UserId
import com.homeassistant.adapter.outbound.persistence.db.tables.SlackCodexActiveSessionTable
import com.homeassistant.adapter.outbound.persistence.db.tables.SlackCodexSessionTable
import com.homeassistant.adapter.outbound.persistence.db.tables.SlackMessageReceiptTable
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

internal class MemoryConversationSessionRepository(
    private val db: Database,
) : MemoryConversationSessionStore {
    override fun claimRequest(key: MemoryConversationRequestKey, now: Long): MemoryConversationReceipt? =
        try {
            transaction(db) {
                SlackMessageReceiptTable.insert {
                    it[channelId] = key.streamId
                    it[messageTs] = key.requestId
                    it[status] = MemoryConversationRequestStatus.PROCESSING.name
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                receiptRow(key).toReceipt()
            }
        } catch (error: ExposedSQLException) {
            if (receipt(key) != null) null else throw error
        }

    override fun receipt(key: MemoryConversationRequestKey): MemoryConversationReceipt? = transaction(db) {
        SlackMessageReceiptTable.selectAll()
            .where {
                (SlackMessageReceiptTable.channelId eq key.streamId) and
                    (SlackMessageReceiptTable.messageTs eq key.requestId)
            }
            .singleOrNull()
            ?.toReceipt()
    }

    override fun attachSession(key: MemoryConversationRequestKey, sessionId: Int, now: Long) {
        updateReceipt(key, now) {
            it[SlackMessageReceiptTable.sessionId] = sessionId
        }
    }

    override fun markAnswerReady(key: MemoryConversationRequestKey, answer: String, now: Long) {
        require(answer.isNotBlank()) { "answer is required" }
        updateReceipt(key, now) {
            it[status] = MemoryConversationRequestStatus.ANSWER_READY.name
            it[answerText] = answer
        }
    }

    override fun markCompleted(key: MemoryConversationRequestKey, deliveryId: String, now: Long) {
        require(deliveryId.isNotBlank()) { "deliveryId is required" }
        updateReceipt(key, now) {
            it[status] = MemoryConversationRequestStatus.COMPLETED.name
            it[SlackMessageReceiptTable.responseTs] = deliveryId
        }
    }

    override fun markFailed(key: MemoryConversationRequestKey, now: Long) {
        updateReceipt(key, now) {
            it[status] = MemoryConversationRequestStatus.FAILED.name
        }
    }

    override fun createAndActivate(
        participant: MemoryConversationParticipant,
        conversationThreadId: String,
        now: Long,
    ): MemoryConversationSession = transaction(db) {
        require(conversationThreadId.isNotBlank()) { "conversationThreadId is required" }
        val id = SlackCodexSessionTable.insert {
            it[teamId] = participant.scopeId
            it[slackUserId] = participant.participantId
            it[userId] = participant.userId.value
            it[SlackCodexSessionTable.codexThreadId] = conversationThreadId
            it[createdAt] = now
            it[lastActiveAt] = now
        }[SlackCodexSessionTable.id]

        deleteActive(participant)
        SlackCodexActiveSessionTable.insert {
            it[teamId] = participant.scopeId
            it[slackUserId] = participant.participantId
            it[sessionId] = id
        }
        sessionRow(id).toSession()
    }

    override fun lease(
        participant: MemoryConversationParticipant,
        now: Long,
        idleTimeoutMillis: Long,
    ): MemoryConversationSessionLease = transaction(db) {
        require(idleTimeoutMillis > 0) { "idleTimeoutMillis must be positive" }
        val pointer = SlackCodexActiveSessionTable.selectAll()
            .where {
                (SlackCodexActiveSessionTable.teamId eq participant.scopeId) and
                    (SlackCodexActiveSessionTable.slackUserId eq participant.participantId)
            }
            .singleOrNull()
            ?: return@transaction MemoryConversationSessionLease.None
        val row = SlackCodexSessionTable.selectAll()
            .where { SlackCodexSessionTable.id eq pointer[SlackCodexActiveSessionTable.sessionId] }
            .singleOrNull()
        if (row == null || !row.matches(participant)) {
            deleteActive(participant)
            return@transaction MemoryConversationSessionLease.None
        }
        val session = row.toSession()
        if (now - row[SlackCodexSessionTable.lastActiveAt] >= idleTimeoutMillis) {
            deleteActive(participant)
            return@transaction MemoryConversationSessionLease.Expired(session)
        }
        SlackCodexSessionTable.update({ SlackCodexSessionTable.id eq session.id }) {
            it[lastActiveAt] = now
        }
        MemoryConversationSessionLease.Active(session.copy(lastActiveAt = now))
    }

    override fun clearActive(participant: MemoryConversationParticipant) {
        transaction(db) {
            deleteActive(participant)
        }
    }

    override fun touch(participant: MemoryConversationParticipant, sessionId: Int, now: Long) {
        transaction(db) {
            val updated = SlackCodexSessionTable.update({
                (SlackCodexSessionTable.id eq sessionId) and
                    (SlackCodexSessionTable.teamId eq participant.scopeId) and
                    (SlackCodexSessionTable.slackUserId eq participant.participantId) and
                    (SlackCodexSessionTable.userId eq participant.userId.value)
            }) {
                it[lastActiveAt] = now
            }
            check(updated == 1) { "Session ownership mismatch" }
        }
    }

    override fun expireIdle(beforeInclusive: Long): List<MemoryConversationSession> = transaction(db) {
        val expired = (SlackCodexActiveSessionTable innerJoin SlackCodexSessionTable)
            .selectAll()
            .where { SlackCodexSessionTable.lastActiveAt lessEq beforeInclusive }
            .map { it.toSession() }
        expired.forEach { deleteActive(it.participant) }
        expired
    }

    override fun failStaleProcessing(before: Long, now: Long): Int = transaction(db) {
        SlackMessageReceiptTable.update({
            (SlackMessageReceiptTable.status eq MemoryConversationRequestStatus.PROCESSING.name) and
                (SlackMessageReceiptTable.updatedAt lessEq before)
        }) {
            it[status] = MemoryConversationRequestStatus.FAILED.name
            it[updatedAt] = now
        }
    }

    private fun updateReceipt(
        key: MemoryConversationRequestKey,
        now: Long,
        update: SlackMessageReceiptTable.(org.jetbrains.exposed.sql.statements.UpdateBuilder<*>) -> Unit,
    ) {
        transaction(db) {
            val count = SlackMessageReceiptTable.update({
                (SlackMessageReceiptTable.channelId eq key.streamId) and
                    (SlackMessageReceiptTable.messageTs eq key.requestId)
            }) {
                update(SlackMessageReceiptTable, it)
                it[updatedAt] = now
            }
            check(count == 1) { "Message receipt not found" }
        }
    }

    private fun deleteActive(participant: MemoryConversationParticipant) {
        SlackCodexActiveSessionTable.deleteWhere {
            (teamId eq participant.scopeId) and
                (slackUserId eq participant.participantId)
        }
    }

    private fun receiptRow(key: MemoryConversationRequestKey): ResultRow =
        SlackMessageReceiptTable.selectAll()
            .where {
                (SlackMessageReceiptTable.channelId eq key.streamId) and
                    (SlackMessageReceiptTable.messageTs eq key.requestId)
            }
            .single()

    private fun sessionRow(id: Int): ResultRow =
        SlackCodexSessionTable.selectAll()
            .where { SlackCodexSessionTable.id eq id }
            .single()

    private fun ResultRow.matches(participant: MemoryConversationParticipant): Boolean =
        this[SlackCodexSessionTable.teamId] == participant.scopeId &&
            this[SlackCodexSessionTable.slackUserId] == participant.participantId &&
            this[SlackCodexSessionTable.userId] == participant.userId.value

    private fun ResultRow.toSession(): MemoryConversationSession =
        MemoryConversationSession(
            id = this[SlackCodexSessionTable.id],
            participant = MemoryConversationParticipant(
                scopeId = this[SlackCodexSessionTable.teamId],
                participantId = this[SlackCodexSessionTable.slackUserId],
                userId = UserId(this[SlackCodexSessionTable.userId]),
            ),
            conversationThreadId = this[SlackCodexSessionTable.codexThreadId],
            createdAt = this[SlackCodexSessionTable.createdAt],
            lastActiveAt = this[SlackCodexSessionTable.lastActiveAt],
        )

    private fun ResultRow.toReceipt(): MemoryConversationReceipt =
        MemoryConversationReceipt(
            key = MemoryConversationRequestKey(
                streamId = this[SlackMessageReceiptTable.channelId],
                requestId = this[SlackMessageReceiptTable.messageTs],
            ),
            status = MemoryConversationRequestStatus.valueOf(this[SlackMessageReceiptTable.status]),
            sessionId = this[SlackMessageReceiptTable.sessionId],
            answerText = this[SlackMessageReceiptTable.answerText],
            deliveryId = this[SlackMessageReceiptTable.responseTs],
            createdAt = this[SlackMessageReceiptTable.createdAt],
            updatedAt = this[SlackMessageReceiptTable.updatedAt],
        )
}
