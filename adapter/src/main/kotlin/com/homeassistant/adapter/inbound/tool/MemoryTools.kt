package com.homeassistant.adapter.inbound.tool

import com.homeassistant.application.memory.MemoryUseCases
import com.homeassistant.application.memory.approve.ApproveMemoryCandidateInput
import com.homeassistant.application.memory.create.CreateMemoryCandidateInput
import com.homeassistant.application.memory.list.ListPendingMemoryCandidatesInput
import com.homeassistant.application.memory.reject.RejectMemoryCandidateInput
import com.homeassistant.application.memory.search.SearchMemoriesInput
import com.homeassistant.core.identity.UserId
import com.homeassistant.core.memory.MemoryType
import com.homeassistant.core.tools.*
import com.homeassistant.core.utils.JsonSerializer.decodeFromString
import kotlinx.serialization.Serializable

internal class MemoryTools(
    private val useCases: MemoryUseCases,
) {
    /**
     * Arguments for creating a reviewable memory candidate.
     *
     * @property conversationId Source conversation id associated with the candidate.
     * @property domain Domain name assigned to the candidate.
     * @property memoryType Memory category assigned to the candidate.
     * @property content Full candidate memory content.
     * @property summary Short review-facing summary.
     * @property confidence Confidence score assigned by the caller.
     * @property subjectMemberId Optional family member the memory is about.
     * @property sourceConversationMessageId Optional source message id that produced the candidate.
     */
    @Serializable
    private data class CreateCandidateArgs(
        val conversationId: String,
        val domain: String,
        val memoryType: MemoryType,
        val content: String,
        val summary: String,
        val confidence: Double,
        val subjectMemberId: String? = null,
        val sourceConversationMessageId: Int? = null,
    )

    /**
     * Arguments for tools that target a single memory candidate.
     *
     * @property candidateId Candidate id to approve or reject.
     */
    @Serializable private data class CandidateIdArgs(val candidateId: Int)

    /**
     * Arguments for semantic memory search.
     *
     * @property query Natural-language search query.
     * @property memoryType Optional memory category filter.
     * @property domain Optional domain name filter.
     * @property memberId Optional subject member filter.
     * @property createdAfter Optional lower creation timestamp bound in epoch milliseconds.
     * @property createdBefore Optional upper creation timestamp bound in epoch milliseconds.
     * @property limit Maximum number of vector matches to request.
     */
    @Serializable
    private data class SearchArgs(
        val query: String,
        val memoryType: MemoryType? = null,
        val domain: String? = null,
        val memberId: String? = null,
        val createdAfter: Long? = null,
        val createdBefore: Long? = null,
        val limit: Int = 5,
    )

    val tools: List<Tool> = listOf(
        Tool(
            "memory_candidate_create",
            "사용자 승인이 필요한 장기 기억 후보를 생성합니다",
            ToolSchema(
                properties = mapOf(
                    "conversationId" to PropertySchema("string", "대화 ID"),
                    "domain" to PropertySchema("string", "생활 영역 이름"),
                    "memoryType" to PropertySchema(
                        "string",
                        "기억 타입. PROFILE, PREFERENCE, RELATIONSHIP, STATE, LOCATION, REFERENCE, DECISION, CONSTRAINT, CONVERSATION, EVENT, TRANSACTION, APPOINTMENT, CHANGE, MILESTONE, OBSERVATION, ROUTINE, CHECKLIST, INSTRUCTION, RULE, RECIPE, TROUBLESHOOTING, TEMPLATE 중 하나",
                    ),
                    "content" to PropertySchema("string", "원문에 가까운 기억 내용"),
                    "summary" to PropertySchema("string", "짧은 요약"),
                    "confidence" to PropertySchema("number", "0.0-1.0 신뢰도"),
                    "subjectMemberId" to PropertySchema("string", "대상 구성원 ID"),
                ),
                required = listOf("conversationId", "domain", "memoryType", "content", "summary", "confidence"),
            ),
        ),
        Tool(
            "memory_candidate_list_pending",
            "현재 대화와 사용자 기준 pending 기억 후보를 조회합니다",
            ToolSchema(
                properties = mapOf("conversationId" to PropertySchema("string", "대화 ID")),
                required = listOf("conversationId"),
            ),
        ),
        Tool(
            "memory_candidate_approve",
            "기억 후보를 승인하고 장기 기억으로 저장합니다",
            ToolSchema(properties = mapOf("candidateId" to PropertySchema("integer", "후보 ID")), required = listOf("candidateId")),
        ),
        Tool(
            "memory_candidate_reject",
            "기억 후보를 거절합니다",
            ToolSchema(properties = mapOf("candidateId" to PropertySchema("integer", "후보 ID")), required = listOf("candidateId")),
        ),
        Tool(
            "memory_search",
            "장기 기억을 의미 검색합니다",
            ToolSchema(
                properties = mapOf(
                    "query" to PropertySchema("string", "검색 질의"),
                    "memoryType" to PropertySchema("string", "기억 타입 필터"),
                    "domain" to PropertySchema("string", "생활 영역 필터"),
                    "memberId" to PropertySchema("string", "구성원 필터"),
                    "createdAfter" to PropertySchema("integer", "생성 시각 하한(epoch milliseconds)"),
                    "createdBefore" to PropertySchema("integer", "생성 시각 상한(epoch milliseconds)"),
                    "limit" to PropertySchema("integer", "최대 결과 수"),
                ),
                required = listOf("query"),
            ),
        ),
    )

    fun execute(spec: ToolCallSpec, userId: UserId): ToolResult = try {
        when (spec.name) {
            "memory_candidate_create" -> handleCreate(spec, userId)
            "memory_candidate_list_pending" -> handleListPending(spec, userId)
            "memory_candidate_approve" -> handleApprove(spec, userId)
            "memory_candidate_reject" -> handleReject(spec, userId)
            "memory_search" -> handleSearch(spec, userId)
            else -> error("Unhandled tool: ${spec.name}")
        }
    } catch (e: Exception) {
        ToolResult("ERROR: ${e.message}")
    }

    private fun handleCreate(spec: ToolCallSpec, userId: UserId): ToolResult {
        val args: CreateCandidateArgs = spec.arguments.decodeFromString()
        val result = useCases.createCandidate.execute(
            CreateMemoryCandidateInput(
                userId = userId,
                conversationId = args.conversationId,
                domainName = args.domain,
                memoryType = args.memoryType,
                content = args.content,
                summary = args.summary,
                confidence = args.confidence,
                sourceConversationMessageId = args.sourceConversationMessageId,
                subjectMemberId = args.subjectMemberId,
            ),
        )
        return ToolResult("기억 후보가 생성되었습니다. candidate_id=${result.candidateId} status=PENDING")
    }

    private fun handleListPending(spec: ToolCallSpec, userId: UserId): ToolResult {
        val args: Map<String, String> = spec.arguments.decodeFromString()
        val candidates = useCases.listPendingCandidates.execute(
            ListPendingMemoryCandidatesInput(userId, args.getValue("conversationId")),
        ).candidates
        return if (candidates.isEmpty()) ToolResult("대기 중인 기억 후보가 없습니다.")
        else ToolResult(candidates.joinToString("\n") {
            "candidate_id=${it.id} [${it.domainName}/${it.memoryType.code}] ${it.summary}"
        })
    }

    private fun handleApprove(spec: ToolCallSpec, userId: UserId): ToolResult {
        val args: CandidateIdArgs = spec.arguments.decodeFromString()
        val result = useCases.approveCandidate.execute(
            ApproveMemoryCandidateInput(userId, args.candidateId),
        )
        val indexStatus = if (result.indexed) "INDEXED" else "INDEX_PENDING"
        return ToolResult(
            "기억이 저장되었습니다. memory_id=${result.memory.id} candidate_id=${args.candidateId} index_status=$indexStatus",
        )
    }

    private fun handleReject(spec: ToolCallSpec, userId: UserId): ToolResult {
        val args: CandidateIdArgs = spec.arguments.decodeFromString()
        useCases.rejectCandidate.execute(RejectMemoryCandidateInput(userId, args.candidateId))
        return ToolResult("기억 후보가 거절되었습니다. candidate_id=${args.candidateId}")
    }

    private fun handleSearch(spec: ToolCallSpec, userId: UserId): ToolResult {
        val args: SearchArgs = spec.arguments.decodeFromString()
        val results = useCases.searchMemories.execute(
            SearchMemoriesInput(
                userId = userId,
                query = args.query,
                memoryType = args.memoryType,
                domain = args.domain?.uppercase(),
                memberId = args.memberId,
                createdAfter = args.createdAfter,
                createdBefore = args.createdBefore,
                limit = args.limit,
            ),
        )
        val lines = results.matches.map { match ->
            with(match.memory) {
                "memory_id=$id score=${"%.2f".format(match.score)} [$domainName/${memoryType.code}] $summary"
            }
        }
        return if (lines.isEmpty()) ToolResult("관련 기억을 찾지 못했습니다.") else ToolResult(lines.joinToString("\n"))
    }

}
