package com.homeassistant.adapter.inbound.slack

import com.homeassistant.adapter.inbound.kakao.KakaoExportParser
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisUseCase
import com.homeassistant.application.topicanalysis.analyze.DuplicateSourceRecordsException
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisRequest
import org.slf4j.LoggerFactory

interface SlackKakaoWorkflow {
    suspend fun process(upload: SlackKakaoFileUpload)
}

internal class SlackKakaoAnalysisWorkflow(
    private val slackClient: SlackKakaoClient,
    private val topicAnalysis: TopicAnalysisUseCase,
    private val reviewContexts: SlackReviewContextStore,
    private val maxFileSizeBytes: Long,
) : SlackKakaoWorkflow {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun process(upload: SlackKakaoFileUpload) {
        try {
            slackClient.postMessage(
                channelId = upload.channelId,
                text = "Kakao 대화 분석을 시작합니다.",
                blocks = listOf(section("Kakao 대화 분석을 시작합니다: `${upload.fileName}`")),
                threadTs = upload.messageTs,
            )

            val downloadUrl = upload.fileId
                ?.let { slackClient.fileDownloadUrl(it) }
                ?: upload.downloadUrl
                ?: error("Slack file download URL is missing")
            val text = slackClient.downloadText(downloadUrl, maxFileSizeBytes)
            val result = topicAnalysis.execute(
                TopicAnalysisRequest(
                    userId = upload.principal.userId.value,
                    source = KakaoExportParser.parse(upload.fileName, text),
                ),
            )
            if (result.importedRecordCount == 0) {
                log.warn("Slack Kakao analysis parsed zero messages for file ${upload.fileName}")
                slackClient.postEphemeral(
                    channelId = upload.channelId,
                    userId = upload.principal.slackUserId,
                    text = "Kakao 대화 메시지를 읽지 못했습니다. 대화 내보내기 파일이 텍스트 형식인지 확인해주세요.\n" +
                        "읽은 첫 줄: `${text.firstNonBlankLinePreview()}`",
                )
                return
            }

            reviewContexts.save(
                SlackReviewContext(
                    reviewId = result.previewId,
                    status = SlackReviewStatus.AWAITING_CONFIRMATION,
                    channelId = upload.channelId,
                ),
            )

            val message = SlackTopicBlocks.analysisMessage(
                reviewId = result.previewId,
                sourceName = result.sourceName,
                importedRecordCount = result.importedRecordCount,
                topics = result.topics,
            )
            @Suppress("UNCHECKED_CAST")
            slackClient.postMessage(
                channelId = upload.channelId,
                text = message["text"] as String,
                blocks = message["blocks"] as List<Map<String, Any>>,
                threadTs = upload.messageTs,
            )
        } catch (e: DuplicateSourceRecordsException) {
            slackClient.postEphemeral(
                channelId = upload.channelId,
                userId = upload.principal.slackUserId,
                text = "이미 분석된 Kakao 대화입니다. 새 메시지가 포함된 파일을 올려주세요.",
            )
        } catch (e: Exception) {
            log.warn("Slack Kakao analysis failed for file ${upload.fileName}: ${e.message}")
            slackClient.postEphemeral(
                channelId = upload.channelId,
                userId = upload.principal.slackUserId,
                text = "Kakao 대화 분석에 실패했습니다. 파일 형식, 크기, Slack 권한을 확인해주세요.\n" +
                    "원인: `${e.safeMessage()}`",
            )
        }
    }

    private fun section(text: String): Map<String, Any> =
        mapOf(
            "type" to "section",
            "text" to mapOf("type" to "mrkdwn", "text" to text),
        )

    private fun String.firstNonBlankLinePreview(): String =
        lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.replace("`", "'")
            ?.replace(Regex("\\s+"), " ")
            ?.take(120)
            ?: "(empty)"

    private fun Exception.safeMessage(): String =
        (message ?: javaClass.simpleName)
            .replace("`", "'")
            .replace(Regex("\\s+"), " ")
            .take(160)
}
