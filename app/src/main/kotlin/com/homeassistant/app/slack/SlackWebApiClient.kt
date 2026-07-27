package com.homeassistant.app.slack

import com.slack.api.Slack
import com.slack.api.util.json.GsonFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

internal class SlackWebApiClient(
    private val botToken: String,
    private val slack: Slack = Slack.getInstance(),
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val messagePoster: SlackMessagePoster? = null,
) : SlackClient {
    private val gson = GsonFactory.createSnakeCase()

    override fun fileDownloadUrl(fileId: String): String? {
        val response = slack.methods(botToken).filesInfo { req -> req.file(fileId) }
        if (!response.isOk) {
            error("Slack files.info failed for $fileId: ${response.error}")
        }
        return response.file?.urlPrivateDownload ?: response.file?.urlPrivate
    }

    override fun downloadText(url: String, maxBytes: Long): String {
        val response = httpClient.send(
            HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer $botToken")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        if (response.statusCode() !in 200..299) {
            error("Slack file download failed with status ${response.statusCode()}")
        }
        val body = response.body()
        if (body.isEmpty()) error("Slack file download returned an empty body")
        if (body.size > maxBytes) error("Slack file exceeds max size")
        return decodeText(body)
    }

    override fun postMessage(
        channelId: String,
        text: String,
        blocks: List<Map<String, Any>>,
        threadTs: String?,
    ): SlackMessageDelivery {
        val response = messagePoster?.post(channelId, text, blocks, threadTs)
            ?: slack.methods(botToken).chatPostMessage { req ->
                val builder = req.channel(channelId)
                    .text(text)
                    .threadTs(threadTs)
                if (blocks.isNotEmpty()) {
                    builder.blocksAsString(gson.toJson(blocks))
                }
                builder
            }.let { SlackPostMessageResponse(it.isOk, it.ts, it.error) }
        if (!response.ok) {
            throw SlackMessageDeliveryException(response.error ?: "API_REJECTED")
        }
        val responseTs = response.ts?.takeIf { it.isNotBlank() }
            ?: throw SlackMessageDeliveryException("MISSING_RESPONSE_TS")
        return SlackMessageDelivery(responseTs)
    }

    override fun postEphemeral(channelId: String, userId: String, text: String) {
        slack.methods(botToken).chatPostEphemeral { req ->
            req.channel(channelId)
                .user(userId)
                .text(text)
        }
    }

    override fun openModal(triggerId: String, view: Map<String, Any>) {
        slack.methods(botToken).viewsOpen { req ->
            req.triggerId(triggerId)
                .viewAsString(gson.toJson(view))
        }
    }

    private fun decodeText(body: ByteArray): String =
        when {
            body.startsWith(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) ->
                Charsets.UTF_16LE.decode(ByteBuffer.wrap(body, 2, body.size - 2)).toString()
            body.startsWith(byteArrayOf(0xFE.toByte(), 0xFF.toByte())) ->
                Charsets.UTF_16BE.decode(ByteBuffer.wrap(body, 2, body.size - 2)).toString()
            body.looksLikeUtf16Le() ->
                Charsets.UTF_16LE.decode(ByteBuffer.wrap(body)).toString()
            body.looksLikeUtf16Be() ->
                Charsets.UTF_16BE.decode(ByteBuffer.wrap(body)).toString()
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

    private fun ByteArray.evenNullByteRatio(): Double =
        nullByteRatio(startIndex = 0)

    private fun ByteArray.oddNullByteRatio(): Double =
        nullByteRatio(startIndex = 1)

    private fun ByteArray.nullByteRatio(startIndex: Int): Double {
        val sampled = indices.count { it % 2 == startIndex }
        if (sampled == 0) return 0.0
        val nulls = indices.count { it % 2 == startIndex && this[it] == 0.toByte() }
        return nulls.toDouble() / sampled
    }
}

internal fun interface SlackMessagePoster {
    fun post(
        channelId: String,
        text: String,
        blocks: List<Map<String, Any>>,
        threadTs: String?,
    ): SlackPostMessageResponse
}

internal data class SlackPostMessageResponse(
    val ok: Boolean,
    val ts: String?,
    val error: String?,
)
