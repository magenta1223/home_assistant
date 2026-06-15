package com.homeassistant.nlp.analysis

import com.homeassistant.core.nlp.LlmOutputSchema
import com.homeassistant.core.nlp.SystemPrompt
import kotlinx.schema.generator.json.serialization.SerializationClassJsonSchemaGenerator
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
@SerialName("TopicAnalysisOutput")
internal data class TopicAnalysisLlmResponse(val topics: List<TopicLlmResponse>)

@Serializable
@SerialName("Topic")
internal data class TopicLlmResponse(
    val title: String,
    val summary: String,
    val classifications: List<TopicClassificationLlmResponse>,
    val domains: List<String>,
    val evidenceRecordIds: List<String>,
    val claims: List<TopicClaimLlmResponse>,
)

@Serializable
@SerialName("TopicClassification")
internal data class TopicClassificationLlmResponse(
    val memoryKind: TopicAnalysisMemoryKind,
    val memorySubtype: TopicAnalysisMemorySubtype,
)

@Serializable
@SerialName("TopicClaim")
internal data class TopicClaimLlmResponse(
    val text: String,
    val subject: String,
    val classification: TopicClassificationLlmResponse,
    val certainty: ClaimCertainty,
    val evidenceRecordIds: List<String>,
)

@Serializable
internal enum class TopicAnalysisMemoryKind { SEMANTIC, EPISODIC, PROCEDURAL }

@Serializable
internal enum class TopicAnalysisMemorySubtype {
    PROFILE,
    PREFERENCE,
    RELATIONSHIP,
    STATE,
    LOCATION,
    REFERENCE,
    DECISION,
    CONSTRAINT,
    CONVERSATION,
    EVENT,
    TRANSACTION,
    APPOINTMENT,
    CHANGE,
    MILESTONE,
    OBSERVATION,
    ROUTINE,
    CHECKLIST,
    INSTRUCTION,
    RULE,
    RECIPE,
    TROUBLESHOOTING,
    TEMPLATE,
}

internal object TopicAnalysisOutputContract {
    private val json = Json { ignoreUnknownKeys = true }

    val schema: LlmOutputSchema = LlmOutputSchema(
        SerializationClassJsonSchemaGenerator.Default
            .generateSchemaString(TopicAnalysisLlmResponse.serializer().descriptor),
    )

    fun decode(raw: String): TopicAnalysisLlmResponse =
        try {
            json.decodeFromString<TopicAnalysisLlmResponse>(stripJsonCodeFence(raw))
        } catch (error: SerializationException) {
            throw TopicAnalysisException("Failed to parse topic analysis response: ${error.message}")
        }

    private fun stripJsonCodeFence(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed

        val lines = trimmed.lines()
        if (lines.size < 3 || lines.last().trim() != "```") return trimmed

        return lines.drop(1).dropLast(1).joinToString("\n").trim()
    }
}

internal object TopicAnalysisPrompt {
    fun system(schema: LlmOutputSchema = TopicAnalysisOutputContract.schema): SystemPrompt =
        SystemPrompt(
            """
            주어진 source document 전체를 내용 기반으로 주제 분석하세요.
            시간 간격으로 나누지 마세요. 같은 주제가 A-B-A 순서로 중간에 끊겨도 하나의 topic으로 병합하세요.
            각 topic은 가족/집 second brain에 승인 후보로 올릴 수 있는 evidence-backed claim을 1개 이상 포함해야 합니다.
            evidenceRecordIds는 사용자 메시지에 제공된 r1, r2 같은 ID만 사용하세요.
            실제로 말하지 않은 사실을 확정하지 말고, 관찰/발화/추론/불확실성을 구분하세요.
            domain은 housing, moving, travel, food, finance 같은 생활 영역 태그이며 memorySubtype과 분리하세요.
            응답은 아래 JSON Schema를 준수하는 JSON object 하나여야 합니다.

            ${schema.value}
            """.trimIndent(),
        )
}
