package com.homeassistant.domain.asset

import com.homeassistant.core.tools.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class AssetTools(private val repo: AssetRepository) {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class AddArgs(
        val name: String, val asset_type: String,
        val purchase_price: Double? = null, val current_value: Double? = null,
        val currency: String, val notes: String? = null,
    )
    @Serializable private data class UpdateValueArgs(val id: Int, val value: Double)
    @Serializable private data class ListArgs(val asset_type: String? = null)

    val tools: List<Tool> = listOf(
        Tool(
            name = ToolName("asset_add"),
            description = ToolDescription("자산을 추가합니다"),
            schema = ToolSchema(
                properties = mapOf(
                    "name" to PropertySchema("string", "자산 이름"),
                    "asset_type" to PropertySchema("string", "자산 유형", enum = listOf("FINANCIAL", "PHYSICAL")),
                    "purchase_price" to PropertySchema("number", "매입가 (선택)"),
                    "current_value" to PropertySchema("number", "현재 가치 (선택)"),
                    "currency" to PropertySchema("string", "통화 (예: KRW, USD)"),
                    "notes" to PropertySchema("string", "메모 (선택)"),
                ),
                required = listOf("name", "asset_type", "currency"),
            ),
        ),
        Tool(
            name = ToolName("asset_update_value"),
            description = ToolDescription("자산 현재 가치를 갱신하고 이력을 기록합니다"),
            schema = ToolSchema(
                properties = mapOf(
                    "id" to PropertySchema("integer", "자산 ID"),
                    "value" to PropertySchema("number", "새 현재 가치"),
                ),
                required = listOf("id", "value"),
            ),
        ),
        Tool(
            name = ToolName("asset_list"),
            description = ToolDescription("자산 목록을 조회합니다"),
            schema = ToolSchema(
                properties = mapOf(
                    "asset_type" to PropertySchema("string", "자산 유형 필터 (선택)", enum = listOf("FINANCIAL", "PHYSICAL")),
                ),
            ),
        ),
        Tool(
            name = ToolName("asset_summary"),
            description = ToolDescription("자산 전체 합계를 유형별, 통화별로 조회합니다"),
            schema = ToolSchema(),
        ),
    )

    fun execute(spec: ToolCallSpec): ToolResult = try {
        when (spec.name.value) {
            "asset_add" -> {
                val args = json.decodeFromString<AddArgs>(spec.arguments.value)
                val id = repo.add(args.name, args.asset_type, args.purchase_price, args.current_value, args.currency, args.notes)
                ToolResult("자산이 추가되었습니다. id=$id name=${args.name}")
            }
            "asset_update_value" -> {
                val args = json.decodeFromString<UpdateValueArgs>(spec.arguments.value)
                val updated = repo.updateValue(args.id, args.value)
                if (updated) ToolResult("자산(id=${args.id}) 현재 가치가 ${args.value}로 갱신되었습니다.")
                else ToolResult("ERROR: id=${args.id} 자산을 찾을 수 없습니다.")
            }
            "asset_list" -> {
                val args = json.decodeFromString<ListArgs>(spec.arguments.value)
                val assets = repo.list(args.asset_type)
                if (assets.isEmpty()) ToolResult("자산이 없습니다.")
                else ToolResult(assets.joinToString("\n") {
                    "id=${it.id} [${it.assetType}] ${it.name} ${it.currentValue ?: "-"} ${it.currency}"
                })
            }
            "asset_summary" -> {
                val summary = repo.summary()
                if (summary.isEmpty()) ToolResult("자산이 없습니다.")
                else ToolResult(summary.entries.joinToString("\n") { (type, currencies) ->
                    currencies.entries.joinToString("\n") { (currency, total) ->
                        "$type / $currency: $total"
                    }
                })
            }
            else -> ToolResult("ERROR: 알 수 없는 tool: ${spec.name.value}")
        }
    } catch (e: Exception) {
        ToolResult("ERROR: ${e.message}")
    }
}
