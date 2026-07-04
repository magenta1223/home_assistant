package com.homeassistant.app.slack

import com.homeassistant.datamodel.topicanalysis.TopicCandidate

object SlackTopicBlocks {
    const val ACTION_OPEN_REVIEW = "topic_analysis_open_review"
    const val ACTION_TOPIC_SELECTION = "topic_analysis_topic_selection"
    const val CALLBACK_CONFIRM_TOPICS = "topic_analysis_confirm_topics"
    const val MAX_MODAL_TOPICS = 100

    fun analysisMessage(
        previewId: String,
        sourceName: String,
        importedRecordCount: Int,
        topics: List<TopicCandidate>,
    ): Map<String, Any> =
        mapOf(
            "text" to "Kakao 대화 분석 후보 ${topics.size}개",
            "blocks" to listOf(
                section(
                    "*Kakao 대화 분석 후보 ${topics.size}개*\n" +
                        "${plain(sourceName, 140)}\n" +
                        "파싱 메시지 ${importedRecordCount}건",
                ),
                divider(),
            ) + topics.take(5).mapIndexed { index, topic -> topicSummary(index, topic) } +
                listOf(
                    actions(
                        listOf(
                            button(
                                text = "검토",
                                actionId = ACTION_OPEN_REVIEW,
                                value = previewId,
                                style = "primary",
                            ),
                        ),
                    ),
                ),
        )

    fun selectionModal(
        previewId: String,
        topics: List<TopicCandidate>,
    ): SlackModalBuildResult {
        if (topics.size > MAX_MODAL_TOPICS) {
            return SlackModalBuildResult.TooManyTopics(topics.size, MAX_MODAL_TOPICS)
        }

        val options = topics.mapIndexed { index, topic ->
            mapOf(
                "text" to plainText("${index + 1}. ${plain(topic.title, 60)}"),
                "description" to plainText(topic.memoryTypes.joinToString(", ").ifBlank { "memory" }),
                "value" to index.toString(),
            )
        }

        return SlackModalBuildResult.Modal(
            mapOf(
                "type" to "modal",
                "callback_id" to CALLBACK_CONFIRM_TOPICS,
                "private_metadata" to previewId,
                "title" to plainText("기억 후보 검토"),
                "submit" to plainText("저장"),
                "close" to plainText("취소"),
                "blocks" to listOf(
                    section("저장할 후보를 선택하세요."),
                    mapOf(
                        "type" to "input",
                        "block_id" to "topic_selection",
                        "label" to plainText("후보"),
                        "element" to mapOf(
                            "type" to "multi_static_select",
                            "action_id" to ACTION_TOPIC_SELECTION,
                            "placeholder" to plainText("저장할 후보 선택"),
                            "options" to options,
                            "initial_options" to options,
                        ),
                    ),
                ),
            ),
        )
    }

    private fun topicSummary(index: Int, topic: TopicCandidate): Map<String, Any> =
        section(
            "*${index + 1}. ${mrkdwn(topic.title, 140)}*\n" +
                "${mrkdwn(topic.summary, 700)}\n" +
                "유형: ${topic.memoryTypes.joinToString(", ")} | 근거: ${topic.evidenceRefs.size}개",
        )

    private fun section(text: String): Map<String, Any> =
        mapOf(
            "type" to "section",
            "text" to mapOf("type" to "mrkdwn", "text" to text),
        )

    private fun actions(elements: List<Map<String, Any>>): Map<String, Any> =
        mapOf("type" to "actions", "elements" to elements)

    private fun button(
        text: String,
        actionId: String,
        value: String,
        style: String,
    ): Map<String, Any> =
        mapOf(
            "type" to "button",
            "text" to plainText(text),
            "action_id" to actionId,
            "value" to value,
            "style" to style,
        )

    private fun divider(): Map<String, Any> = mapOf("type" to "divider")

    private fun plainText(text: String): Map<String, Any> =
        mapOf("type" to "plain_text", "text" to plain(text, 75))

    private fun mrkdwn(text: String, maxLength: Int): String =
        plain(text, maxLength)

    private fun plain(text: String, maxLength: Int): String {
        val compact = text.replace(Regex("\\s+"), " ").trim()
        if (compact.length <= maxLength) return compact
        return compact.take(maxLength - 1).trimEnd() + "…"
    }
}

sealed class SlackModalBuildResult {
    data class Modal(val view: Map<String, Any>) : SlackModalBuildResult()
    data class TooManyTopics(val actualCount: Int, val maxCount: Int) : SlackModalBuildResult()
}
