package com.homeassistant.domain.kakao

import com.homeassistant.domain.db.tables.KakaoImportedMessageTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** Stores and retrieves deduplicated KakaoTalk messages imported from export files. */
class KakaoMessageRepository(private val db: Database) {
    fun importMessages(messages: List<ParsedKakaoMessage>): List<KakaoImportedMessage> = transaction(db) {
        messages.mapNotNull { message ->
            val existing = KakaoImportedMessageTable.selectAll()
                .where { KakaoImportedMessageTable.fingerprint eq message.fingerprint.value }
                .singleOrNull()
            if (existing != null) return@mapNotNull null

            val id = KakaoImportedMessageTable.insert {
                it[sourceFileName] = message.sourceFileName.value
                it[sender] = message.sender.value
                it[displayTime] = message.displayTime
                it[content] = message.text.value
                it[lineStart] = message.lineStart.value
                it[lineEnd] = message.lineEnd.value
                it[fingerprint] = message.fingerprint.value
                it[createdAt] = System.currentTimeMillis()
            }[KakaoImportedMessageTable.id]
            message.toImported(KakaoMessageId(id))
        }
    }

    fun listMessages(sourceFileName: KakaoSourceFileName): List<KakaoImportedMessage> = transaction(db) {
        KakaoImportedMessageTable.selectAll()
            .where { KakaoImportedMessageTable.sourceFileName eq sourceFileName.value }
            .orderBy(KakaoImportedMessageTable.id)
            .map { it.toImportedMessage() }
    }

    private fun ParsedKakaoMessage.toImported(id: KakaoMessageId): KakaoImportedMessage =
        KakaoImportedMessage(
            id = id,
            sourceFileName = sourceFileName,
            sender = sender,
            displayTime = displayTime,
            text = text,
            lineStart = lineStart,
            lineEnd = lineEnd,
            fingerprint = fingerprint,
        )

    private fun ResultRow.toImportedMessage(): KakaoImportedMessage =
        KakaoImportedMessage(
            id = KakaoMessageId(this[KakaoImportedMessageTable.id]),
            sourceFileName = KakaoSourceFileName(this[KakaoImportedMessageTable.sourceFileName]),
            sender = KakaoSenderName(this[KakaoImportedMessageTable.sender]),
            displayTime = this[KakaoImportedMessageTable.displayTime],
            text = KakaoMessageText(this[KakaoImportedMessageTable.content]),
            lineStart = KakaoLineNumber(this[KakaoImportedMessageTable.lineStart]),
            lineEnd = KakaoLineNumber(this[KakaoImportedMessageTable.lineEnd]),
            fingerprint = KakaoMessageFingerprint(this[KakaoImportedMessageTable.fingerprint]),
        )
}
