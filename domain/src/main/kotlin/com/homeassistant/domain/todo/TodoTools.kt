package com.homeassistant.domain.todo

import com.homeassistant.core.tools.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

class TodoTools(private val repo: TodoRepository) : ToolGroup {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class CreateArgs(val title: String, val subtasks: List<String> = emptyList(), val node_ids: List<Int> = emptyList())
    @Serializable private data class AddSubtaskArgs(val todo_id: Int, val title: String)
    @Serializable private data class CompleteArgs(val todo_id: Int, val subtask_id: Int? = null)
    @Serializable private data class ListArgs(val status: String? = null, val node_id: Int? = null)
    @Serializable private data class GetArgs(val id: Int)

    override val tools: List<Tool> = listOf(
        Tool(
            name = ToolName("todo_create"),
            description = ToolDescription("Todo 항목을 생성합니다"),
            schema = ToolSchema(
                properties = mapOf(
                    "title" to PropertySchema("string", "Todo 제목"),
                    "subtasks" to PropertySchema("array", "하위 작업 목록 (선택)", items = PropertySchema("string", "하위 작업 제목")),
                    "node_ids" to PropertySchema("array", "taxonomy node ID 목록 (선택)", items = PropertySchema("integer", "node ID")),
                ),
                required = listOf("title"),
            ),
        ),
        Tool(
            name = ToolName("todo_add_subtask"),
            description = ToolDescription("기존 Todo에 하위 작업을 추가합니다"),
            schema = ToolSchema(
                properties = mapOf(
                    "todo_id" to PropertySchema("integer", "Todo ID"),
                    "title" to PropertySchema("string", "하위 작업 제목"),
                ),
                required = listOf("todo_id", "title"),
            ),
        ),
        Tool(
            name = ToolName("todo_complete"),
            description = ToolDescription("Todo 또는 하위 작업을 완료 처리합니다"),
            schema = ToolSchema(
                properties = mapOf(
                    "todo_id" to PropertySchema("integer", "Todo ID"),
                    "subtask_id" to PropertySchema("integer", "하위 작업 ID (생략 시 Todo 전체 완료)"),
                ),
                required = listOf("todo_id"),
            ),
        ),
        Tool(
            name = ToolName("todo_list"),
            description = ToolDescription("Todo 목록을 조회합니다. 경과 시간 포함"),
            schema = ToolSchema(
                properties = mapOf(
                    "status" to PropertySchema("string", "상태 필터 (선택)", enum = listOf("PENDING", "DONE")),
                    "node_id" to PropertySchema("integer", "taxonomy 필터 (선택)"),
                ),
            ),
        ),
        Tool(
            name = ToolName("todo_get"),
            description = ToolDescription("Todo 상세 조회 (하위 작업 포함)"),
            schema = ToolSchema(
                properties = mapOf("id" to PropertySchema("integer", "Todo ID")),
                required = listOf("id"),
            ),
        ),
    )

    override fun execute(spec: ToolCallSpec): ToolResult = try {
        when (spec.name.value) {
            "todo_create" -> {
                val args = json.decodeFromString<CreateArgs>(spec.arguments.value)
                val id = repo.create(args.title, args.subtasks, args.node_ids)
                ToolResult("Todo가 생성되었습니다. id=$id title=${args.title}")
            }
            "todo_add_subtask" -> {
                val args = json.decodeFromString<AddSubtaskArgs>(spec.arguments.value)
                val id = repo.addSubtask(args.todo_id, args.title)
                ToolResult("하위 작업이 추가되었습니다. id=$id title=${args.title}")
            }
            "todo_complete" -> {
                val args = json.decodeFromString<CompleteArgs>(spec.arguments.value)
                val completed = repo.complete(args.todo_id, args.subtask_id)
                if (completed) ToolResult("완료 처리되었습니다.")
                else ToolResult("ERROR: 해당 Todo/하위 작업을 찾을 수 없습니다.")
            }
            "todo_list" -> {
                val args = json.decodeFromString<ListArgs>(spec.arguments.value)
                val todos = repo.list(args.status, args.node_id)
                if (todos.isEmpty()) ToolResult("해당하는 Todo가 없습니다.")
                else ToolResult(todos.joinToString("\n") { formatTodo(it) })
            }
            "todo_get" -> {
                val args = json.decodeFromString<GetArgs>(spec.arguments.value)
                val todo = repo.get(args.id) ?: return ToolResult("ERROR: id=${args.id} Todo를 찾을 수 없습니다.")
                ToolResult(formatTodoDetail(todo))
            }
            else -> error("Unhandled tool: ${spec.name.value}")
        }
    } catch (e: Exception) {
        ToolResult("ERROR: ${e.message}")
    }

    private fun formatTodo(t: TodoRow): String {
        val elapsed = elapsedLabel(t.createdAt)
        val subtaskSummary = if (t.subtasks.isEmpty()) "" else " [${t.subtasks.count { it.status == "DONE" }}/${t.subtasks.size}]"
        return "id=${t.id} [${t.status}]$subtaskSummary ${t.title} ($elapsed)"
    }

    private fun formatTodoDetail(t: TodoRow): String {
        val lines = mutableListOf("id=${t.id} [${t.status}] ${t.title} (${elapsedLabel(t.createdAt)})")
        t.subtasks.forEach { lines.add("  - [${it.status}] id=${it.id} ${it.title}") }
        return lines.joinToString("\n")
    }

    private fun elapsedLabel(createdAt: Long): String {
        val diffMs = System.currentTimeMillis() - createdAt
        val days = TimeUnit.MILLISECONDS.toDays(diffMs)
        val hours = TimeUnit.MILLISECONDS.toHours(diffMs) % 24
        return when {
            days > 0 -> "${days}일 전"
            hours > 0 -> "${hours}시간 전"
            else -> "방금"
        }
    }
}
