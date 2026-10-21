package com.homeassistant.adapter.inbound.kakao.localdb

import com.homeassistant.adapter.inbound.kakao.KakaoExportParser
import com.homeassistant.application.port.input.memory.analysis.DuplicateSourceRecordsException
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysis
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisRequest
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisResult
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.MemoryAccess
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class KakaoLocalDbSyncTest {
    private val cursorPath: Path = Files.createTempDirectory("kakao-sync").resolve("cursor.json")
    private val family = KakaoChatSource("100", "가족 단톡방", MemoryAccess.restricted(listOf(UserId("mom"), UserId("dad"))))
    private val house = KakaoChatSource("200", "집 공지", MemoryAccess.PUBLIC)

    @Test
    fun `new messages of each chat are analyzed under the chat's source and access`() {
        val store = FakeStore("100" to listOf(message(1, "약 먹었어?")), "200" to listOf(message(5, "관리비 냈어")))
        val analysis = RecordingAnalysis()

        val results = sync(store, analysis).syncOnceBlocking()

        assertEquals(listOf(1, 1), results.map { it.syncedMessageCount })
        assertEquals(listOf("가족 단톡방", "집 공지"), analysis.requests.map { it.source.source.name })
        assertEquals(listOf(family.access, house.access), analysis.requests.map { it.access })
        assertEquals(setOf("owner"), analysis.requests.map { it.userId }.toSet())
        assertEquals(setOf("kakao"), analysis.requests.map { it.source.source.type }.toSet())
    }

    @Test
    fun `the cursor survives restarts so a message is analyzed once`() {
        val store = FakeStore("100" to listOf(message(1, "a"), message(2, "b")))
        val analysis = RecordingAnalysis()

        sync(store, analysis).syncOnceBlocking()
        sync(store, analysis).syncOnceBlocking()

        assertEquals(1, analysis.requests.size)
        assertEquals(2L, KakaoSyncCursor(cursorPath).after("100"))
    }

    @Test
    fun `messages are analyzed in batches`() {
        val store = FakeStore("100" to (1L..5L).map { message(it, "m$it") })
        val analysis = RecordingAnalysis()

        sync(store, analysis, batchSize = 2).syncOnceBlocking()

        assertEquals(listOf(2, 2, 1), analysis.requests.map { it.source.records.size })
    }

    @Test
    fun `an already analyzed batch still advances the cursor`() {
        val store = FakeStore("100" to listOf(message(1, "a")))
        val analysis = RecordingAnalysis { throw duplicate() }

        val result = sync(store, analysis).syncOnceBlocking().first()

        assertIs<KakaoChatSyncResult.UpToDate>(result)
        assertEquals(1L, KakaoSyncCursor(cursorPath).after("100"))
    }

    @Test
    fun `a failing chat keeps its cursor and does not stop the other chats`() {
        val store = FakeStore("100" to listOf(message(1, "a")), "200" to listOf(message(5, "b")))
        val analysis = RecordingAnalysis { request ->
            if (request.source.source.name == family.sourceName) error("analysis unavailable")
        }

        val results = sync(store, analysis).syncOnceBlocking()

        assertIs<KakaoChatSyncResult.Stopped>(results[0])
        assertIs<KakaoChatSyncResult.UpToDate>(results[1])
        val cursor = KakaoSyncCursor(cursorPath)
        assertEquals(KakaoSyncCursor.START, cursor.after("100"))
        assertEquals(5L, cursor.after("200"))
    }

    @Test
    fun `a DB message gets the same record as the exported message`() {
        val sentAt = LocalDateTime.of(2025, 1, 2, 9, 3).atZone(KakaoLocalDbSync.KAKAO_ZONE).toInstant()
        val store = FakeStore("100" to listOf(KakaoLocalMessage(1, "민수", "아침 약은 먹었어?", sentAt)))
        val analysis = RecordingAnalysis()

        sync(store, analysis).syncOnceBlocking()

        val exported = KakaoExportParser.parse("export.txt", "2025년 1월 2일 오전 9:03, 민수 : 아침 약은 먹었어?")
            .records.single()
        val synced = analysis.requests.single().source.records.single()
        assertEquals(exported.deduplicationKey, synced.deduplicationKey)
        assertEquals(exported.content, synced.content)
    }

    private fun sync(store: KakaoLocalStore, analysis: MemoryAnalysis, batchSize: Int = 200) = KakaoLocalDbSync(
        store = store,
        memoryAnalysis = analysis,
        cursor = KakaoSyncCursor(cursorPath),
        config = KakaoLocalDbConfig(Path.of("unused.sqlite"), cursorPath, UserId("owner"), listOf(family, house)),
        batchSize = batchSize,
    )

    private fun KakaoLocalDbSync.syncOnceBlocking() = runBlocking { syncOnce() }

    private fun message(logId: Long, text: String) =
        KakaoLocalMessage(logId, "민수", text, LocalDateTime.of(2025, 1, 2, 9, 3).atZone(KakaoLocalDbSync.KAKAO_ZONE).toInstant())

    /** The application keeps this constructor module-internal; adapters only ever receive it. */
    private fun duplicate(): DuplicateSourceRecordsException =
        DuplicateSourceRecordsException::class.java
            .getDeclaredConstructor(String::class.java, Int::class.javaPrimitiveType)
            .newInstance("가족 단톡방", 1)

    private class FakeStore(vararg chats: Pair<String, List<KakaoLocalMessage>>) : KakaoLocalStore {
        private val messages = chats.toMap()

        override fun messagesAfter(chatId: String, afterLogId: Long, limit: Int): List<KakaoLocalMessage> =
            messages[chatId].orEmpty().filter { it.logId > afterLogId }.take(limit)
    }

    private class RecordingAnalysis(
        private val onExecute: (MemoryAnalysisRequest) -> Unit = {},
    ) : MemoryAnalysis {
        val requests = mutableListOf<MemoryAnalysisRequest>()

        override suspend fun execute(request: MemoryAnalysisRequest): MemoryAnalysisResult {
            requests += request
            onExecute(request)
            return MemoryAnalysisResult(
                sourceType = request.source.source.type,
                sourceName = request.source.source.name,
                importedRecordCount = request.source.records.size,
                retriedRecordCount = 0,
                alreadyAnalyzedRecordCount = 0,
                visibility = request.access.visibility,
                allowedUserIds = request.access.allowedUserIds,
                memoryCount = 0,
                memories = emptyList(),
            )
        }
    }
}
