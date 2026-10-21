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
import java.time.temporal.ChronoUnit

/**
 * Feeds new messages of the allowlisted chats from the [KakaoLocalStore] into [MemoryAnalysis], one batch per
 * analysis, and advances each chat's cursor to the batch's last minute only after the batch is stored.
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

    /**
     * Each round starts at the cursor minute again and absorbs its re-read messages as duplicates. Within a round
     * the next batch starts after the last minute, which a batch never splits.
     */
    private suspend fun syncChat(chat: KakaoChatSource): KakaoChatSyncResult {
        var importedMessageCount = 0
        return try {
            var from = cursor.from(chat.chatKey)
            do {
                val messages = store.messagesFrom(chat.chatKey, from, batchSize)
                if (messages.isNotEmpty()) {
                    importedMessageCount += analyze(chat, messages)
                    val lastMinute = messages.last().sentAt.truncatedTo(ChronoUnit.MINUTES)
                    cursor.advance(chat.chatKey, lastMinute)
                    from = lastMinute.plus(1, ChronoUnit.MINUTES)
                }
            } while (messages.size >= batchSize)
            KakaoChatSyncResult.UpToDate(chat.chatKey, importedMessageCount)
        } catch (failure: Exception) {
            KakaoChatSyncResult.Stopped(chat.chatKey, importedMessageCount, failure)
        }
    }

    /**
     * Returns how many messages were new. A batch whose messages were all analyzed before, e.g. re-read or
     * imported from an uploaded export, is already stored.
     */
    private suspend fun analyze(chat: KakaoChatSource, messages: List<KakaoLocalMessage>): Int {
        val request = MemoryAnalysisRequest(
            userId = config.ownerUserId.value,
            source = SourceDocumentDraft(
                source = SourceDescriptor(KakaoMessageRecord.SOURCE_TYPE, chat.sourceName),
                records = messages.map(::toRecord),
            ),
            access = chat.access,
        )
        return try {
            memoryAnalysis.execute(request).importedRecordCount
        } catch (_: DuplicateSourceRecordsException) {
            0
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

        /** Kakao exports show Korean local time; synced messages use the same clock so both paths share one key. */
        val KAKAO_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }
}

sealed interface KakaoChatSyncResult {
    val chatKey: String

    /** Messages stored for the first time this round; re-read duplicates are not counted. */
    val importedMessageCount: Int

    data class UpToDate(
        override val chatKey: String,
        override val importedMessageCount: Int,
    ) : KakaoChatSyncResult

    /** Sync stopped at the cursor before [cause]; the next round retries from there. */
    data class Stopped(
        override val chatKey: String,
        override val importedMessageCount: Int,
        val cause: Exception,
    ) : KakaoChatSyncResult
}
