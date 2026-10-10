package com.homeassistant.adapter.outbound.persistence.repo.memoryconversation

import com.homeassistant.application.port.input.memory.conversation.MemoryConversationParticipant
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationRequestKey
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationReceipt
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationRequestStatus
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationSession
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationSessionLease
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationSessionStore
import com.homeassistant.application.port.output.memory.conversation.ConversationId
import com.homeassistant.domain.identity.UserId
import com.homeassistant.adapter.outbound.persistence.db.tables.MemoryConversationActiveSessionTable
import com.homeassistant.adapter.outbound.persistence.db.tables.MemoryConversationSessionTable
import com.homeassistant.adapter.outbound.persistence.db.tables.MemoryConversationReceiptTable
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
                MemoryConversationReceiptTable.insert {
                    it[streamId] = key.streamId
                    it[requestId] = key.requestId
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
        MemoryConversationReceiptTable.selectAll()
            .where {
                (MemoryConversationReceiptTable.streamId eq key.streamId) and
                    (MemoryConversationReceiptTable.requestId eq key.requestId)
            }
            .singleOrNull()
            ?.toReceipt()
    }

    override fun attachSession(key: MemoryConversationRequestKey, sessionId: Int, now: Long) {
        updateReceipt(key, now) {
            it[MemoryConversationReceiptTable.sessionId] = sessionId
        }
    }

    override fun markAnswerReady(key: MemoryConversationRequestKey, answer: String, now: Long) {
        require(answer.isNotBlank()) { "answer is required" }
        updateReceipt(key, now) {
            it[status] = MemoryConversationRequestStatus.ANSWER_READY.name
            it[answerText] = answer
        }
    }

    override fun markFailed(key: MemoryConversationRequestKey, now: Long) {
        updateReceipt(key, now) {
            it[status] = MemoryConversationRequestStatus.FAILED.name
        }
    }

    override fun createAndActivate(
        participant: MemoryConversationParticipant,
        conversationId: ConversationId,
        now: Long,
    ): MemoryConversationSession = transaction(db) {
        val id = MemoryConversationSessionTable.insert {
            it[scopeId] = participant.scopeId
            it[participantId] = participant.participantId
            it[userId] = participant.userId.value
            it[MemoryConversationSessionTable.conversationId] = conversationId.value
            it[createdAt] = now
            it[lastActiveAt] = now
        }[MemoryConversationSessionTable.id]

        deleteActive(participant)
        MemoryConversationActiveSessionTable.insert {
            it[scopeId] = participant.scopeId
            it[participantId] = participant.participantId
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
        val pointer = MemoryConversationActiveSessionTable.selectAll()
            .where {
                (MemoryConversationActiveSessionTable.scopeId eq participant.scopeId) and
                    (MemoryConversationActiveSessionTable.participantId eq participant.participantId)
            }
            .singleOrNull()
            ?: return@transaction MemoryConversationSessionLease.None
        val row = MemoryConversationSessionTable.selectAll()
            .where { MemoryConversationSessionTable.id eq pointer[MemoryConversationActiveSessionTable.sessionId] }
            .singleOrNull()
        if (row == null || !row.matches(participant)) {
            deleteActive(participant)
            return@transaction MemoryConversationSessionLease.None
        }
        val session = row.toSession()
        if (now - row[MemoryConversationSessionTable.lastActiveAt] >= idleTimeoutMillis) {
            deleteActive(participant)
            return@transaction MemoryConversationSessionLease.Expired(session)
        }
        MemoryConversationSessionTable.update({ MemoryConversationSessionTable.id eq session.id }) {
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
            val updated = MemoryConversationSessionTable.update({
                (MemoryConversationSessionTable.id eq sessionId) and
                    (MemoryConversationSessionTable.scopeId eq participant.scopeId) and
                    (MemoryConversationSessionTable.participantId eq participant.participantId) and
                    (MemoryConversationSessionTable.userId eq participant.userId.value)
            }) {
                it[lastActiveAt] = now
            }
            check(updated == 1) { "Session ownership mismatch" }
        }
    }

    override fun expireIdle(beforeInclusive: Long): List<MemoryConversationSession> = transaction(db) {
        val expired = (MemoryConversationActiveSessionTable innerJoin MemoryConversationSessionTable)
            .selectAll()
            .where { MemoryConversationSessionTable.lastActiveAt lessEq beforeInclusive }
            .map { it.toSession() }
        expired.forEach { deleteActive(it.participant) }
        expired
    }

    override fun failStaleProcessing(before: Long, now: Long): Int = transaction(db) {
        MemoryConversationReceiptTable.update({
            (MemoryConversationReceiptTable.status eq MemoryConversationRequestStatus.PROCESSING.name) and
                (MemoryConversationReceiptTable.updatedAt lessEq before)
        }) {
            it[status] = MemoryConversationRequestStatus.FAILED.name
            it[updatedAt] = now
        }
    }

    private fun updateReceipt(
        key: MemoryConversationRequestKey,
        now: Long,
        update: MemoryConversationReceiptTable.(org.jetbrains.exposed.sql.statements.UpdateBuilder<*>) -> Unit,
    ) {
        transaction(db) {
            val count = MemoryConversationReceiptTable.update({
                (MemoryConversationReceiptTable.streamId eq key.streamId) and
                    (MemoryConversationReceiptTable.requestId eq key.requestId)
            }) {
                update(MemoryConversationReceiptTable, it)
                it[updatedAt] = now
            }
            check(count == 1) { "Message receipt not found" }
        }
    }

    private fun deleteActive(participant: MemoryConversationParticipant) {
        MemoryConversationActiveSessionTable.deleteWhere {
            (scopeId eq participant.scopeId) and
                (participantId eq participant.participantId)
        }
    }

    private fun receiptRow(key: MemoryConversationRequestKey): ResultRow =
        MemoryConversationReceiptTable.selectAll()
            .where {
                (MemoryConversationReceiptTable.streamId eq key.streamId) and
                    (MemoryConversationReceiptTable.requestId eq key.requestId)
            }
            .single()

    private fun sessionRow(id: Int): ResultRow =
        MemoryConversationSessionTable.selectAll()
            .where { MemoryConversationSessionTable.id eq id }
            .single()

    private fun ResultRow.matches(participant: MemoryConversationParticipant): Boolean =
        this[MemoryConversationSessionTable.scopeId] == participant.scopeId &&
            this[MemoryConversationSessionTable.participantId] == participant.participantId &&
            this[MemoryConversationSessionTable.userId] == participant.userId.value

    private fun ResultRow.toSession(): MemoryConversationSession =
        MemoryConversationSession(
            id = this[MemoryConversationSessionTable.id],
            participant = MemoryConversationParticipant(
                scopeId = this[MemoryConversationSessionTable.scopeId],
                participantId = this[MemoryConversationSessionTable.participantId],
                userId = UserId(this[MemoryConversationSessionTable.userId]),
            ),
            conversationId = ConversationId(this[MemoryConversationSessionTable.conversationId]),
            createdAt = this[MemoryConversationSessionTable.createdAt],
            lastActiveAt = this[MemoryConversationSessionTable.lastActiveAt],
        )

    private fun ResultRow.toReceipt(): MemoryConversationReceipt =
        MemoryConversationReceipt(
            key = MemoryConversationRequestKey(
                streamId = this[MemoryConversationReceiptTable.streamId],
                requestId = this[MemoryConversationReceiptTable.requestId],
            ),
            status = MemoryConversationRequestStatus.valueOf(this[MemoryConversationReceiptTable.status]),
            sessionId = this[MemoryConversationReceiptTable.sessionId],
            answerText = this[MemoryConversationReceiptTable.answerText],
            createdAt = this[MemoryConversationReceiptTable.createdAt],
            updatedAt = this[MemoryConversationReceiptTable.updatedAt],
        )
}
