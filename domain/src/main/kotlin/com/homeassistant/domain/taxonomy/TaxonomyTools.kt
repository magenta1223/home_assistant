package com.homeassistant.domain.taxonomy

import com.homeassistant.core.tools.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class TaxonomyTools(private val repo: TaxonomyRepository) : ToolGroup {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class CreateArgs(val name: String, val node_type: String, val parent_id: Int? = null)
    @Serializable private data class ListArgs(val parent_id: Int? = null)
    @Serializable private data class SearchArgs(val query: String)

    override val tools: List<Tool> = listOf(
        Tool(
            name = ToolName("taxonomy_create"),
            description = ToolDescription("taxonomy 노드(카테고리 또는 태그)를 생성합니다"),
            schema = ToolSchema(
                properties = mapOf(
                    "name" to PropertySchema("string", "노드 이름"),
                    "node_type" to PropertySchema("string", "노드 유형", enum = listOf("CATEGORY", "TAG")),
                    "parent_id" to PropertySchema("integer", "부모 노드 ID (루트면 생략)"),
                ),
                required = listOf("name", "node_type"),
            ),
        ),
        Tool(
            name = ToolName("taxonomy_list"),
            description = ToolDescription("taxonomy 노드 목록을 조회합니다. parent_id 없으면 루트부터"),
            schema = ToolSchema(
                properties = mapOf(
                    "parent_id" to PropertySchema("integer", "조회할 부모 노드 ID (생략 시 루트)"),
                ),
            ),
        ),
        Tool(
            name = ToolName("taxonomy_search"),
            description = ToolDescription("이름으로 taxonomy 노드를 검색합니다"),
            schema = ToolSchema(
                properties = mapOf(
                    "query" to PropertySchema("string", "검색어"),
                ),
                required = listOf("query"),
            ),
        ),
    )

    override fun execute(spec: ToolCallSpec): ToolResult = try {
        when (spec.name.value) {
            "taxonomy_create" -> {
                val args = json.decodeFromString<CreateArgs>(spec.arguments.value)
                val id = repo.create(args.name, args.node_type, args.parent_id)
                ToolResult("taxonomy 노드가 생성되었습니다. id=$id name=${args.name} type=${args.node_type}")
            }
            "taxonomy_list" -> {
                val args = json.decodeFromString<ListArgs>(spec.arguments.value)
                val nodes = repo.list(args.parent_id)
                if (nodes.isEmpty()) ToolResult("조회된 taxonomy 노드가 없습니다.")
                else ToolResult(nodes.joinToString("\n") { "[${it.id}] ${it.name} (${it.nodeType})" })
            }
            "taxonomy_search" -> {
                val args = json.decodeFromString<SearchArgs>(spec.arguments.value)
                val nodes = repo.search(args.query)
                if (nodes.isEmpty()) ToolResult("'${args.query}'에 해당하는 taxonomy 노드가 없습니다.")
                else ToolResult(nodes.joinToString("\n") { "[${it.id}] ${it.name} (${it.nodeType})" })
            }
            else -> error("Unhandled tool: ${spec.name.value}")
        }
    } catch (e: Exception) {
        ToolResult("ERROR: ${e.message}")
    }
}
