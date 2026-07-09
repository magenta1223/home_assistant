package com.homeassistant.domain.topicanswer

import com.homeassistant.datamodel.topicanalysis.Topic
import com.homeassistant.domain.topicanalysis.TopicAnalysisStore

class TopicAnswerService(
    private val topicStore: TopicAnalysisStore,
) : TopicAnswerUseCase {
    override fun answer(request: TopicAnswerRequest): TopicAnswerResult {
        val question = request.question.trim()
        val limit = request.limit.coerceIn(1, 10)
        val topics = topicStore.searchApprovedTopics(question, limit)
        val matches = topics.map { it.toMatch() }

        return TopicAnswerResult(
            question = question,
            answer = buildAnswer(matches),
            matches = matches,
        )
    }

    private fun Topic.toMatch(): TopicAnswerMatch =
        TopicAnswerMatch(
            topicId = id,
            title = title,
            summary = summary,
            claims = claims.map { it.text }.distinct(),
            evidenceRefs = evidenceRefs.distinct(),
        )

    private fun buildAnswer(matches: List<TopicAnswerMatch>): String {
        if (matches.isEmpty()) return "승인된 기억에서 관련 내용을 찾지 못했습니다."

        val claims = matches
            .flatMap { it.claims }
            .distinct()
            .take(3)
        return "저장된 기억 기준으로는 " + claims.joinToString(" ")
    }
}
