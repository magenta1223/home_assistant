package com.homeassistant.adapter.outbound.memoryanalysis

import com.homeassistant.llm.completion.CompletionClient
import com.homeassistant.llm.completion.CompletionImage
import com.homeassistant.domain.memory.MemoryAccess
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.source.SourceRecord
import com.homeassistant.domain.source.SourceRecordAnalysisStatus
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MemoryExtractorTest {
    @Test
    fun `extraction contract only requires content and evidence`() {
        val root = Json.parseToJsonElement(MemoryAnalysisOutputContract.schema).jsonObject
        val memorySchema = root["properties"]!!.jsonObject["memories"]!!
            .jsonObject["items"]!!.jsonObject
        val required = memorySchema["required"]!!.jsonArray.map { it.jsonPrimitive.content }

        assertEquals(setOf("text", "evidenceRecordIds"), required.toSet())
        assertEquals(setOf("text", "evidenceRecordIds"), memorySchema["properties"]!!.jsonObject.keys)
        assertContains(MemoryAnalysisPrompt.system(), "열람 권한은 사용자가 입력 단계에서 지정")
    }

    @Test
    fun `self contained content preserves attribution conditions and uncertainty`() = runBlocking {
        val content = "연구자는 9월 측정값이 센서 오류일 가능성이 있으며 재측정 전에는 확정할 수 없다고 말했다."
        val client = RecordingClient(mutableListOf(response(text = content)))

        val memory = MemoryExtractor(client).analyze(document(recordCount = 1)).single()

        assertEquals(content, memory.content)
        assertEquals(listOf(1), memory.evidenceIds)
    }

    @Test
    fun `large documents use bounded chunks with overlap`() = runBlocking {
        val client = FunctionalClient { _, _, _ -> """{"memories":[]}""" }

        MemoryExtractor(
            client = client,
            chunkSize = 400,
            chunkOverlap = 20,
        ).analyze(document(recordCount = 1_001))

        assertEquals(3, client.calls.size)
        assertEquals(400, client.calls[0].newRecordLines().size)
        assertEquals(400, client.calls[1].newRecordLines().size)
        assertEquals(241, client.calls[2].newRecordLines().size)
        assertEquals(
            client.calls[0].newRecordLines().takeLast(20),
            client.calls[1].newRecordLines().take(20),
        )
        assertEquals(
            client.calls[1].newRecordLines().takeLast(20),
            client.calls[2].newRecordLines().take(20),
        )
    }

    @Test
    fun `chunking does not create a final overlap-only request`() = runBlocking {
        val client = FunctionalClient { _, _, _ -> """{"memories":[]}""" }

        MemoryExtractor(
            client = client,
            chunkSize = 400,
            chunkOverlap = 20,
        ).analyze(document(recordCount = 780))

        assertEquals(2, client.calls.size)
    }

    @Test
    fun `parallel chunk calls respect configured concurrency limit`() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val client = FunctionalClient { _, _, _ ->
            val current = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, current) }
            try {
                delay(30)
                """{"memories":[]}"""
            } finally {
                active.decrementAndGet()
            }
        }

        MemoryExtractor(
            client = client,
            chunkSize = 1,
            chunkOverlap = 0,
            maxConcurrentChunks = 2,
        ).analyze(document(recordCount = 6))

        assertEquals(6, client.calls.size)
        assertEquals(2, peak.get())
    }

    private fun document(recordCount: Int): SourceDocument = SourceDocument(
        source = SourceDescriptor(type = "test", name = "source"),
        records = (1..recordCount).map {
            SourceRecord(it, "key-$it", "content-$it", SourceRecordAnalysisStatus.PENDING, MemoryAccess.PUBLIC)
        },
    )

    private fun response(
        text: String = "fact",
        evidenceIds: List<String> = listOf("r1"),
    ): String = """{"memories":[${memoryJson(text, evidenceIds)}]}"""

    private fun memoryJson(
        text: String = "fact",
        evidenceIds: List<String> = listOf("r1"),
    ): String {
        val evidenceJson = evidenceIds.joinToString(",") { "\"$it\"" }
        return """{"text":"$text","evidenceRecordIds":[$evidenceJson]}"""
    }

    private class RecordingClient(
        private val responses: MutableList<String>,
    ) : CompletionClient {
        val calls = mutableListOf<Call>()

        override suspend fun complete(
            system: String,
            userMessage: String,
            outputSchema: String,
            images: List<CompletionImage>,
        ): String {
            calls += Call(system, userMessage)
            return responses.removeFirst()
        }
    }

    private data class Call(
        val system: String,
        val userMessage: String,
    )

    private class FunctionalClient(
        private val block: suspend (String, String, String) -> String,
    ) : CompletionClient {
        val calls = mutableListOf<Call>()

        override suspend fun complete(
            system: String,
            userMessage: String,
            outputSchema: String,
            images: List<CompletionImage>,
        ): String {
            synchronized(calls) { calls += Call(system, userMessage) }
            return block(system, userMessage, outputSchema)
        }
    }

    private fun Call.newRecordLines(): List<String> =
        userMessage.substringAfter("[NEW_RECORDS]\n").lines().filter { it.isNotBlank() }
}
