package com.homeassistant.adapter.outbound.persistence.repo.slackconversation

import com.homeassistant.domain.slackconversation.SlackCodexSession
import com.homeassistant.domain.slackconversation.SlackCodexSessionStore
import com.homeassistant.domain.slackconversation.SlackMessageKey
import com.homeassistant.domain.slackconversation.SlackMessageReceipt
import com.homeassistant.domain.slackconversation.SlackMessageReceiptStatus
import com.homeassistant.domain.slackconversation.SlackPrincipal
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
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

internal class SlackCodexSessionRepository(
    private val db: Database,
) : SlackCodexSessionStore {
    override fun claimMessage(key: SlackMessageKey, now: Long): SlackMessageReceipt? =
        try {
            transaction(db) {
                SlackMessageReceiptTable.insert {
                    it[channelId] = key.channelId
                    it[messageTs] = key.messageTs
                    it[status] = SlackMessageReceiptStatus.PROCESSING.name
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                receiptRow(key).toReceipt()
            }
        } catch (error: ExposedSQLException) {
            if (receipt(key) != null) null else throw error
        }

    override fun receipt(key: SlackMessageKey): SlackMessageReceipt? = transaction(db) {
        SlackMessageReceiptTable.selectAll()
            .where {
                (SlackMessageReceiptTable.channelId eq key.channelId) and
                    (SlackMessageReceiptTable.messageTs eq key.messageTs)
            }
            .singleOrNull()
            ?.toReceipt()
    }

    override fun attachSession(key: SlackMessageKey, sessionId: Int, now: Long) {
        updateReceipt(key, now) {
            it[SlackMessageReceiptTable.sessionId] = sessionId
        }
    }

    override fun markAnswerReady(key: SlackMessageKey, answer: String, now: Long) {
        require(answer.isNotBlank()) { "answer is required" }
        updateReceipt(key, now) {
            it[status] = SlackMessageReceiptStatus.ANSWER_READY.name
            it[answerText] = answer
        }
    }

    override fun markCompleted(key: SlackMessageKey, responseTs: String, now: Long) {
        require(responseTs.isNotBlank()) { "responseTs is required" }
        updateReceipt(key, now) {
            it[status] = SlackMessageReceiptStatus.COMPLETED.name
            it[SlackMessageReceiptTable.responseTs] = responseTs
        }
    }

    override fun markFailed(key: SlackMessageKey, now: Long) {
        updateReceipt(key, now) {
            it[status] = SlackMessageReceiptStatus.FAILED.name
        }
    }

    override fun createAndActivate(
        principal: SlackPrincipal,
        codexThreadId: String,
        now: Long,
    ): SlackCodexSession = transaction(db) {
        require(codexThreadId.isNotBlank()) { "codexThreadId is required" }
        val id = SlackCodexSessionTable.insert {
            it[teamId] = principal.teamId
            it[slackUserId] = principal.slackUserId
            it[userId] = principal.userId.value
            it[familyId] = LEGACY_HOUSEHOLD_ID
            it[SlackCodexSessionTable.codexThreadId] = codexThreadId
            it[createdAt] = now
            it[lastActiveAt] = now
        }[SlackCodexSessionTable.id]

        deleteActive(principal)
        SlackCodexActiveSessionTable.insert {
            it[teamId] = principal.teamId
            it[slackUserId] = principal.slackUserId
            it[sessionId] = id
        }
        sessionRow(id).toSession()
    }

    override fun active(
        principal: SlackPrincipal,
        now: Long,
        idleTimeoutMillis: Long,
    ): SlackCodexSession? = transaction(db) {
        require(idleTimeoutMillis > 0) { "idleTimeoutMillis must be positive" }
        val pointer = SlackCodexActiveSessionTable.selectAll()
            .where {
                (SlackCodexActiveSessionTable.teamId eq principal.teamId) and
                    (SlackCodexActiveSessionTable.slackUserId eq principal.slackUserId)
            }
            .singleOrNull()
            ?: return@transaction null
        val row = SlackCodexSessionTable.selectAll()
            .where { SlackCodexSessionTable.id eq pointer[SlackCodexActiveSessionTable.sessionId] }
            .singleOrNull()
        if (row == null || !row.matches(principal)) {
            deleteActive(principal)
            return@transaction null
        }
        if (now - row[SlackCodexSessionTable.lastActiveAt] >= idleTimeoutMillis) {
            deleteActive(principal)
            return@transaction null
        }
        row.toSession()
    }

    override fun clearActive(principal: SlackPrincipal) {
        transaction(db) {
            deleteActive(principal)
        }
    }

    override fun touch(principal: SlackPrincipal, sessionId: Int, now: Long) {
        transaction(db) {
            val updated = SlackCodexSessionTable.update({
                (SlackCodexSessionTable.id eq sessionId) and
                    (SlackCodexSessionTable.teamId eq principal.teamId) and
                    (SlackCodexSessionTable.slackUserId eq principal.slackUserId) and
                    (SlackCodexSessionTable.userId eq principal.userId.value)
            }) {
                it[lastActiveAt] = now
            }
            check(updated == 1) { "Session ownership mismatch" }
        }
    }

    override fun failStaleProcessing(before: Long, now: Long): Int = transaction(db) {
        SlackMessageReceiptTable.update({
            (SlackMessageReceiptTable.status eq SlackMessageReceiptStatus.PROCESSING.name) and
                (SlackMessageReceiptTable.updatedAt lessEq before)
        }) {
            it[status] = SlackMessageReceiptStatus.FAILED.name
            it[updatedAt] = now
        }
    }

    private fun updateReceipt(
        key: SlackMessageKey,
        now: Long,
        update: SlackMessageReceiptTable.(org.jetbrains.exposed.sql.statements.UpdateBuilder<*>) -> Unit,
    ) {
        transaction(db) {
            val count = SlackMessageReceiptTable.update({
                (SlackMessageReceiptTable.channelId eq key.channelId) and
                    (SlackMessageReceiptTable.messageTs eq key.messageTs)
            }) {
                update(SlackMessageReceiptTable, it)
                it[updatedAt] = now
            }
            check(count == 1) { "Message receipt not found" }
        }
    }

    private fun deleteActive(principal: SlackPrincipal) {
        SlackCodexActiveSessionTable.deleteWhere {
            (teamId eq principal.teamId) and
                (slackUserId eq principal.slackUserId)
        }
    }

    private fun receiptRow(key: SlackMessageKey): ResultRow =
        SlackMessageReceiptTable.selectAll()
            .where {
                (SlackMessageReceiptTable.channelId eq key.channelId) and
                    (SlackMessageReceiptTable.messageTs eq key.messageTs)
            }
            .single()

    private fun sessionRow(id: Int): ResultRow =
        SlackCodexSessionTable.selectAll()
            .where { SlackCodexSessionTable.id eq id }
            .single()

    private fun ResultRow.matches(principal: SlackPrincipal): Boolean =
        this[SlackCodexSessionTable.teamId] == principal.teamId &&
            this[SlackCodexSessionTable.slackUserId] == principal.slackUserId &&
            this[SlackCodexSessionTable.userId] == principal.userId.value

    private fun ResultRow.toSession(): SlackCodexSession =
        SlackCodexSession(
            id = this[SlackCodexSessionTable.id],
            principal = SlackPrincipal(
                teamId = this[SlackCodexSessionTable.teamId],
                slackUserId = this[SlackCodexSessionTable.slackUserId],
                userId = UserId(this[SlackCodexSessionTable.userId]),
            ),
            codexThreadId = this[SlackCodexSessionTable.codexThreadId],
            createdAt = this[SlackCodexSessionTable.createdAt],
            lastActiveAt = this[SlackCodexSessionTable.lastActiveAt],
        )

    private fun ResultRow.toReceipt(): SlackMessageReceipt =
        SlackMessageReceipt(
            key = SlackMessageKey(
                channelId = this[SlackMessageReceiptTable.channelId],
                messageTs = this[SlackMessageReceiptTable.messageTs],
            ),
            status = SlackMessageReceiptStatus.valueOf(this[SlackMessageReceiptTable.status]),
            sessionId = this[SlackMessageReceiptTable.sessionId],
            answerText = this[SlackMessageReceiptTable.answerText],
            responseTs = this[SlackMessageReceiptTable.responseTs],
            createdAt = this[SlackMessageReceiptTable.createdAt],
            updatedAt = this[SlackMessageReceiptTable.updatedAt],
        )
}

private const val LEGACY_HOUSEHOLD_ID = "household"
