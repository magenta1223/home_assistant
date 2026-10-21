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
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class KakaoLocalDbSyncTest {
    private val cursorPath: Path = Files.createTempDirectory("kakao-sync").resolve("cursor.json")
    private val family = KakaoChatSource("family", "우리 가족", "가족 단톡방", MemoryAccess.restricted(listOf(UserId("mom"), UserId("dad"))))
    private val house = KakaoChatSource("house", "집 공지방", "집 공지", MemoryAccess.PUBLIC)

    @Test
    fun `new messages of each chat are analyzed under the chat's source and access`() {
        val store = FakeStore("family" to listOf(message(3, "약 먹었어?")), "house" to listOf(message(5, "관리비 냈어")))
        val analysis = DedupAnalysis()

        val results = sync(store, analysis).syncOnceBlocking()

        assertEquals(listOf(1, 1), results.map { it.importedMessageCount })
        assertEquals(listOf("가족 단톡방", "집 공지"), analysis.requests.map { it.source.source.name })
        assertEquals(listOf(family.access, house.access), analysis.requests.map { it.access })
        assertEquals(setOf("owner"), analysis.requests.map { it.userId }.toSet())
        assertEquals(setOf("kakao"), analysis.requests.map { it.source.source.type }.toSet())
    }

    @Test
    fun `the cursor keeps the last message minute`() {
        val store = FakeStore("family" to listOf(message(3, "a"), message(4, "b")))

        sync(store, DedupAnalysis()).syncOnceBlocking()

        assertEquals(minute(4), KakaoSyncCursor(cursorPath).from("family"))
    }

    @Test
    fun `a restart re-reads only the cursor minute and absorbs it as duplicates`() {
        val store = FakeStore("family" to listOf(message(3, "a"), message(4, "b")))
        val analysis = DedupAnalysis()

        sync(store, analysis).syncOnceBlocking()
        val second = sync(store, analysis).syncOnceBlocking().single { it.chatKey == "family" }

        assertIs<KakaoChatSyncResult.UpToDate>(second)
        assertEquals(0, second.importedMessageCount)
        assertEquals(listOf("b"), analysis.requests.last().source.records.map { it.content.substringAfterLast(" | ") })
        assertEquals(minute(4), KakaoSyncCursor(cursorPath).from("family"))
    }

    @Test
    fun `a message that arrives later in the cursor minute is still imported`() {
        val store = FakeStore("family" to listOf(message(3, "a"), message(4, "b")))
        val analysis = DedupAnalysis()
        sync(store, analysis).syncOnceBlocking()

        store.add("family", message(4, "c"))
        val result = sync(store, analysis).syncOnceBlocking().single { it.chatKey == "family" }

        assertEquals(1, result.importedMessageCount)
        assertEquals(setOf("a", "b", "c"), analysis.stored)
    }

    @Test
    fun `messages are analyzed in batches that never split a minute`() {
        val store = FakeStore(
            "family" to listOf(message(1, "m1"), message(2, "m2a"), message(2, "m2b"), message(2, "m2c"), message(3, "m3")),
        )
        val analysis = DedupAnalysis()

        val result = sync(store, analysis, batchSize = 2).syncOnceBlocking().single { it.chatKey == "family" }

        assertEquals(listOf(listOf("m1", "m2a", "m2b", "m2c"), listOf("m3")), analysis.requests.map { request ->
            request.source.records.map { it.content.substringAfterLast(" | ") }
        })
        assertEquals(5, result.importedMessageCount)
    }

    @Test
    fun `a batch ending exactly at the limit moves on to the next minute`() {
        val store = FakeStore("family" to listOf(message(1, "a"), message(1, "b"), message(2, "c")))
        val analysis = DedupAnalysis()

        sync(store, analysis, batchSize = 2).syncOnceBlocking()

        assertEquals(listOf(2, 1), analysis.requests.map { it.source.records.size })
        assertEquals(minute(2), KakaoSyncCursor(cursorPath).from("family"))
    }

    @Test
    fun `an already analyzed batch still advances the cursor`() {
        val store = FakeStore("family" to listOf(message(3, "a")))
        val analysis = DedupAnalysis { throw duplicate(family.sourceName, 1) }

        val result = sync(store, analysis).syncOnceBlocking().first()

        assertIs<KakaoChatSyncResult.UpToDate>(result)
        assertEquals(minute(3), KakaoSyncCursor(cursorPath).from("family"))
    }

    @Test
    fun `a failing chat keeps its cursor and does not stop the other chats`() {
        val store = FakeStore("family" to listOf(message(3, "a")), "house" to listOf(message(5, "b")))
        val analysis = DedupAnalysis { request ->
            if (request.source.source.name == family.sourceName) error("analysis unavailable")
        }

        val results = sync(store, analysis).syncOnceBlocking()

        assertIs<KakaoChatSyncResult.Stopped>(results[0])
        assertIs<KakaoChatSyncResult.UpToDate>(results[1])
        val cursor = KakaoSyncCursor(cursorPath)
        assertEquals(KakaoSyncCursor.START, cursor.from("family"))
        assertEquals(minute(5), cursor.from("house"))
    }

    @Test
    fun `a synced message gets the same record as the uploaded export`() {
        val store = FakeStore("family" to listOf(KakaoLocalMessage("민수", "아침 약은 먹었어?", minute(3))))
        val analysis = DedupAnalysis()

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
        config = KakaoLocalDbConfig(Path.of("unused"), cursorPath, UserId("owner"), listOf(family, house)),
        batchSize = batchSize,
    )

    private fun KakaoLocalDbSync.syncOnceBlocking() = runBlocking { syncOnce() }

    private fun minute(minute: Int): Instant =
        LocalDateTime.of(2025, 1, 2, 9, minute).atZone(KakaoLocalDbSync.KAKAO_ZONE).toInstant()

    /** Seconds inside the minute must not matter: exports only carry minutes. */
    private fun message(minute: Int, text: String) = KakaoLocalMessage("민수", text, minute(minute).plusSeconds(30))

    private class FakeStore(vararg chats: Pair<String, List<KakaoLocalMessage>>) : KakaoLocalStore {
        private val messages = chats.associate { (key, list) -> key to list.toMutableList() }.toMutableMap()

        fun add(chatKey: String, message: KakaoLocalMessage) {
            messages.getOrPut(chatKey) { mutableListOf() } += message
        }

        override fun messagesFrom(chatKey: String, from: Instant, limit: Int): List<KakaoLocalMessage> =
            messages[chatKey].orEmpty()
                .filter { it.sentAt.truncatedTo(ChronoUnit.MINUTES) >= from }
                .takeWholeMinutes(limit)
    }

    /** Stores records by deduplication key like the application does, and throws when a batch holds nothing new. */
    private class DedupAnalysis(
        private val onExecute: (MemoryAnalysisRequest) -> Unit = {},
    ) : MemoryAnalysis {
        val requests = mutableListOf<MemoryAnalysisRequest>()
        private val keys = mutableSetOf<String>()
        val stored = mutableSetOf<String>()

        override suspend fun execute(request: MemoryAnalysisRequest): MemoryAnalysisResult {
            requests += request
            onExecute(request)
            val fresh = request.source.records.filter { keys.add(it.deduplicationKey) }
            fresh.forEach { stored += it.content.substringAfterLast(" | ") }
            if (fresh.isEmpty()) {
                throw duplicate(request.source.source.name, request.source.records.size)
            }
            return MemoryAnalysisResult(
                sourceType = request.source.source.type,
                sourceName = request.source.source.name,
                importedRecordCount = fresh.size,
                retriedRecordCount = 0,
                alreadyAnalyzedRecordCount = request.source.records.size - fresh.size,
                visibility = request.access.visibility,
                allowedUserIds = request.access.allowedUserIds,
                memoryCount = 0,
                memories = emptyList(),
            )
        }
    }
}

/** The application keeps this constructor module-internal; adapters only ever receive it. */
private fun duplicate(sourceName: String, count: Int): DuplicateSourceRecordsException =
    DuplicateSourceRecordsException::class.java
        .getDeclaredConstructor(String::class.java, Int::class.javaPrimitiveType)
        .newInstance(sourceName, count)
