package com.homeassistant.adapter.outbound.memoryanalysis

import com.homeassistant.adapter.outbound.codex.CodexCompletionClient
import com.homeassistant.application.memory.tree.MemoryPlacementBatchResult
import com.homeassistant.application.memory.tree.MemoryPlacementDecision
import com.homeassistant.application.memory.tree.MemoryPlacementDecisionType
import com.homeassistant.application.memory.tree.MemoryPlacementException
import com.homeassistant.application.memory.tree.MemoryPlacementExtractor
import com.homeassistant.application.memory.tree.MemoryPlacementInput
import com.homeassistant.common.json.JsonSerializer.decodeFromString
import com.homeassistant.common.json.JsonSerializer.encodeToString
import com.homeassistant.common.json.JsonSerializer.parseToJsonElement
import com.homeassistant.domain.memory.Memory
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

/** Makes one structured Codex call for a batch and returns one placement per memory. */
internal class CodexMemoryPlacementExtractor(
    private val client: CodexCompletionClient,
) : MemoryPlacementExtractor {
    override suspend fun analyze(inputs: List<MemoryPlacementInput>): MemoryPlacementBatchResult {
        if (inputs.isEmpty()) return MemoryPlacementBatchResult(emptyList())
        val raw = client.complete(
            system = MemoryPlacementPrompt.system(MemoryPlacementOutputContract.schema),
            userMessage = renderInputs(inputs),
            outputSchema = MemoryPlacementOutputContract.schema,
        )
        val response = MemoryPlacementOutputContract.decode(raw)
        return MemoryPlacementBatchResult(
            decisions = response.decisions.map { decision ->
                val type = runCatching {
                    MemoryPlacementDecisionType.valueOf(decision.decision.trim().uppercase())
                }.getOrElse {
                    throw MemoryPlacementException("Unknown placement decision: ${decision.decision}")
                }
                MemoryPlacementDecision(
                    memoryId = decision.memoryId,
                    decision = type,
                    containerId = decision.containerId,
                )
            },
        )
    }

    private fun renderInputs(inputs: List<MemoryPlacementInput>): String = buildString {
        appendLine("신규 memory 목록:")
        inputs.forEach { input ->
            appendLine("memoryId=${input.memory.id}")
            appendLine("subject=${input.memory.subject}")
            appendLine("content=${input.memory.content}")
            appendLine("후보 부모:")
            if (input.candidates.isEmpty()) {
                appendLine("- 없음")
            } else {
                input.candidates.forEach { candidate ->
                    appendLine("- id=${candidate.id} | subject=${candidate.subject} | content=${candidate.content}")
                }
            }
            appendLine()
        }
    }
}

internal object MemoryPlacementPrompt {
    fun system(schema: String): String =
        """
        신규 atomic memory들을 기존 단일 부모 트리에 배치하세요.

        각 memory마다 다음 중 하나를 결정하세요.
        - EXISTING_PARENT: 후보 목록에 있는 기존 부모 id를 선택
        - ROOT: 적절한 부모가 없으므로 최상위에 유지

        후보 목록에 없는 id를 containerId로 사용하지 마세요.
        서로 관련 없는 memory를 같은 그룹에 넣지 마세요.
        모든 입력 memory에 대해 정확히 하나의 decision을 반환하세요.
        응답은 아래 JSON Schema를 준수하는 JSON object 하나여야 합니다.

        $schema
        """.trimIndent()
}

@Serializable
internal data class MemoryPlacementLlmResponse(
    @property:SerialDescription("One placement decision for every input memory.")
    val decisions: List<MemoryPlacementLlmDecision>,
)

@Schema
@Serializable
internal data class MemoryPlacementLlmDecision(
    @property:SerialDescription("The input memory id.")
    val memoryId: Int,
    @property:SerialDescription("EXISTING_PARENT or ROOT.")
    val decision: String,
    @property:SerialDescription("Existing candidate container id; only for EXISTING_PARENT.")
    val containerId: Int? = null,
)

@OptIn(ExperimentalSerializationApi::class)
internal object MemoryPlacementOutputContract {
    val schema = SerializationClassJsonSchemaGenerator(
        json = com.homeassistant.common.json.JsonSerializer.json,
        jsonSchemaConfig = JsonSchemaConfig.Strict,
    )
        .generateSchemaString(MemoryPlacementLlmResponse.serializer().descriptor)
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

    fun decode(raw: String): MemoryPlacementLlmResponse =
        try {
            stripJsonCodeFence(raw).decodeFromString()
        } catch (error: SerializationException) {
            throw MemoryPlacementException("Failed to parse memory placement response: ${error.message}")
        }

    private fun stripJsonCodeFence(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val lines = trimmed.lines()
        if (lines.size < 2) return trimmed
        return lines.drop(1).takeWhile { it.trim() != "```" }.joinToString("\n").trim()
    }
}
