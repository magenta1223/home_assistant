package com.homeassistant.domain.kakao

import com.homeassistant.datamodel.kakao.KakaoMessage
import kotlin.test.Test
import kotlin.test.assertEquals

class KakaoImportServiceTest {
    @Test
    fun `import stores only kakao messages and dedupes by fingerprint`() {
        val store = FakeKakaoMessageStore()
        val service = KakaoImportService(store)
        val text = """
            [동훈] [오후 4:49] 따랑해
            [홍승민] [오후 5:38] 여기루 와용 ㅎㅎ
            """.trimIndent()

        val result = service.import("2026-06-07.txt", text)
        val repeated = service.import("2026-06-07.txt", text)

        assertEquals(2, result.importedMessageCount)
        assertEquals(2, result.messages.size)
        assertEquals(0, repeated.importedMessageCount)
    }

    private class FakeKakaoMessageStore : KakaoMessageStore {
        private val messages = mutableListOf<KakaoMessage>()
        private var nextId = 1

        override fun importMessages(messages: List<ParsedKakaoMessage>): List<KakaoMessage> =
            messages.mapNotNull { message ->
                if (this.messages.any { it.fingerprint == message.fingerprint }) return@mapNotNull null
                KakaoMessage(
                    id = nextId++,
                    sourceFileName = message.sourceFileName,
                    sender = message.sender,
                    displayTime = message.displayTime,
                    text = message.text,
                    lineStart = message.lineStart,
                    lineEnd = message.lineEnd,
                    fingerprint = message.fingerprint,
                ).also { this.messages += it }
            }

        override fun listMessages(sourceFileName: String): List<KakaoMessage> =
            messages.filter { it.sourceFileName == sourceFileName }
    }
}
