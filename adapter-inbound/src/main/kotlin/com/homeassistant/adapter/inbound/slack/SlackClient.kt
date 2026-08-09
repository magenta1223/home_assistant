package com.homeassistant.adapter.inbound.slack

import com.homeassistant.common.json.JsonSerializer
import com.homeassistant.common.json.JsonSerializer.toJsonElement
import com.slack.api.Slack
import kotlinx.serialization.encodeToString

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

}

data class SlackMessageDelivery(val responseTs: String)

class SlackMessageDeliveryException(
    val category: String,
) : RuntimeException("Slack message delivery failed: $category")

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
