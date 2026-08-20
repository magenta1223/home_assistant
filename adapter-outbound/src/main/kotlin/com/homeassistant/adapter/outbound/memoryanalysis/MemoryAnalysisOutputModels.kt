package com.homeassistant.adapter.outbound.memoryanalysis

import com.homeassistant.common.json.JsonSerializer.decodeFromString
import com.homeassistant.common.json.JsonSerializer.encodeToString
import com.homeassistant.common.json.JsonSerializer.parseToJsonElement
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryType
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
internal data class MemoryAnalysisLlmResponse(
    @property:SerialDescription("Evidence-backed atomic memories. Return an empty array when there are no reusable memories.")
    val memories: List<MemoryLlmResponse>,
)

@Schema
@Serializable
internal data class MemoryLlmResponse(
    @property:SerialDescription("One atomic memory statement supported by the cited evidence. Do not combine unrelated facts.")
    val text: String,
    @property:SerialDescription("Person, place, object, organization, or other entity the memory is about.")
    val subject: String,
    @property:SerialDescription("Allowed MemoryType enum value for this single memory.")
    val memoryType: MemoryType,
    @property:SerialDescription("How directly the source evidence supports this memory.")
    val certainty: MemoryCertainty,
    @property:SerialDescription("Source record ids supporting this memory, using ids such as r1 or r2 from the input.")
    val evidenceRecordIds: List<String>,
)

@OptIn(ExperimentalSerializationApi::class)
internal object MemoryAnalysisOutputContract {
    val schema = SerializationClassJsonSchemaGenerator(
        json = com.homeassistant.common.json.JsonSerializer.json,
        jsonSchemaConfig = JsonSchemaConfig.Strict,
    )
        .generateSchemaString(MemoryAnalysisLlmResponse.serializer().descriptor)
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

    fun decode(raw: String): MemoryAnalysisLlmResponse =
        try {
            stripJsonCodeFence(raw).decodeFromString()
        } catch (error: SerializationException) {
            throw IllegalArgumentException("Failed to parse memory analysis response: ${error.message}", error)
        }

    private fun stripJsonCodeFence(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val lines = trimmed.lines()
        if (lines.size < 2) return trimmed
        return lines.drop(1).takeWhile { it.trim() != "```" }.joinToString("\n").trim()
    }
}
