package com.homeassistant.adapter.outbound.memoryanalysis

import com.homeassistant.adapter.outbound.codex.CodexCompletionClient
import com.homeassistant.application.memory.tree.MemoryPlacementDecision
import com.homeassistant.application.memory.tree.MemoryPlacementException
import com.homeassistant.application.memory.tree.MemoryPlacementExtractor
import com.homeassistant.application.memory.tree.MemoryPlacementInput
import com.homeassistant.application.memory.tree.MemoryPlacementResponse
import com.homeassistant.common.json.JsonSerializer.decodeFromString
import com.homeassistant.common.json.JsonSerializer.encodeToString
import com.homeassistant.common.json.JsonSerializer.parseToJsonElement
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

/** Makes one structured Codex call for the complete placement batch. */
internal class CodexMemoryPlacementExtractor(
    private val client: CodexCompletionClient,
) : MemoryPlacementExtractor {
    override suspend fun analyze(input: MemoryPlacementInput): MemoryPlacementResponse {
        if (input.memories.isEmpty()) return MemoryPlacementResponse(emptyList())
        val raw = client.complete(
            system = MemoryPlacementPrompt.system(MemoryPlacementOutputContract.schema),
            userMessage = renderInput(input),
            outputSchema = MemoryPlacementOutputContract.schema,
        )
        val response = MemoryPlacementOutputContract.decode(raw)
        return MemoryPlacementResponse(
            decisions = response.placements.map { placement ->
                MemoryPlacementDecision(
                    memoryId = placement.memoryId,
                    parentId = placement.parentId,
                )
            },
        )
    }

    private fun renderInput(input: MemoryPlacementInput): String = buildString {
        appendLine("사용자에게 접근 가능한 기존 memory directory tree:")
        appendLine(input.visibleMemoryTree)
        appendLine()
        appendLine("이번 batch에서 새로 저장된 memory 목록:")
        input.memories.forEach { memory ->
            appendLine("memoryId=${memory.id}")
            appendLine("subject=${memory.subject}")
            appendLine("content=${memory.content}")
            appendLine()
        }
    }
}

internal object MemoryPlacementPrompt {
    fun system(schema: String): String =
        """
        새로 저장된 memory batch를 기존 single-parent directory tree에 배치하세요.

        각 입력 memory에 대해 parentId를 하나만 결정하세요.
        - 기존 tree에 표시된 memory id를 parentId로 선택할 수 있습니다.
        - 이번 batch의 다른 입력 memory id를 parentId로 선택할 수 있습니다.
        - 적절한 부모가 없으면 parentId를 null로 하여 root에 둡니다.

        이번 batch에 없는 id, 기존 tree에 표시되지 않은 id, 새로 만든 구조/container memory를 사용하지 마세요.
        batch 안에서 부모가 되는 memory도 함께 고려하여, 관련된 경우 직접 부모로 지정하세요.
        서로 관련 없는 memory를 같은 부모 아래에 넣지 마세요.
        모든 입력 memory에 대해 정확히 하나의 placement를 반환하세요.
        응답은 아래 JSON Schema를 준수하는 JSON object 하나여야 합니다.

        $schema
        """.trimIndent()
}

@Serializable
internal data class MemoryPlacementLlmResponse(
    @property:SerialDescription("One placement for every input memory.")
    val placements: List<MemoryPlacementLlmPlacement>,
)

@Schema
@Serializable
internal data class MemoryPlacementLlmPlacement(
    @property:SerialDescription("The input memory id.")
    val memoryId: Int,
    @property:SerialDescription("Direct parent memory id, or null when the memory stays at the root.")
    val parentId: Int?,
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
