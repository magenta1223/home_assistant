package com.homeassistant.adapter.inbound.slack

import com.homeassistant.common.json.JsonSerializer
import com.homeassistant.common.json.JsonSerializer.toJsonElement
import com.slack.api.Slack
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.serialization.encodeToString

interface SlackClient {
    fun fileDownloadUrl(fileId: String): String?
    fun downloadText(url: String, maxBytes: Long): String

    fun postMessage(
        channelId: String,
        text: String,
        blocks: List<Map<String, Any>>,
        threadTs: String? = null,
    ): SlackMessageDelivery

    fun postEphemeral(
        channelId: String,
        userId: String,
        text: String,
    )

    fun openModal(
        triggerId: String,
        view: Map<String, Any>,
    )
}

data class SlackMessageDelivery(val responseTs: String)

class SlackMessageDeliveryException(
    val category: String,
) : RuntimeException("Slack message delivery failed: $category")

internal class SlackApiClient(
    private val botToken: String,
    private val slack: Slack = Slack.getInstance(),
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val messagePoster: SlackMessagePoster? = null,
) : SlackClient {
    override fun fileDownloadUrl(fileId: String): String? {
        val response = slack.methods(botToken).filesInfo { req -> req.file(fileId) }
        check(response.isOk) {
            "Slack files.info failed for $fileId: ${response.error}"
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
        check(response.statusCode() in 200..299) {
            "Slack file download failed with status ${response.statusCode()}"
        }
        val body = response.body()
        check(body.isNotEmpty()) { "Slack file download returned an empty body" }
        check(body.size <= maxBytes) { "Slack file exceeds max size" }
        return SlackTextDecoder.decode(body)
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
                .viewAsString(JsonSerializer.json.encodeToString(view.toJsonElement()))
        }
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
