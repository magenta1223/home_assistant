package com.homeassistant.adapter.outbound.persistence.repo.memoryanswer

import com.homeassistant.adapter.outbound.persistence.db.tables.PendingRegistrationQuestionTable
import com.homeassistant.application.port.input.memory.answer.MemoryAnswerRequest
import com.homeassistant.application.port.input.memory.answer.ConversationRequestKey
import com.homeassistant.application.port.input.identity.ConversationIdentity
import com.homeassistant.application.port.output.memory.answer.PendingRegistrationQuestionStore
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

internal class PendingRegistrationQuestionRepository(
    private val db: Database,
) : PendingRegistrationQuestionStore {
    override fun rememberFirst(request: MemoryAnswerRequest, now: Long): Boolean = transaction(db) {
        PendingRegistrationQuestionTable.insertIgnore {
            it[scopeId] = request.identity.scopeId
            it[participantId] = request.identity.participantId
            it[streamId] = request.key.streamId
            it[requestId] = request.key.requestId
            it[question] = request.question
            it[createdAt] = now
        }.insertedCount == 1
    }

    override fun find(identity: ConversationIdentity): MemoryAnswerRequest? = transaction(db) {
        row(identity)?.toPendingConversation()
    }

    override fun remove(identity: ConversationIdentity) {
        transaction(db) {
            PendingRegistrationQuestionTable.deleteWhere {
                (scopeId eq identity.scopeId) and (participantId eq identity.participantId)
            }
        }
    }

    private fun row(identity: ConversationIdentity): ResultRow? =
        PendingRegistrationQuestionTable.selectAll()
            .where {
                (PendingRegistrationQuestionTable.scopeId eq identity.scopeId) and
                    (PendingRegistrationQuestionTable.participantId eq identity.participantId)
            }
            .singleOrNull()

    private fun ResultRow.toPendingConversation(): MemoryAnswerRequest =
        MemoryAnswerRequest(
            identity = ConversationIdentity(
                scopeId = this[PendingRegistrationQuestionTable.scopeId],
                participantId = this[PendingRegistrationQuestionTable.participantId],
            ),
            key = ConversationRequestKey(
                streamId = this[PendingRegistrationQuestionTable.streamId],
                requestId = this[PendingRegistrationQuestionTable.requestId],
            ),
            question = this[PendingRegistrationQuestionTable.question],
        )
}
