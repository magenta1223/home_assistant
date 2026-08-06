package com.homeassistant.application.topicanswer.answer

import com.homeassistant.domain.identity.HouseholdAccessDeniedException
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.topicanalysis.Topic
import com.homeassistant.domain.topicanalysis.TopicAnalysisQueryStore

internal class TopicAnswerService(
    private val topicStore: TopicAnalysisQueryStore,
    private val memorySearchIndex: MemorySearchIndex,
    private val accessPolicy: HouseholdAccessPolicy,
) : TopicAnswerUseCase {
    override fun answer(request: TopicAnswerRequest): TopicAnswerResult {
        val userId = request.requester()
        if (!accessPolicy.isAuthorized(userId)) throw HouseholdAccessDeniedException()
        val question = request.question.trim()
        val limit = request.limit.coerceIn(1, 10)
        val hits = memorySearchIndex.search(userId, question, limit)
        val topicsById = topicStore
            .getApprovedTopics(userId, hits.map { it.topicId })
            .associateBy { it.id }
        val matches = hits.mapNotNull { hit ->
            topicsById[hit.topicId]?.toMatch(hit)
        }

        return TopicAnswerResult(
            question = question,
            answer = buildAnswer(matches),
            matches = matches,
        )
    }

    private fun Topic.toMatch(hit: MemorySearchHit): TopicAnswerMatch? {
        val selectedMemory = memories.singleOrNull { it.id == hit.memoryId } ?: return null

        return TopicAnswerMatch(
            topicId = id,
            title = title,
            summary = summary,
            claims = listOf(selectedMemory.content),
            evidenceRefs = selectedMemory.evidenceRefs,
        )
    }

    private fun buildAnswer(matches: List<TopicAnswerMatch>): String {
        if (matches.isEmpty()) return "승인된 기억에서 관련 내용을 찾지 못했습니다."

        val claims = matches.first()
            .claims
            .distinct()
            .take(3)
        return "저장된 기억 기준으로는 " + claims.joinToString(" ")
    }
}

object TopicAnswerFactory {
    fun create(
        topicStore: TopicAnalysisQueryStore,
        memorySearchIndex: MemorySearchIndex,
        accessPolicy: HouseholdAccessPolicy,
    ): TopicAnswerUseCase =
        TopicAnswerService(topicStore, memorySearchIndex, accessPolicy)
}
