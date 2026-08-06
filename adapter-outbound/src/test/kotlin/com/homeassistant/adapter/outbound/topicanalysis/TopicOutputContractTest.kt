package com.homeassistant.adapter.outbound.topicanalysis

import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.application.topicanalysis.analyze.TopicExtractionException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class LlmTopicOutputContractTest {
    @Test
    fun `rejects invalid topic analysis responses`() = runBlocking {
        val invalid = listOf(
            "not json",
            topicJson(memoryTypes = """["UNKNOWN"]"""),
            topicJson(evidenceRecordIds = """["missing"]"""),
            topicJson(title = ""),
            topicJson(summary = ""),
        )

        invalid.forEach { response ->
            assertFailsWith<TopicExtractionException> {
                serviceFor(response).analyze(singleRecordDocument())
            }
        }
    }

    @Test
    fun `accepts fenced and trailing topic responses`() = runBlocking {
        val fenced = serviceFor("```json\n${topicJson()}\n```").analyze(singleRecordDocument())
        val trailing = serviceFor("```json\n${topicJson()}\n```\n분석 완료")
            .analyze(singleRecordDocument())

        assertEquals("관계 표현", fenced.single().title)
        assertEquals("관계 표현", trailing.single().title)
    }

    @Test
    fun `decodes concrete memory type values`() {
        val cases = mapOf(
            "STATE" to MemoryType.STATE,
            "OBSERVATION" to MemoryType.OBSERVATION,
            "CHECKLIST" to MemoryType.CHECKLIST,
        )

        cases.forEach { (raw, expected) ->
            val decoded = TopicAnalysisOutputContract.decode(topicJson(memoryTypes = """["$raw"]"""))
            assertEquals(expected, decoded.topics.single().memoryTypes.single())
        }
    }

    @Test
    fun `accepts trailing commas`() = runBlocking {
        val response = topicJson().replace("}\n          ]", "},\n          ]")
            .replace("}\n      ]", "},\n      ]")
        val result = serviceFor(response).analyze(singleRecordDocument())

        assertEquals("관계 표현", result.single().title)
    }

    @Test
    fun `rejects invalid topic memories`() = runBlocking {
        val invalid = listOf(
            topicJson(memories = "[]"),
            topicJson(memoryText = ""),
            topicJson(memorySubject = ""),
            topicJson(memoryType = "UNKNOWN"),
            topicJson(memoryCertainty = "GUESSED"),
            topicJson(memoryEvidenceRecordIds = """["missing"]"""),
        )

        invalid.forEach { response ->
            assertFailsWith<TopicExtractionException> {
                serviceFor(response).analyze(singleRecordDocument())
            }
        }
    }

    @Test
    fun `schema contains fields descriptions and inline definitions`() {
        val schema = TopicAnalysisOutputContract.schema
        listOf(
            "topics",
            "memories",
            "memoryTypes",
            "memoryType",
            "certainty",
            "evidenceRecordIds",
            "Proposed topics extracted from the source document",
            "Short review-facing title for one grouped household memory topic",
            "Concise summary of why the grouped records belong together",
            "Allowed MemoryType enum values represented by this topic",
            "Evidence-backed canonical-memory proposals under this topic",
            "Atomic memory statement supported by the cited evidence",
            "How directly the source evidence supports this memory",
            "\"enum\"",
            "\"STATE\"",
        ).forEach { assertContains(schema, it) }
        assertFalse(schema.contains("memoryKind"))
        assertFalse(schema.contains("memorySubtype"))
        assertFalse(schema.contains("\"${'$'}ref\""))
        assertFalse(schema.contains("\"${'$'}defs\""))
    }

    @Test
    fun `dto names come from class names without overrides`() {
        assertEquals(
            "com.homeassistant.adapter.outbound.topicanalysis.TopicAnalysisLlmResponse",
            TopicAnalysisLlmResponse.serializer().descriptor.serialName,
        )
        assertEquals(
            "com.homeassistant.adapter.outbound.topicanalysis.TopicLlmResponse",
            TopicLlmResponse.serializer().descriptor.serialName,
        )
        assertEquals(
            "com.homeassistant.adapter.outbound.topicanalysis.MemoryLlmResponse",
            MemoryLlmResponse.serializer().descriptor.serialName,
        )
    }
}
