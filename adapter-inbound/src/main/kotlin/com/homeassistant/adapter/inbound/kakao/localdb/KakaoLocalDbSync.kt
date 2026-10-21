package com.homeassistant.adapter.inbound.kakao.localdb

import com.homeassistant.adapter.inbound.kakao.KakaoMessageRecord
import com.homeassistant.application.port.input.memory.analysis.DuplicateSourceRecordsException
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysis
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisRequest
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceDocumentDraft
import com.homeassistant.domain.source.SourceRecordDraft
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Feeds new messages of the allowlisted chats from the Kakao local DB into [MemoryAnalysis], one batch per
 * analysis, and advances each chat's cursor only after its batch is stored.
 */
class KakaoLocalDbSync(
    private val store: KakaoLocalStore,
    private val memoryAnalysis: MemoryAnalysis,
    private val cursor: KakaoSyncCursor,
    private val config: KakaoLocalDbConfig,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    /** A failing chat stops at its cursor and is retried next round; the other chats still sync. */
    suspend fun syncOnce(): List<KakaoChatSyncResult> = config.chats.map { syncChat(it) }

    private suspend fun syncChat(chat: KakaoChatSource): KakaoChatSyncResult {
        var syncedMessageCount = 0
        return try {
            do {
                val messages = store.messagesAfter(chat.chatId, cursor.after(chat.chatId), batchSize)
                if (messages.isNotEmpty()) {
                    analyze(chat, messages)
                    cursor.advance(chat.chatId, messages.last().logId)
                    syncedMessageCount += messages.size
                }
            } while (messages.size == batchSize)
            KakaoChatSyncResult.UpToDate(chat.chatId, syncedMessageCount)
        } catch (failure: Exception) {
            KakaoChatSyncResult.Stopped(chat.chatId, syncedMessageCount, failure)
        }
    }

    /** A batch whose messages were all analyzed before, e.g. imported from an export file, is already stored. */
    private suspend fun analyze(chat: KakaoChatSource, messages: List<KakaoLocalMessage>) {
        val request = MemoryAnalysisRequest(
            userId = config.ownerUserId.value,
            source = SourceDocumentDraft(
                source = SourceDescriptor(KakaoMessageRecord.SOURCE_TYPE, chat.sourceName),
                records = messages.map(::toRecord),
            ),
            access = chat.access,
        )
        try {
            memoryAnalysis.execute(request)
        } catch (_: DuplicateSourceRecordsException) {
            // Already stored.
        }
    }

    private fun toRecord(message: KakaoLocalMessage): SourceRecordDraft {
        val sentAt = LocalDateTime.ofInstant(message.sentAt, KAKAO_ZONE)
        return KakaoMessageRecord.record(
            sender = message.senderName.trim(),
            displayTime = KakaoMessageRecord.displayTime(sentAt),
            canonicalTime = KakaoMessageRecord.canonicalTime(sentAt),
            text = message.text.trimEnd(),
        )
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 200

        /** Kakao exports show Korean local time; DB messages use the same clock so both paths share one key. */
        val KAKAO_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }
}

sealed interface KakaoChatSyncResult {
    val chatId: String
    val syncedMessageCount: Int

    data class UpToDate(
        override val chatId: String,
        override val syncedMessageCount: Int,
    ) : KakaoChatSyncResult

    /** Sync stopped at the cursor before [cause]; the next round retries from there. */
    data class Stopped(
        override val chatId: String,
        override val syncedMessageCount: Int,
        val cause: Exception,
    ) : KakaoChatSyncResult
}
