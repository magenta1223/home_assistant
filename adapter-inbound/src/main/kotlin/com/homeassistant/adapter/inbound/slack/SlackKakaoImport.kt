package com.homeassistant.adapter.inbound.slack

import com.homeassistant.adapter.inbound.kakao.KakaoExportParser
import com.homeassistant.application.slackconversation.SlackPrincipal
import com.homeassistant.application.topicanalysis.analyze.DuplicateSourceRecordsException
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisRequest
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysis
import com.slack.api.model.event.MessageFileShareEvent
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import org.slf4j.LoggerFactory

data class SlackKakaoFileUpload(
    val principal: SlackPrincipal,
    val channelId: String,
    val messageTs: String,
    val fileId: String?,
    val fileName: String,
    val downloadUrl: String?,
)

object SlackFileIngress {
    fun from(
        event: MessageFileShareEvent,
        principal: SlackPrincipal,
        maxFileSizeBytes: Long,
    ): List<SlackKakaoFileUpload> {
        if (event.channelType != "im") return emptyList()
        val userId = event.user?.takeIf { it.isNotBlank() } ?: return emptyList()
        if (userId != principal.slackUserId) return emptyList()
        val channelId = event.channel?.takeIf { it.isNotBlank() } ?: return emptyList()
        val messageTs = event.threadTs ?: event.ts ?: return emptyList()

        return event.files.orEmpty().mapNotNull { file ->
            val fileName = file.name ?: file.title ?: return@mapNotNull null
            val size = file.size?.toLong() ?: return@mapNotNull null
            val url = file.urlPrivateDownload ?: file.urlPrivate ?: return@mapNotNull null
            if (!fileName.endsWith(".txt", ignoreCase = true)) return@mapNotNull null
            if (size > maxFileSizeBytes) return@mapNotNull null

            SlackKakaoFileUpload(
                principal = principal,
                channelId = channelId,
                messageTs = messageTs,
                fileId = file.id,
                fileName = fileName,
                downloadUrl = url,
            )
        }
    }
}

internal class SlackKakaoAnalysisWorkflow(
    private val slackClient: SlackClient,
    private val topicAnalysis: TopicAnalysis,
    private val reviewContexts: SlackReviewContextStore,
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

internal object SlackTextDecoder {
    fun decode(body: ByteArray): String =
        when {
            body.startsWith(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) ->
                Charsets.UTF_16LE.decode(ByteBuffer.wrap(body, 2, body.size - 2)).toString()
            body.startsWith(byteArrayOf(0xFE.toByte(), 0xFF.toByte())) ->
                Charsets.UTF_16BE.decode(ByteBuffer.wrap(body, 2, body.size - 2)).toString()
            body.looksLikeUtf16Le() -> Charsets.UTF_16LE.decode(ByteBuffer.wrap(body)).toString()
            body.looksLikeUtf16Be() -> Charsets.UTF_16BE.decode(ByteBuffer.wrap(body)).toString()
            else -> decodeUtf8OrMs949(body)
        }

    private fun decodeUtf8OrMs949(body: ByteArray): String =
        try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString()
        } catch (_: CharacterCodingException) {
            Charset.forName("MS949").decode(ByteBuffer.wrap(body)).toString()
        }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun ByteArray.looksLikeUtf16Le(): Boolean =
        size >= 8 && oddNullByteRatio() > 0.3 && evenNullByteRatio() < 0.05

    private fun ByteArray.looksLikeUtf16Be(): Boolean =
        size >= 8 && evenNullByteRatio() > 0.3 && oddNullByteRatio() < 0.05

    private fun ByteArray.evenNullByteRatio(): Double = nullByteRatio(startIndex = 0)
    private fun ByteArray.oddNullByteRatio(): Double = nullByteRatio(startIndex = 1)

    private fun ByteArray.nullByteRatio(startIndex: Int): Double {
        val sampled = indices.count { it % 2 == startIndex }
        if (sampled == 0) return 0.0
        val nulls = indices.count { it % 2 == startIndex && this[it] == 0.toByte() }
        return nulls.toDouble() / sampled
    }
}
