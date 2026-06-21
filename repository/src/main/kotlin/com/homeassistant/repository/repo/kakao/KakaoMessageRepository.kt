package com.homeassistant.repository.repo.kakao

import com.homeassistant.datamodel.kakao.KakaoMessage
import com.homeassistant.domain.kakao.KakaoMessageStore
import com.homeassistant.domain.kakao.ParsedKakaoMessage
import com.homeassistant.repository.db.tables.KakaoImportedMessageTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** Stores and retrieves deduplicated KakaoTalk messages imported from export files. */
internal class KakaoMessageRepository(private val db: Database) : KakaoMessageStore {
    override fun importMessages(messages: List<ParsedKakaoMessage>): List<KakaoMessage> = transaction(db) {
        messages.mapNotNull { message ->
            val existing = KakaoImportedMessageTable.selectAll()
                .where { KakaoImportedMessageTable.fingerprint eq message.fingerprint }
                .singleOrNull()
            if (existing != null) return@mapNotNull null

            val id = KakaoImportedMessageTable.insert {
                it[sourceFileName] = message.sourceFileName
                it[sender] = message.sender
                it[displayTime] = message.displayTime
                it[content] = message.text
                it[lineStart] = message.lineStart
                it[lineEnd] = message.lineEnd
                it[fingerprint] = message.fingerprint
                it[createdAt] = System.currentTimeMillis()
            }[KakaoImportedMessageTable.id]
            message.toImported(id)
        }
    }

    override fun listMessages(sourceFileName: String): List<KakaoMessage> = transaction(db) {
        KakaoImportedMessageTable.selectAll()
            .where { KakaoImportedMessageTable.sourceFileName eq sourceFileName }
            .orderBy(KakaoImportedMessageTable.id)
            .map { it.toImportedMessage() }
    }

    private fun ParsedKakaoMessage.toImported(id: Int): KakaoMessage =
        KakaoMessage(
            id = id,
            sourceFileName = sourceFileName,
            sender = sender,
            displayTime = displayTime,
            text = text,
            lineStart = lineStart,
            lineEnd = lineEnd,
            fingerprint = fingerprint,
        )

    private fun ResultRow.toImportedMessage(): KakaoMessage =
        KakaoMessage(
            id = this[KakaoImportedMessageTable.id],
            sourceFileName = this[KakaoImportedMessageTable.sourceFileName],
            sender = this[KakaoImportedMessageTable.sender],
            displayTime = this[KakaoImportedMessageTable.displayTime],
            text = this[KakaoImportedMessageTable.content],
            lineStart = this[KakaoImportedMessageTable.lineStart],
            lineEnd = this[KakaoImportedMessageTable.lineEnd],
            fingerprint = this[KakaoImportedMessageTable.fingerprint],
        )
}
