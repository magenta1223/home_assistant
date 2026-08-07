package com.homeassistant.adapter.outbound.memoryanalysis

import com.homeassistant.adapter.outbound.codex.CodexCompletionClient
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.source.SourceRecord
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class CodexMemoryExtractorTest {
    @Test
    fun `extracts flat atomic memories with validated evidence`() = runBlocking {
        val service = CodexMemoryExtractor(
            StaticClient(
                """
                {"memories":[{"text":"홍승민은 카인드커피로 오라고 말했다.","subject":"홍승민","memoryType":"EVENT","certainty":"SAID","evidenceRecordIds":["r2","r3"]}]}
                """.trimIndent(),
            ),
        )

        val result = service.analyze(document(3))

        assertEquals(1, result.size)
        assertEquals("홍승민은 카인드커피로 오라고 말했다.", result.single().content)
        assertEquals(MemoryType.EVENT, result.single().memoryType)
        assertEquals(MemoryCertainty.SAID, result.single().certainty)
        assertEquals(listOf(2, 3), result.single().evidenceIds)
    }

    @Test
    fun `long documents are analyzed in chunks and merged`() = runBlocking {
        val client = RecordingClient(
            listOf(
                memoryJson("r1"),
                memoryJson("r201"),
                """{"memories":[{"text":"병원 일정이 있다.","subject":"병원","memoryType":"APPOINTMENT","certainty":"SAID","evidenceRecordIds":["r1","r201"]}]}""",
            ),
        )
        val result = CodexMemoryExtractor(client).analyze(document(201))

        assertEquals(3, client.calls)
        assertEquals(listOf(1, 201), result.single().evidenceIds)
    }

    @Test
    fun `rejects unknown evidence`() = runBlocking {
        val service = CodexMemoryExtractor(
            StaticClient("""{"memories":[{"text":"기억","subject":"대상","memoryType":"STATE","certainty":"SAID","evidenceRecordIds":["missing"]}]}"""),
        )

        assertFailsWith<com.homeassistant.application.memory.analysis.MemoryExtractionException> {
            service.analyze(document(1))
        }
    }
}

private fun document(count: Int) = SourceDocument(
    source = SourceDescriptor("kakao", "family.txt"),
    records = (1..count).map { SourceRecord(it, "key-$it", "기록 $it") },
)

private fun memoryJson(evidence: String) =
    """{"memories":[{"text":"기억 $evidence","subject":"대상","memoryType":"STATE","certainty":"SAID","evidenceRecordIds":["$evidence"]}]}"""

private class StaticClient(private val response: String) : CodexCompletionClient {
    override suspend fun complete(system: String, userMessage: String, outputSchema: String): String = response
}

private class RecordingClient(private val responses: List<String>) : CodexCompletionClient {
    private val pending = ArrayDeque(responses)
    var calls = 0

    override suspend fun complete(system: String, userMessage: String, outputSchema: String): String {
        calls++
        return pending.removeFirst()
    }
}
