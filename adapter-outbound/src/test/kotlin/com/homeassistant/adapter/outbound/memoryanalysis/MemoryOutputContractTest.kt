package com.homeassistant.adapter.outbound.memoryanalysis

import com.homeassistant.domain.memory.MemoryType
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class MemoryOutputContractTest {
    @Test
    fun `accepts fenced memory response`() = runBlocking {
        val result = CodexMemoryExtractor(
            ContractStaticClient("```json\n${memoryJson()}\n```"),
        ).analyze(singleRecordDocument())

        assertEquals("관계 표현", result.single().subject)
    }

    @Test
    fun `schema is flat and contains memory fields`() {
        val schema = MemoryAnalysisOutputContract.schema

        assertContains(schema, "memories")
        assertContains(schema, "memoryType")
        assertContains(schema, "certainty")
        assertContains(schema, "evidenceRecordIds")
        assertContains(schema, "\"STATE\"")
        assertFalse(schema.contains("topics"))
        assertFalse(schema.contains("title"))
        assertFalse(schema.contains("categories"))
        assertFalse(schema.contains("\"${'$'}ref\""))
        assertFalse(schema.contains("\"${'$'}defs\""))
    }
}

private fun singleRecordDocument() = com.homeassistant.domain.source.SourceDocument(
    source = com.homeassistant.domain.source.SourceDescriptor("kakao", "family.txt"),
    records = listOf(com.homeassistant.domain.source.SourceRecord(1, "key-1", "동훈 | 따랑해")),
)

private fun memoryJson() =
    """{"memories":[{"text":"동훈은 애정 표현을 했다.","subject":"관계 표현","memoryType":"STATE","certainty":"OBSERVED","evidenceRecordIds":["r1"]}]}"""

private class ContractStaticClient(private val response: String) : com.homeassistant.adapter.outbound.codex.CodexCompletionClient {
    override suspend fun complete(system: String, userMessage: String, outputSchema: String): String = response
}
