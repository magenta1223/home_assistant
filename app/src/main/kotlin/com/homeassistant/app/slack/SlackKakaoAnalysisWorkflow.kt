package com.homeassistant.app.slack

import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisRequest
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisUseCase
import org.slf4j.LoggerFactory

class SlackKakaoAnalysisWorkflow(
    private val slackClient: SlackClient,
    private val topicAnalysis: TopicAnalysisUseCase,
    private val reviewSessions: InMemorySlackTopicReviewSessionStore,
    private val maxFileSizeBytes: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun process(upload: SlackKakaoFileUpload) {
        try {
            slackClient.postMessage(
                channelId = upload.channelId,
                text = "Kakao 대화 분석을 시작합니다.",
                blocks = listOf(section("Kakao 대화 분석을 시작합니다: `${upload.fileName}`")),
                threadTs = upload.messageTs,
            )

            val text = slackClient.downloadText(upload.downloadUrl, maxFileSizeBytes)
            val result = topicAnalysis.analyze(
                TopicAnalysisRequest(
                    sourceType = "kakao",
                    sourceName = upload.fileName,
                    text = text,
                ),
            )

            reviewSessions.put(
                SlackTopicReviewSession(
                    previewId = result.previewId,
                    ownerSlackUserId = upload.slackUserId,
                    status = SlackTopicReviewStatus.AWAITING_CONFIRMATION,
                    channelId = upload.channelId,
                    messageTs = upload.messageTs,
                    topics = result.topics,
                ),
            )

            val message = SlackTopicBlocks.analysisMessage(
                previewId = result.previewId,
                sourceName = result.sourceName,
                topics = result.topics,
            )
            @Suppress("UNCHECKED_CAST")
            slackClient.postMessage(
                channelId = upload.channelId,
                text = message["text"] as String,
                blocks = message["blocks"] as List<Map<String, Any>>,
                threadTs = upload.messageTs,
            )
        } catch (e: Exception) {
            log.warn("Slack Kakao analysis failed for file ${upload.fileName}: ${e.message}")
            slackClient.postEphemeral(
                channelId = upload.channelId,
                userId = upload.slackUserId,
                text = "Kakao 대화 분석에 실패했습니다. 파일 형식과 크기를 확인해주세요.",
            )
        }
    }

    private fun section(text: String): Map<String, Any> =
        mapOf(
            "type" to "section",
            "text" to mapOf("type" to "mrkdwn", "text" to text),
        )
}
