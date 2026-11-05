package com.homeassistant.adapter.outbound.episodeanalysis

import com.homeassistant.infrastructure.serialization.JsonSerializer
import com.homeassistant.infrastructure.serialization.JsonSerializer.decodeFromString
import com.homeassistant.infrastructure.serialization.JsonSerializer.encodeToString
import com.homeassistant.infrastructure.serialization.JsonSerializer.parseToJsonElement
import kotlinx.schema.Schema
import kotlinx.schema.generator.json.JsonSchemaConfig
import kotlinx.schema.generator.json.SerialDescription
import kotlinx.schema.generator.json.serialization.SerializationClassJsonSchemaGenerator
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
internal data class EpisodeAnalysisInput(
    val records: List<EpisodeAnalysisInputRecord>,
)

/** Message time is left out on purpose: the model repeats it in the body, and the source record keeps it. */
@Serializable
internal data class EpisodeAnalysisInputRecord(
    val id: String,
    val text: String,
)

@Serializable
internal data class EpisodeAnalysisLlmResponse(
    @property:SerialDescription("Evidence-backed atomic episodes. Return an empty array when there are no reusable episodes.")
    val episodes: List<EpisodeLlmResponse>,
)

@Schema
@Serializable
internal data class EpisodeLlmResponse(
    @property:SerialDescription("이 episode의 주제와 활동을 담은 검색하기 쉬운 제목")
    val title: String,
    @property:SerialDescription("근거 기록으로 뒷받침되는 독립적인 episode 본문")
    val text: String,
    @property:SerialDescription("이 episode의 근거인 records의 id")
    val evidenceRecordIds: List<String>,
)

@OptIn(ExperimentalSerializationApi::class)
internal object EpisodeAnalysisOutputContract {
    val schema = SerializationClassJsonSchemaGenerator(
        json = JsonSerializer.json,
        jsonSchemaConfig = JsonSchemaConfig.Strict,
    )
        .generateSchemaString(EpisodeAnalysisLlmResponse.serializer().descriptor)
        .inlineLocalDefinitions()

    private fun String.inlineLocalDefinitions(): String {
        val root = parseToJsonElement().jsonObject
        val definitions = root["${'$'}defs"]?.jsonObject.orEmpty()
        return root.inlineLocalReferences(definitions).encodeToString()
    }

    private fun JsonElement.inlineLocalReferences(definitions: Map<String, JsonElement>): JsonElement {
        return when (this) {
            is JsonObject -> {
                val ref = this["${'$'}ref"]?.jsonPrimitive?.contentOrNull
                if (ref != null) {
                    val referenced = definitions[ref.localDefinitionName()]
                    if (referenced != null) {
                        return JsonObject(
                            referenced.inlineLocalReferences(definitions).jsonObject +
                                this.filterKeys { it != "${'$'}ref" && it != "${'$'}defs" }
                                    .mapValues { (_, value) -> value.inlineLocalReferences(definitions) },
                        )
                    }
                }
                JsonObject(
                    filterKeys { it != "${'$'}defs" }
                        .mapValues { (_, value) -> value.inlineLocalReferences(definitions) },
                )
            }
            else -> this
        }
    }

    private fun String.localDefinitionName(): String? =
        takeIf { it.startsWith("#/${'$'}defs/") }
            ?.removePrefix("#/${'$'}defs/")
            ?.replace("~1", "/")
            ?.replace("~0", "~")

    fun decode(raw: String): EpisodeAnalysisLlmResponse =
        try {
            stripJsonCodeFence(raw).decodeFromString()
        } catch (error: SerializationException) {
            throw IllegalArgumentException("Failed to parse episode analysis response: ${error.message}", error)
        }

    private fun stripJsonCodeFence(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val lines = trimmed.lines()
        if (lines.size < 2) return trimmed
        return lines.drop(1).takeWhile { it.trim() != "```" }.joinToString("\n").trim()
    }
}
