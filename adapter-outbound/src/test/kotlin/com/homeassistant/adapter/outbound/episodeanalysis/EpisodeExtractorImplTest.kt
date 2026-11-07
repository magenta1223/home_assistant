package com.homeassistant.adapter.outbound.episodeanalysis

import com.homeassistant.llm.CompletionClient
import com.homeassistant.llm.CompletionImage
import com.homeassistant.domain.access.AccessControl
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.infrastructure.serialization.JsonSerializer.decodeFromString
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

class EpisodeExtractorImplTest {
    @Test
    fun `extraction contract requires a title, content and evidence`() {
        val root = Json.parseToJsonElement(EpisodeAnalysisOutputContract.schema).jsonObject
        val episodeSchema = root["properties"]!!.jsonObject["episodes"]!!
            .jsonObject["items"]!!.jsonObject
        val required = episodeSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content }

        assertEquals(setOf("title", "text", "evidenceRecordIds"), required.toSet())
        assertEquals(setOf("title", "text", "evidenceRecordIds"), episodeSchema["properties"]!!.jsonObject.keys)
        assertContains(EpisodeAnalysisPrompt.system(), "같은 일에 관한 내용은 하나의 episode로")
    }

    @Test
    fun `episode title and text are kept as the model wrote them with their evidence`() = runBlocking {
        val content = "연구자는 9월 측정값이 센서 오류일 가능성이 있으며 재측정 전에는 확정할 수 없다고 말했다."
        val client = RecordingClient(mutableListOf(response(text = content)))

        val episode = EpisodeExtractorImpl(client).analyze(document(recordCount = 1)).single()

        assertEquals("title", episode.title)
        assertEquals(content, episode.content)
        assertEquals(listOf(1), episode.evidenceIds)
    }

    @Test
    fun `large documents use bounded chunks with overlap`() = runBlocking {
        val client = FunctionalClient { _, _, _ -> """{"episodes":[]}""" }

        EpisodeExtractorImpl(
            client = client,
            chunkSize = 400,
            chunkOverlap = 20,
        ).analyze(document(recordCount = 1_001))

        assertEquals(3, client.calls.size)
        assertEquals(400, client.calls[0].input().records.size)
        assertEquals(400, client.calls[1].input().records.size)
        assertEquals(241, client.calls[2].input().records.size)
        assertEquals(
            client.calls[0].input().records.takeLast(20),
            client.calls[1].input().records.take(20),
        )
        assertEquals(
            client.calls[1].input().records.takeLast(20),
            client.calls[2].input().records.take(20),
        )
    }

    @Test
    fun `chunking does not create a final overlap-only request`() = runBlocking {
        val client = FunctionalClient { _, _, _ -> """{"episodes":[]}""" }

        EpisodeExtractorImpl(
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
                """{"episodes":[]}"""
            } finally {
                active.decrementAndGet()
            }
        }

        EpisodeExtractorImpl(
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
            SourceRecord(it, "key-$it", "content-$it", SourceRecordAnalysisStatus.PENDING, AccessControl.PUBLIC)
        },
    )

    private fun response(
        text: String = "fact",
        evidenceIds: List<String> = listOf("r1"),
    ): String = """{"episodes":[${episodeJson(text, evidenceIds)}]}"""

    private fun episodeJson(
        text: String = "fact",
        evidenceIds: List<String> = listOf("r1"),
    ): String {
        val evidenceJson = evidenceIds.joinToString(",") { "\"$it\"" }
        return """{"title":"title","text":"$text","evidenceRecordIds":[$evidenceJson]}"""
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

    private fun Call.input(): EpisodeAnalysisInput = userMessage.decodeFromString()
}
