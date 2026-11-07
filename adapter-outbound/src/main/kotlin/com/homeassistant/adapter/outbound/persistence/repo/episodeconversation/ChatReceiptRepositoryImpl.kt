package com.homeassistant.adapter.outbound.persistence.repo.episodeconversation

import com.homeassistant.adapter.outbound.persistence.db.tables.ChatReceiptTable
import com.homeassistant.application.port.inbound.chatservice.ChatRequestKey
import com.homeassistant.application.port.outbound.chatgateway.ChatReceipt
import com.homeassistant.application.port.outbound.chatgateway.ChatReceiptChange
import com.homeassistant.application.port.outbound.chatgateway.ChatReceiptConflict
import com.homeassistant.application.port.outbound.chatgateway.ChatReceiptRepository
import com.homeassistant.application.port.outbound.chatgateway.ChatRequestStatus
import com.homeassistant.application.port.outbound.chatgateway.StaleProcessingFailure
import com.homeassistant.domain.identity.UserId
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

internal class ChatReceiptRepositoryImpl(
    private val db: Database,
) : ChatReceiptRepository {
    override fun findByKey(key: ChatRequestKey): ChatReceipt? = transaction(db) {
        ChatReceiptTable.selectAll().where { hasKey(key) }.singleOrNull()?.toReceipt()
    }

    override fun save(change: ChatReceiptChange): ChatReceipt = when (change) {
        is ChatReceiptChange.Claim -> claim(change)
        is ChatReceiptChange.AttachSession -> update(change) { it[ChatReceiptTable.sessionId] = change.sessionId }
        is ChatReceiptChange.AnswerReady -> update(change) {
            it[ChatReceiptTable.status] = ChatRequestStatus.ANSWER_READY.name
            it[ChatReceiptTable.answerText] = change.answer
        }
        is ChatReceiptChange.Fail -> update(change) { it[ChatReceiptTable.status] = ChatRequestStatus.FAILED.name }
    }

    override fun save(failure: StaleProcessingFailure): List<ChatReceipt> = transaction(db) {
        val stale = (ChatReceiptTable.status eq ChatRequestStatus.PROCESSING.name) and
            (ChatReceiptTable.updatedAt lessEq failure.before)
        val failed = ChatReceiptTable.selectAll().where { stale }
            .map { it.toReceipt().copy(status = ChatRequestStatus.FAILED, updatedAt = failure.now) }
        ChatReceiptTable.update({ stale }) {
            it[status] = ChatRequestStatus.FAILED.name
            it[updatedAt] = failure.now
        }
        failed
    }

    private fun claim(claim: ChatReceiptChange.Claim): ChatReceipt =
        try {
            transaction(db) {
                ChatReceiptTable.insert {
                    it[userId] = claim.key.userId.value
                    it[requestId] = claim.key.requestId
                    it[status] = ChatRequestStatus.PROCESSING.name
                    it[createdAt] = claim.now
                    it[updatedAt] = claim.now
                }
                receiptRow(claim.key).toReceipt()
            }
        } catch (error: ExposedSQLException) {
            if (findByKey(claim.key) == null) throw error
            throw ChatReceiptConflict(claim.key)
        }

    private fun update(
        change: ChatReceiptChange,
        apply: (UpdateBuilder<*>) -> Unit,
    ): ChatReceipt = transaction(db) {
        val count = ChatReceiptTable.update({ hasKey(change.key) }) {
            apply(it)
            it[updatedAt] = change.now
        }
        check(count == 1) { "Message receipt not found" }
        receiptRow(change.key).toReceipt()
    }

    private fun hasKey(key: ChatRequestKey): Op<Boolean> =
        (ChatReceiptTable.userId eq key.userId.value) and (ChatReceiptTable.requestId eq key.requestId)

    private fun receiptRow(key: ChatRequestKey): ResultRow =
        ChatReceiptTable.selectAll().where { hasKey(key) }.single()

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
