package com.homeassistant.nlp.analysis

import com.homeassistant.core.nlp.LlmOutputSchema
import kotlinx.schema.generator.json.serialization.SerializationClassJsonSchemaGenerator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    val memorySubtype: String,
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

internal object TopicAnalysisOutputSchema {
    val value: LlmOutputSchema = LlmOutputSchema(
        SerializationClassJsonSchemaGenerator.Default
            .generateSchemaString(TopicAnalysisLlmResponse.serializer().descriptor),
    )
}
