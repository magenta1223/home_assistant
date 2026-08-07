package com.homeassistant.adapter.inbound.slack

import com.homeassistant.domain.topicanalysis.TopicProposal

object SlackTopicBlocks {
    fun analysisMessage(
        sourceName: String,
        importedRecordCount: Int,
        topics: List<TopicProposal>,
    ): Map<String, Any> =
        mapOf(
            "text" to "Kakao 대화 분석 및 저장 완료: ${topics.size}개",
            "blocks" to listOf(
                section(
                    "*Kakao 대화 분석 및 저장 완료: ${topics.size}개*\n" +
                        "${plain(sourceName, 140)}\n" +
                        "파싱 메시지 ${importedRecordCount}건",
                ),
                divider(),
            ) + topics.take(5).mapIndexed { index, topic -> topicSummary(index, topic) },
        )

    private fun topicSummary(index: Int, topic: TopicProposal): Map<String, Any> =
        section(
            "*${index + 1}. ${mrkdwn(topic.title, 140)}*\n" +
                "${mrkdwn(topic.summary, 700)}\n" +
                "유형: ${topic.memoryTypes.joinToString(", ")} | 근거: ${topic.evidenceIds.size}개",
        )

    private fun section(text: String): Map<String, Any> =
        mapOf(
            "type" to "section",
            "text" to mapOf("type" to "mrkdwn", "text" to text),
        )

    private fun divider(): Map<String, Any> = mapOf("type" to "divider")

    private fun mrkdwn(text: String, maxLength: Int): String =
        plain(text, maxLength)

    private fun plain(text: String, maxLength: Int): String {
        val compact = text.replace(Regex("\\s+"), " ").trim()
        if (compact.length <= maxLength) return compact
        return compact.take(maxLength - 1).trimEnd() + "…"
    }
}
