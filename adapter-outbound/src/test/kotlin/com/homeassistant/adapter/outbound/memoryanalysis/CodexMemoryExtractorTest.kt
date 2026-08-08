package com.homeassistant.adapter.outbound.memoryanalysis

import com.homeassistant.adapter.outbound.codex.CodexCompletionClient
import com.homeassistant.application.memory.analysis.MemoryExtractionException
import com.homeassistant.domain.memory.MemoryVisibility
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.source.SourceRecord
import com.homeassistant.domain.source.SourceRecordAnalysisStatus
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
}
