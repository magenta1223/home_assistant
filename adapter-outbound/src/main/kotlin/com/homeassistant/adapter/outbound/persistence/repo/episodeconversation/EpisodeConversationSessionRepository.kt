package com.homeassistant.adapter.outbound.persistence.repo.episodeconversation

import com.homeassistant.application.port.inbound.episode.EpisodeConversationParticipant
import com.homeassistant.application.port.inbound.episode.EpisodeConversationRequestKey
import com.homeassistant.application.port.outbound.episode.conversation.EpisodeConversationReceipt
import com.homeassistant.application.port.outbound.episode.conversation.EpisodeConversationRequestStatus
import com.homeassistant.application.port.outbound.episode.conversation.EpisodeConversationSession
import com.homeassistant.application.port.outbound.episode.conversation.EpisodeConversationSessionLease
import com.homeassistant.application.port.outbound.episode.conversation.EpisodeConversationSessionStore
import com.homeassistant.application.port.outbound.episode.conversation.ConversationId
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

internal class EpisodeConversationSessionRepository(
    private val db: Database,
) : EpisodeConversationSessionStore {
    override fun claimRequest(key: EpisodeConversationRequestKey, now: Long): EpisodeConversationReceipt? =
        try {
            transaction(db) {
                EpisodeConversationReceiptTable.insert {
                    it[streamId] = key.streamId
                    it[requestId] = key.requestId
                    it[status] = EpisodeConversationRequestStatus.PROCESSING.name
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                receiptRow(key).toReceipt()
            }
        } catch (error: ExposedSQLException) {
            if (receipt(key) != null) null else throw error
        }

    override fun receipt(key: EpisodeConversationRequestKey): EpisodeConversationReceipt? = transaction(db) {
        EpisodeConversationReceiptTable.selectAll()
            .where {
                (EpisodeConversationReceiptTable.streamId eq key.streamId) and
                    (EpisodeConversationReceiptTable.requestId eq key.requestId)
            }
            .singleOrNull()
            ?.toReceipt()
    }

    override fun attachSession(key: EpisodeConversationRequestKey, sessionId: Int, now: Long) {
        updateReceipt(key, now) {
            it[EpisodeConversationReceiptTable.sessionId] = sessionId
        }
    }

    override fun markAnswerReady(key: EpisodeConversationRequestKey, answer: String, now: Long) {
        require(answer.isNotBlank()) { "answer is required" }
        updateReceipt(key, now) {
            it[status] = EpisodeConversationRequestStatus.ANSWER_READY.name
            it[answerText] = answer
        }
    }

    override fun markFailed(key: EpisodeConversationRequestKey, now: Long) {
        updateReceipt(key, now) {
            it[status] = EpisodeConversationRequestStatus.FAILED.name
        }
    }

    override fun createAndActivate(
        participant: EpisodeConversationParticipant,
        conversationId: ConversationId,
        now: Long,
    ): EpisodeConversationSession = transaction(db) {
        val id = EpisodeConversationSessionTable.insert {
            it[scopeId] = participant.scopeId
            it[participantId] = participant.participantId
            it[userId] = participant.userId.value
            it[EpisodeConversationSessionTable.conversationId] = conversationId.value
            it[createdAt] = now
            it[lastActiveAt] = now
        }[EpisodeConversationSessionTable.id]

        deleteActive(participant)
        EpisodeConversationActiveSessionTable.insert {
            it[scopeId] = participant.scopeId
            it[participantId] = participant.participantId
            it[sessionId] = id
        }
        sessionRow(id).toSession()
    }

    override fun lease(
        participant: EpisodeConversationParticipant,
        now: Long,
        idleTimeoutMillis: Long,
    ): EpisodeConversationSessionLease = transaction(db) {
        require(idleTimeoutMillis > 0) { "idleTimeoutMillis must be positive" }
        val pointer = EpisodeConversationActiveSessionTable.selectAll()
            .where {
                (EpisodeConversationActiveSessionTable.scopeId eq participant.scopeId) and
                    (EpisodeConversationActiveSessionTable.participantId eq participant.participantId)
            }
            .singleOrNull()
            ?: return@transaction EpisodeConversationSessionLease.None
        val row = EpisodeConversationSessionTable.selectAll()
            .where { EpisodeConversationSessionTable.id eq pointer[EpisodeConversationActiveSessionTable.sessionId] }
            .singleOrNull()
        if (row == null || !row.matches(participant)) {
            deleteActive(participant)
            return@transaction EpisodeConversationSessionLease.None
        }
        val session = row.toSession()
        if (now - row[EpisodeConversationSessionTable.lastActiveAt] >= idleTimeoutMillis) {
            deleteActive(participant)
            return@transaction EpisodeConversationSessionLease.Expired(session)
        }
        EpisodeConversationSessionTable.update({ EpisodeConversationSessionTable.id eq session.id }) {
            it[lastActiveAt] = now
        }
        EpisodeConversationSessionLease.Active(session.copy(lastActiveAt = now))
    }

    override fun clearActive(participant: EpisodeConversationParticipant) {
        transaction(db) {
            deleteActive(participant)
        }
    }

    override fun touch(participant: EpisodeConversationParticipant, sessionId: Int, now: Long) {
        transaction(db) {
            val updated = EpisodeConversationSessionTable.update({
                (EpisodeConversationSessionTable.id eq sessionId) and
                    (EpisodeConversationSessionTable.scopeId eq participant.scopeId) and
                    (EpisodeConversationSessionTable.participantId eq participant.participantId) and
                    (EpisodeConversationSessionTable.userId eq participant.userId.value)
            }) {
                it[lastActiveAt] = now
            }
            check(updated == 1) { "Session ownership mismatch" }
        }
    }

    override fun expireIdle(beforeInclusive: Long): List<EpisodeConversationSession> = transaction(db) {
        val expired = (EpisodeConversationActiveSessionTable innerJoin EpisodeConversationSessionTable)
            .selectAll()
            .where { EpisodeConversationSessionTable.lastActiveAt lessEq beforeInclusive }
            .map { it.toSession() }
        expired.forEach { deleteActive(it.participant) }
        expired
    }

    override fun failStaleProcessing(before: Long, now: Long): Int = transaction(db) {
        EpisodeConversationReceiptTable.update({
            (EpisodeConversationReceiptTable.status eq EpisodeConversationRequestStatus.PROCESSING.name) and
                (EpisodeConversationReceiptTable.updatedAt lessEq before)
        }) {
            it[status] = EpisodeConversationRequestStatus.FAILED.name
            it[updatedAt] = now
        }
    }

    private fun updateReceipt(
        key: EpisodeConversationRequestKey,
        now: Long,
        update: EpisodeConversationReceiptTable.(org.jetbrains.exposed.sql.statements.UpdateBuilder<*>) -> Unit,
    ) {
        transaction(db) {
            val count = EpisodeConversationReceiptTable.update({
                (EpisodeConversationReceiptTable.streamId eq key.streamId) and
                    (EpisodeConversationReceiptTable.requestId eq key.requestId)
            }) {
                update(EpisodeConversationReceiptTable, it)
                it[updatedAt] = now
            }
            check(count == 1) { "Message receipt not found" }
        }
    }

    private fun deleteActive(participant: EpisodeConversationParticipant) {
        EpisodeConversationActiveSessionTable.deleteWhere {
            (scopeId eq participant.scopeId) and
                (participantId eq participant.participantId)
        }
    }

    private fun receiptRow(key: EpisodeConversationRequestKey): ResultRow =
        EpisodeConversationReceiptTable.selectAll()
            .where {
                (EpisodeConversationReceiptTable.streamId eq key.streamId) and
                    (EpisodeConversationReceiptTable.requestId eq key.requestId)
            }
            .single()

    private fun sessionRow(id: Int): ResultRow =
        EpisodeConversationSessionTable.selectAll()
            .where { EpisodeConversationSessionTable.id eq id }
            .single()

    private fun ResultRow.matches(participant: EpisodeConversationParticipant): Boolean =
        this[EpisodeConversationSessionTable.scopeId] == participant.scopeId &&
            this[EpisodeConversationSessionTable.participantId] == participant.participantId &&
            this[EpisodeConversationSessionTable.userId] == participant.userId.value

    private fun ResultRow.toSession(): EpisodeConversationSession =
        EpisodeConversationSession(
            id = this[EpisodeConversationSessionTable.id],
            participant = EpisodeConversationParticipant(
                scopeId = this[EpisodeConversationSessionTable.scopeId],
                participantId = this[EpisodeConversationSessionTable.participantId],
                userId = UserId(this[EpisodeConversationSessionTable.userId]),
            ),
            conversationId = ConversationId(this[EpisodeConversationSessionTable.conversationId]),
            createdAt = this[EpisodeConversationSessionTable.createdAt],
            lastActiveAt = this[EpisodeConversationSessionTable.lastActiveAt],
        )

    private fun ResultRow.toReceipt(): EpisodeConversationReceipt =
        EpisodeConversationReceipt(
            key = EpisodeConversationRequestKey(
                streamId = this[EpisodeConversationReceiptTable.streamId],
                requestId = this[EpisodeConversationReceiptTable.requestId],
            ),
            status = EpisodeConversationRequestStatus.valueOf(this[EpisodeConversationReceiptTable.status]),
            sessionId = this[EpisodeConversationReceiptTable.sessionId],
            answerText = this[EpisodeConversationReceiptTable.answerText],
            createdAt = this[EpisodeConversationReceiptTable.createdAt],
            updatedAt = this[EpisodeConversationReceiptTable.updatedAt],
        )
}
