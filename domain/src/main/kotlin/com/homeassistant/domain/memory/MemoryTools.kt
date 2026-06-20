package com.homeassistant.domain.memory

import com.homeassistant.core.identity.UserId
import com.homeassistant.core.tools.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class MemoryTools(
    private val repo: MemoryRepository,
    private val embeddingService: EmbeddingService,
    private val vectorStore: VectorStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class CreateCandidateArgs(
        @SerialName("conversation_id") val conversationId: String,
        val domain: String,
        @SerialName("memory_type") val memoryType: MemoryType,
        val content: String,
        val summary: String,
        val confidence: Double,
        @SerialName("subject_member_id") val subjectMemberId: String? = null,
        @SerialName("source_conversation_message_id") val sourceConversationMessageId: Int? = null,
    )

    @Serializable private data class CandidateIdArgs(@SerialName("candidate_id") val candidateId: Int)

    @Serializable
    private data class SearchArgs(
        val query: String,
        @SerialName("memory_type") val memoryType: MemoryType? = null,
        val domain: String? = null,
        @SerialName("member_id") val memberId: String? = null,
        @SerialName("created_after") val createdAfter: Long? = null,
        @SerialName("created_before") val createdBefore: Long? = null,
        val limit: Int = 5,
    )

    val tools: List<Tool> = listOf(
        Tool(
            ToolName("memory_candidate_create"),
            ToolDescription("사용자 승인이 필요한 장기 기억 후보를 생성합니다"),
            ToolSchema(
                properties = mapOf(
                    "conversation_id" to PropertySchema("string", "대화 ID"),
                    "domain" to PropertySchema("string", "생활 영역 이름"),
                    "memory_type" to PropertySchema(
                        "string",
                        "기억 타입. PROFILE, PREFERENCE, RELATIONSHIP, STATE, LOCATION, REFERENCE, DECISION, CONSTRAINT, CONVERSATION, EVENT, TRANSACTION, APPOINTMENT, CHANGE, MILESTONE, OBSERVATION, ROUTINE, CHECKLIST, INSTRUCTION, RULE, RECIPE, TROUBLESHOOTING, TEMPLATE 중 하나",
                    ),
                    "content" to PropertySchema("string", "원문에 가까운 기억 내용"),
                    "summary" to PropertySchema("string", "짧은 요약"),
                    "confidence" to PropertySchema("number", "0.0-1.0 신뢰도"),
                    "subject_member_id" to PropertySchema("string", "대상 구성원 ID"),
                ),
                required = listOf("conversation_id", "domain", "memory_type", "content", "summary", "confidence"),
            ),
        ),
        Tool(
            ToolName("memory_candidate_list_pending"),
            ToolDescription("현재 대화와 사용자 기준 pending 기억 후보를 조회합니다"),
            ToolSchema(
                properties = mapOf("conversation_id" to PropertySchema("string", "대화 ID")),
                required = listOf("conversation_id"),
            ),
        ),
        Tool(
            ToolName("memory_candidate_approve"),
            ToolDescription("기억 후보를 승인하고 장기 기억으로 저장합니다"),
            ToolSchema(properties = mapOf("candidate_id" to PropertySchema("integer", "후보 ID")), required = listOf("candidate_id")),
        ),
        Tool(
            ToolName("memory_candidate_reject"),
            ToolDescription("기억 후보를 거절합니다"),
            ToolSchema(properties = mapOf("candidate_id" to PropertySchema("integer", "후보 ID")), required = listOf("candidate_id")),
        ),
        Tool(
            ToolName("memory_search"),
            ToolDescription("장기 기억을 의미 검색합니다"),
            ToolSchema(
                properties = mapOf(
                    "query" to PropertySchema("string", "검색 질의"),
                    "memory_type" to PropertySchema("string", "기억 타입 필터"),
                    "domain" to PropertySchema("string", "생활 영역 필터"),
                    "member_id" to PropertySchema("string", "구성원 필터"),
                    "limit" to PropertySchema("integer", "최대 결과 수"),
                ),
                required = listOf("query"),
            ),
        ),
    )

    fun execute(spec: ToolCallSpec, userId: UserId): ToolResult = try {
        when (spec.name.value) {
            "memory_candidate_create" -> handleCreate(spec, userId)
            "memory_candidate_list_pending" -> handleListPending(spec, userId)
            "memory_candidate_approve" -> handleApprove(spec, userId)
            "memory_candidate_reject" -> handleReject(spec, userId)
            "memory_search" -> handleSearch(spec)
            else -> error("Unhandled tool: ${spec.name.value}")
        }
    } catch (e: Exception) {
        ToolResult("ERROR: ${e.message}")
    }

    private fun handleCreate(spec: ToolCallSpec, userId: UserId): ToolResult {
        val args = json.decodeFromString<CreateCandidateArgs>(spec.arguments.value)
        val id = repo.createCandidate(
            userId = userId,
            conversationId = args.conversationId,
            domainName = args.domain,
            memoryType = args.memoryType,
            content = args.content,
            summary = args.summary,
            confidence = args.confidence,
            sourceConversationMessageId = args.sourceConversationMessageId,
            subjectMemberId = args.subjectMemberId,
        )
        return ToolResult("기억 후보가 생성되었습니다. candidate_id=$id status=PENDING")
    }

    private fun handleListPending(spec: ToolCallSpec, userId: UserId): ToolResult {
        val args = json.decodeFromString<Map<String, String>>(spec.arguments.value)
        val candidates = repo.listPending(userId, args.getValue("conversation_id"))
        return if (candidates.isEmpty()) ToolResult("대기 중인 기억 후보가 없습니다.")
        else ToolResult(candidates.joinToString("\n") {
            "candidate_id=${it.id} [${it.domainName}/${it.memoryType.code}] ${it.summary}"
        })
    }

    private fun handleApprove(spec: ToolCallSpec, userId: UserId): ToolResult {
        val args = json.decodeFromString<CandidateIdArgs>(spec.arguments.value)
        val memory = repo.approveCandidate(userId, args.candidateId)
        vectorStore.upsert(
            VectorPoint(
                memoryId = memory.id,
                vector = embeddingService.embed("${memory.summary}\n${memory.content}"),
                payload = mapOf(
                    "familyId" to memory.familyId,
                    "memoryId" to memory.id.toString(),
                    "memoryType" to memory.memoryType.code,
                    "domain" to memory.domainName,
                    "memberId" to (memory.subjectMemberId ?: ""),
                    "createdAt" to memory.createdAt.toString(),
                ),
            ),
        )
        return ToolResult("기억이 저장되었습니다. memory_id=${memory.id} candidate_id=${args.candidateId}")
    }

    private fun handleReject(spec: ToolCallSpec, userId: UserId): ToolResult {
        val args = json.decodeFromString<CandidateIdArgs>(spec.arguments.value)
        repo.rejectCandidate(userId, args.candidateId)
        return ToolResult("기억 후보가 거절되었습니다. candidate_id=${args.candidateId}")
    }

    private fun handleSearch(spec: ToolCallSpec): ToolResult {
        val args = json.decodeFromString<SearchArgs>(spec.arguments.value)
        val filter = MemorySearchFilter(
            memoryType = args.memoryType,
            domain = args.domain?.uppercase(),
            memberId = args.memberId,
            createdAfter = args.createdAfter,
            createdBefore = args.createdBefore,
        )
        val results = vectorStore.search(embeddingService.embed(args.query), filter, args.limit)
        val byId = repo.listMemories(results.map { it.memoryId }).associateBy { it.id }
        val lines = results.mapNotNull { result ->
            byId[result.memoryId]?.let { memory ->
                "memory_id=${memory.id} score=${"%.2f".format(result.score)} [${memory.domainName}/${memory.memoryType.code}] ${memory.summary}"
            }
        }
        return if (lines.isEmpty()) ToolResult("관련 기억을 찾지 못했습니다.") else ToolResult(lines.joinToString("\n"))
    }
}
