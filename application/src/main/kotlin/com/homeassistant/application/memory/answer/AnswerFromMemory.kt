package com.homeassistant.application.memory.answer

import com.homeassistant.domain.identity.HouseholdAccessDeniedException
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.topicanalysis.Topic
import com.homeassistant.domain.topicanalysis.TopicAnalysisQueryStore

class AnswerFromMemory(
    private val topicStore: TopicAnalysisQueryStore,
    private val memorySearchIndex: MemorySearchIndex,
    private val accessPolicy: HouseholdAccessPolicy,
) : MemoryAnswerUseCase {
    override fun answer(request: MemoryAnswerRequest): MemoryAnswerResult {
        val userId = request.requester()
        if (!accessPolicy.isAuthorized(userId)) throw HouseholdAccessDeniedException()
        val question = request.question.trim()
        val hits = memorySearchIndex.search(userId, question, request.limit.coerceIn(1, 10))
        val topicsById = topicStore
            .getApprovedTopics(userId, hits.mapNotNull { it.topicId })
            .associateBy { it.id }
        val matches = hits.mapNotNull { hit -> topicsById[hit.topicId]?.toMatch(hit) }

        return MemoryAnswerResult(
            question = question,
            answer = buildAnswer(matches),
            matches = matches,
        )
    }

    private fun Topic.toMatch(hit: MemorySearchHit): MemoryAnswerMatch? {
        val memory = memories.singleOrNull { it.id == hit.memoryId } ?: return null
        return MemoryAnswerMatch(
            memoryId = memory.id,
            topicId = id,
            topicTitle = title,
            topicSummary = summary,
            content = memory.content,
            evidenceRefs = memory.evidenceRefs,
        )
    }

    private fun buildAnswer(matches: List<MemoryAnswerMatch>): String =
        if (matches.isEmpty()) {
            "승인된 기억에서 관련 내용을 찾지 못했습니다."
        } else {
            "저장된 기억 기준으로는 ${matches.first().content}"
        }
}
