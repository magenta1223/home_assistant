package com.homeassistant.domain.topicanswer

import com.homeassistant.datamodel.topicanalysis.Topic
import com.homeassistant.domain.topicanalysis.TopicAnalysisStore

class TopicAnswerService(
    private val topicStore: TopicAnalysisStore,
) : TopicAnswerUseCase {
    override fun answer(request: TopicAnswerRequest): TopicAnswerResult {
        val question = request.question.trim()
        val limit = request.limit.coerceIn(1, 10)
        val queryTokens = tokenize(question)
        val topics = topicStore.searchApprovedTopics(question, limit)
        val matches = topics.map { it.toMatch(queryTokens) }

        return TopicAnswerResult(
            question = question,
            answer = buildAnswer(matches),
            matches = matches,
        )
    }

    private fun Topic.toMatch(queryTokens: Set<String>): TopicAnswerMatch {
        val scoredClaims = claims
            .map { claim -> claim to scoreClaim(claim.text, queryTokens) }
            .filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }
            .map { (claim, _) -> claim }
        val selectedClaims = (scoredClaims.ifEmpty { claims }).take(3)

        return TopicAnswerMatch(
            topicId = id,
            title = title,
            summary = summary,
            claims = selectedClaims.map { it.text }.distinct(),
            evidenceRefs = selectedClaims.flatMap { it.evidenceRefs }.distinct(),
        )
    }

    private fun tokenize(text: String): Set<String> =
        Regex("[\\p{L}\\p{N}]+")
            .findAll(text.lowercase())
            .map { it.value }
            .filter { it.length >= 2 }
            .toSet()

    private fun scoreClaim(claim: String, queryTokens: Set<String>): Int {
        val normalized = claim.lowercase()
        return queryTokens.count { normalized.contains(it) }
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
