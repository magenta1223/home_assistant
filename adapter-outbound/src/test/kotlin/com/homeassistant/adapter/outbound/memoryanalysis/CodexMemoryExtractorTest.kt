package com.homeassistant.adapter.outbound.memoryanalysis

import com.homeassistant.adapter.outbound.codex.CodexCompletionClient
import com.homeassistant.application.port.output.memory.analysis.MemoryExtractionException
import com.homeassistant.domain.memory.MemoryVisibility
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
import kotlin.test.assertTrue

class CodexMemoryExtractorTest {
    @Test
    fun `visibility is required by the output schema`() {
        val root = Json.parseToJsonElement(MemoryAnalysisOutputContract.schema).jsonObject
        val memorySchema = root["properties"]!!.jsonObject["memories"]!!
            .jsonObject["items"]!!.jsonObject
        val required = memorySchema["required"]!!.jsonArray.map { it.jsonPrimitive.content }

        assertContains(required, "visibility")
    }

    @Test
    fun `missing visibility is rejected`() = runBlocking {
        val extractor = CodexMemoryExtractor(
            client = RecordingClient(
                responses = mutableListOf(response(visibility = null)),
            ),
        )

        assertFailsWith<MemoryExtractionException> {
            extractor.analyze(document(recordCount = 1))
        }
    }

    @Test
    fun `private visibility survives chunk merge`() = runBlocking {
        val client = RecordingClient(
            responses = mutableListOf(
                response(text = "private fact", evidenceIds = listOf("r1"), visibility = "PRIVATE"),
                response(text = "shared fact", evidenceIds = listOf("r2"), visibility = "PUBLIC"),
                response(text = "private fact", evidenceIds = listOf("r1"), visibility = "PRIVATE"),
            ),
        )
        val memories = CodexMemoryExtractor(client, chunkSize = 1).analyze(document(recordCount = 2))

        assertEquals(MemoryVisibility.PRIVATE, memories.single().visibility)
        assertContains(client.calls.last().userMessage, "\"visibility\": \"PRIVATE\"")
        assertContains(client.calls.last().system, "더 제한적인 PRIVATE")
    }

    @Test
    fun `duplicate visibility conflict resolves to private`() = runBlocking {
        val client = RecordingClient(
            responses = mutableListOf(
                """
                {"memories":[
                  ${memoryJson(visibility = "PUBLIC")},
                  ${memoryJson(visibility = "PRIVATE")}
                ]}
                """.trimIndent(),
            ),
        )

        val memories = CodexMemoryExtractor(client).analyze(document(recordCount = 1))

        assertEquals(1, memories.size)
        assertEquals(MemoryVisibility.PRIVATE, memories.single().visibility)
    }

    @Test
    fun `analysis prompt classifies sensitive and ambiguous information conservatively`() {
        val prompt = MemoryAnalysisPrompt.system()

        assertTrue(listOf("건강", "금융", "자격 증명", "민감한 관계", "개인적인 고민").all(prompt::contains))
        assertContains(prompt, "애매하면 반드시 PRIVATE")
    }

    @Test
    fun `context records are rendered separately and cannot become evidence`() = runBlocking {
        val client = RecordingClient(
            responses = mutableListOf(response(evidenceIds = listOf("c99"), visibility = "PRIVATE")),
        )
        val input = document(recordCount = 1).copy(
            contextRecords = listOf(
                SourceRecord(99, "context", "earlier message", SourceRecordAnalysisStatus.ANALYZED),
            ),
        )

        assertFailsWith<MemoryExtractionException> { CodexMemoryExtractor(client).analyze(input) }
        assertContains(client.calls.single().userMessage, "[CONTEXT_ONLY]\nc99 | earlier message")
        assertContains(client.calls.single().userMessage, "[NEW_RECORDS]\nr1 | content-1")
        assertContains(client.calls.single().system, "c1, c2 같은 record는 해석에만 사용")
    }

    @Test
    fun `large documents use bounded chunks with overlap`() = runBlocking {
        val client = FunctionalClient { _, _, _ -> """{"memories":[]}""" }

        CodexMemoryExtractor(
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

        CodexMemoryExtractor(
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

        CodexMemoryExtractor(
            client = client,
            chunkSize = 1,
            chunkOverlap = 0,
            maxConcurrentChunks = 2,
        ).analyze(document(recordCount = 6))

        assertEquals(6, client.calls.size)
        assertEquals(2, peak.get())
    }

    @Test
    fun `oversized merge input falls back to deterministic deduplication`() = runBlocking {
        val client = FunctionalClient { _, userMessage, _ ->
            val evidenceId = if (userMessage.contains("r1 |")) "r1" else "r2"
            response(text = "fact-$evidenceId", evidenceIds = listOf(evidenceId), visibility = "PUBLIC")
        }

        val memories = CodexMemoryExtractor(
            client = client,
            chunkSize = 1,
            chunkOverlap = 0,
            maxMergeInputChars = 1,
        ).analyze(document(recordCount = 2))

        assertEquals(2, client.calls.size)
        assertEquals(listOf("fact-r1", "fact-r2"), memories.map { it.content }.sorted())
    }

    @Test
    fun `oversized merge fallback removes duplicate overlap candidates`() = runBlocking {
        val client = FunctionalClient { _, userMessage, _ ->
            response(
                text = "overlap fact",
                evidenceIds = listOf("r2"),
                visibility = if (userMessage.contains("r1 |")) "PUBLIC" else "PRIVATE",
            )
        }

        val memories = CodexMemoryExtractor(
            client = client,
            chunkSize = 2,
            chunkOverlap = 1,
            maxMergeInputChars = 1,
        ).analyze(document(recordCount = 3))

        assertEquals(2, client.calls.size)
        assertEquals(1, memories.size)
        assertEquals(MemoryVisibility.PRIVATE, memories.single().visibility)
    }

    private fun document(recordCount: Int): SourceDocument = SourceDocument(
        source = SourceDescriptor(type = "test", name = "source"),
        records = (1..recordCount).map {
            SourceRecord(it, "key-$it", "content-$it", SourceRecordAnalysisStatus.PENDING)
        },
    )

    private fun response(
        text: String = "fact",
        evidenceIds: List<String> = listOf("r1"),
        visibility: String?,
    ): String = """{"memories":[${memoryJson(text, evidenceIds, visibility)}]}"""

    private fun memoryJson(
        text: String = "fact",
        evidenceIds: List<String> = listOf("r1"),
        visibility: String?,
    ): String {
        val visibilityJson = visibility?.let { ",\"visibility\":\"$it\"" }.orEmpty()
        val evidenceJson = evidenceIds.joinToString(",") { "\"$it\"" }
        return """{"text":"$text","subject":"subject","memoryType":"REFERENCE","certainty":"OBSERVED"$visibilityJson,"evidenceRecordIds":[$evidenceJson]}"""
    }

    private class RecordingClient(
        private val responses: MutableList<String>,
    ) : CodexCompletionClient {
        val calls = mutableListOf<Call>()

        override suspend fun complete(system: String, userMessage: String, outputSchema: String): String {
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
    ) : CodexCompletionClient {
        val calls = mutableListOf<Call>()

        override suspend fun complete(system: String, userMessage: String, outputSchema: String): String {
            synchronized(calls) { calls += Call(system, userMessage) }
            return block(system, userMessage, outputSchema)
        }
    }

    private fun Call.newRecordLines(): List<String> =
        userMessage.substringAfter("[NEW_RECORDS]\n").lines().filter { it.isNotBlank() }
}
