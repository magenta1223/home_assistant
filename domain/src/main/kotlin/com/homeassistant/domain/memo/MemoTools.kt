package com.homeassistant.domain.memo

import com.homeassistant.core.tools.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class MemoTools(private val repo: MemoRepository) {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class CreateArgs(val title: String, val content: String, val node_ids: List<Int> = emptyList())
    @Serializable private data class SearchArgs(val query: String, val node_ids: List<Int>? = null)
    @Serializable private data class ListArgs(val node_id: Int? = null)
    @Serializable private data class UpdateArgs(val id: Int, val title: String? = null, val content: String? = null, val node_ids: List<Int>? = null)
    @Serializable private data class DeleteArgs(val id: Int)

    val tools: List<Tool> = listOf(
        Tool(
            name = ToolName("memo_create"),
            description = ToolDescription("메모를 생성합니다"),
            schema = ToolSchema(
                properties = mapOf(
                    "title" to PropertySchema("string", "메모 제목"),
                    "content" to PropertySchema("string", "메모 내용"),
                    "node_ids" to PropertySchema("array", "taxonomy node ID 목록", items = PropertySchema("integer", "node ID")),
                ),
                required = listOf("title", "content"),
            ),
        ),
        Tool(
            name = ToolName("memo_search"),
            description = ToolDescription("제목/내용으로 메모를 전문 검색합니다"),
            schema = ToolSchema(
                properties = mapOf(
                    "query" to PropertySchema("string", "검색어"),
                    "node_ids" to PropertySchema("array", "taxonomy 필터 (선택)", items = PropertySchema("integer", "node ID")),
                ),
                required = listOf("query"),
            ),
        ),
        Tool(
            name = ToolName("memo_list"),
            description = ToolDescription("메모 목록을 조회합니다. taxonomy 필터 가능"),
            schema = ToolSchema(
                properties = mapOf(
                    "node_id" to PropertySchema("integer", "taxonomy 필터 (선택)"),
                ),
            ),
        ),
        Tool(
            name = ToolName("memo_update"),
            description = ToolDescription("메모를 수정합니다"),
            schema = ToolSchema(
                properties = mapOf(
                    "id" to PropertySchema("integer", "메모 ID"),
                    "title" to PropertySchema("string", "새 제목 (선택)"),
                    "content" to PropertySchema("string", "새 내용 (선택)"),
                    "node_ids" to PropertySchema("array", "새 taxonomy node ID 목록 (선택)", items = PropertySchema("integer", "node ID")),
                ),
                required = listOf("id"),
            ),
        ),
        Tool(
            name = ToolName("memo_delete"),
            description = ToolDescription("메모를 삭제합니다"),
            schema = ToolSchema(
                properties = mapOf("id" to PropertySchema("integer", "메모 ID")),
                required = listOf("id"),
            ),
        ),
    )

    fun execute(spec: ToolCallSpec): ToolResult = try {
        when (spec.name.value) {
            "memo_create" -> {
                val args = json.decodeFromString<CreateArgs>(spec.arguments.value)
                val id = repo.create(args.title, args.content, args.node_ids)
                ToolResult("메모가 생성되었습니다. id=$id title=${args.title}")
            }
            "memo_search" -> {
                val args = json.decodeFromString<SearchArgs>(spec.arguments.value)
                val memos = repo.search(args.query, args.node_ids)
                if (memos.isEmpty()) ToolResult("'${args.query}'에 해당하는 메모가 없습니다.")
                else ToolResult(memos.joinToString("\n") { formatMemo(it) })
            }
            "memo_list" -> {
                val args = json.decodeFromString<ListArgs>(spec.arguments.value)
                val memos = repo.list(args.node_id)
                if (memos.isEmpty()) ToolResult("메모가 없습니다.")
                else ToolResult(memos.joinToString("\n") { formatMemo(it) })
            }
            "memo_update" -> {
                val args = json.decodeFromString<UpdateArgs>(spec.arguments.value)
                repo.update(args.id, args.title, args.content, args.node_ids)
                ToolResult("메모(id=${args.id})가 수정되었습니다.")
            }
            "memo_delete" -> {
                val args = json.decodeFromString<DeleteArgs>(spec.arguments.value)
                val deleted = repo.delete(args.id)
                if (deleted) ToolResult("메모(id=${args.id})가 삭제되었습니다.")
                else ToolResult("ERROR: id=${args.id} 메모를 찾을 수 없습니다.")
            }
            else -> ToolResult("ERROR: 알 수 없는 tool: ${spec.name.value}")
        }
    } catch (e: Exception) {
        ToolResult("ERROR: ${e.message}")
    }

    private fun formatMemo(m: MemoRow) =
        "id=${m.id} [${m.title}] ${m.content.take(80)}${if (m.content.length > 80) "..." else ""}"
}
