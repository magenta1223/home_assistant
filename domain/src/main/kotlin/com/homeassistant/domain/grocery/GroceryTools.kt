package com.homeassistant.domain.grocery

import com.homeassistant.core.tools.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class GroceryTools(private val repo: GroceryRepository) {

    private val json = Json { ignoreUnknownKeys = true }
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())

    @Serializable private data class RecordArgs(val item_name: String, val quantity: Double, val purchased_at: Long? = null)

    val tools: List<Tool> = listOf(
        Tool(
            name = ToolName("grocery_record_purchase"),
            description = ToolDescription("식료품 구매를 기록합니다"),
            schema = ToolSchema(
                properties = mapOf(
                    "item_name" to PropertySchema("string", "식료품 이름"),
                    "quantity" to PropertySchema("number", "구매 수량"),
                    "purchased_at" to PropertySchema("integer", "구매 시각 (epoch ms, 생략 시 현재)"),
                ),
                required = listOf("item_name", "quantity"),
            ),
        ),
        Tool(
            name = ToolName("grocery_list"),
            description = ToolDescription("식료품 항목별 마지막 구매일과 평균 구매 주기를 조회합니다"),
            schema = ToolSchema(),
        ),
        Tool(
            name = ToolName("grocery_due"),
            description = ToolDescription("구매 주기가 도래한 식료품 목록을 조회합니다"),
            schema = ToolSchema(),
        ),
    )

    fun execute(spec: ToolCallSpec): ToolResult = try {
        when (spec.name.value) {
            "grocery_record_purchase" -> {
                val args = json.decodeFromString<RecordArgs>(spec.arguments.value)
                repo.recordPurchase(args.item_name, args.quantity, args.purchased_at)
                ToolResult("'${args.item_name}' ${args.quantity}개 구매가 기록되었습니다.")
            }
            "grocery_list" -> {
                val items = repo.list()
                if (items.isEmpty()) ToolResult("기록된 식료품이 없습니다.")
                else ToolResult(items.joinToString("\n") { formatItem(it) })
            }
            "grocery_due" -> {
                val items = repo.due()
                if (items.isEmpty()) ToolResult("구매 주기가 도래한 식료품이 없습니다.")
                else ToolResult("구매가 필요한 식료품:\n" + items.joinToString("\n") { formatItem(it) })
            }
            else -> ToolResult("ERROR: 알 수 없는 tool: ${spec.name.value}")
        }
    } catch (e: Exception) {
        ToolResult("ERROR: ${e.message}")
    }

    private fun formatItem(s: GroceryItemStats): String {
        val lastDate = s.lastPurchasedAt?.let { dateFmt.format(Instant.ofEpochMilli(it)) } ?: "-"
        val avg = s.avgIntervalDays?.let { "평균 %.0f일".format(it) } ?: "주기 미산출"
        return "${s.name}: 마지막 구매 $lastDate ($avg)"
    }
}
