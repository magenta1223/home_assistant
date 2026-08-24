package com.homeassistant.adapter.inbound.slack

import com.homeassistant.common.json.JsonSerializer
import com.homeassistant.common.json.JsonSerializer.toJsonElement
import com.slack.api.Slack
import kotlinx.serialization.encodeToString
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Provides the Slack operations used by inbound workflows. */
interface SlackClient {
    /** Posts a message, optionally as a reply in a Slack thread. */
    fun postMessage(
        channelId: String,
        text: String,
        blocks: List<Map<String, Any>>,
        threadTs: String? = null,
    ): SlackMessageDelivery

    /** Opens a modal in response to a Slack interaction trigger. */
    fun openModal(triggerId: String, view: Map<String, Any>)

    /** Sends an ephemeral follow-up through a Slack interaction response URL. */
    fun respond(responseUrl: String, text: String)

    /** Downloads one Slack-hosted UTF-8 text file without persisting it locally. */
    fun readTextFile(fileId: String, maxBytes: Int): SlackTextFile
}

data class SlackMessageDelivery(val responseTs: String)

data class SlackTextFile(
    val name: String,
    val text: String,
)

class SlackMessageDeliveryException(
    val category: String,
) : RuntimeException("Slack message delivery failed: $category")

class SlackFileReadException(
    val category: String,
) : RuntimeException("Slack file read failed: $category")

internal class SlackApiClient(
    private val botToken: String,
    private val slack: Slack = Slack.getInstance(),
    private val messagePoster: SlackMessagePoster? = null,
) : SlackClient {

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
                    builder.blocksAsString(JsonSerializer.json.encodeToString(blocks.toJsonElement()))
                }
                builder
            }.let { SlackPostMessageResponse(it.isOk, it.ts, it.error) }
        if (!response.ok) {
            throw SlackMessageDeliveryException(response.error ?: "API_REJECTED")
        }
        val responseTs = response.ts?.takeIf(String::isNotBlank)
            ?: throw SlackMessageDeliveryException("MISSING_RESPONSE_TS")
        return SlackMessageDelivery(responseTs)
    }

    override fun openModal(triggerId: String, view: Map<String, Any>) {
        val response = slack.methods(botToken).viewsOpen { req ->
            req.triggerId(triggerId)
                .viewAsString(JsonSerializer.json.encodeToString(view.toJsonElement()))
        }
        if (!response.isOk) {
            throw SlackMessageDeliveryException(response.error ?: "MODAL_API_REJECTED")
        }
    }

    override fun respond(responseUrl: String, text: String) {
        val payload = mapOf(
            "response_type" to "ephemeral",
            "text" to text,
        )
        val response = slack.send(
            responseUrl,
            JsonSerializer.json.encodeToString(payload.toJsonElement()),
        )
        if (response.code?.let { it in 200..299 } != true) {
            throw SlackMessageDeliveryException("RESPONSE_URL_REJECTED_${response.code}")
        }
    }

    override fun readTextFile(fileId: String, maxBytes: Int): SlackTextFile {
        require(fileId.isNotBlank()) { "fileId is required" }
        require(maxBytes > 0) { "maxBytes must be positive" }
        val info = slack.methods(botToken).filesInfo { request -> request.file(fileId) }
        if (!info.isOk) throw SlackFileReadException(info.error ?: "FILE_INFO_REJECTED")
        val file = info.file ?: throw SlackFileReadException("MISSING_FILE_INFO")
        val name = file.name?.takeIf(String::isNotBlank)
            ?: throw SlackFileReadException("MISSING_FILE_NAME")
        if (!name.endsWith(".txt", ignoreCase = true)) {
            throw SlackFileReadException("UNSUPPORTED_FILE_TYPE")
        }
        if (file.size?.let { it > maxBytes } == true) {
            throw SlackFileReadException("FILE_TOO_LARGE")
        }
        val downloadUrl = file.urlPrivateDownload?.takeIf(String::isNotBlank)
            ?: file.urlPrivate?.takeIf(String::isNotBlank)
            ?: throw SlackFileReadException("MISSING_DOWNLOAD_URL")
        val uri = runCatching { URI(downloadUrl) }
            .getOrElse { throw SlackFileReadException("INVALID_DOWNLOAD_URL") }
        if (uri.scheme != "https" || !isSlackHost(uri.host)) {
            throw SlackFileReadException("UNTRUSTED_DOWNLOAD_URL")
        }

        val bytes = slack.httpClient.get(downloadUrl, emptyMap(), botToken).use { response ->
            if (!response.isSuccessful) throw SlackFileReadException("DOWNLOAD_REJECTED_${response.code}")
            val body = response.body ?: throw SlackFileReadException("MISSING_FILE_BODY")
            if (body.contentLength() > maxBytes) throw SlackFileReadException("FILE_TOO_LARGE")
            body.byteStream().use { input -> input.readNBytes(maxBytes + 1) }
        }
        if (bytes.size > maxBytes) throw SlackFileReadException("FILE_TOO_LARGE")
        val text = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
                .removePrefix("\uFEFF")
        }.getOrElse { throw SlackFileReadException("INVALID_UTF8") }
        if (text.isBlank()) throw SlackFileReadException("EMPTY_FILE")
        return SlackTextFile(name, text)
    }

    private fun isSlackHost(host: String?): Boolean =
        host?.lowercase()?.let { it == "slack.com" || it.endsWith(".slack.com") } == true

}

/** Isolates the Slack message-posting operation for the client adapter. */
internal fun interface SlackMessagePoster {
    /** Posts one message through the underlying Slack API call. */
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
