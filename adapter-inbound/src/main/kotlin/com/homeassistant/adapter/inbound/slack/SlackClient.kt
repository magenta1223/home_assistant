package com.homeassistant.adapter.inbound.slack

interface SlackFileClient {
    fun fileDownloadUrl(fileId: String): String?
    fun downloadText(url: String, maxBytes: Long): String
}

interface SlackMessageClient {
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
}

interface SlackModalClient {
    fun openModal(
        triggerId: String,
        view: Map<String, Any>,
    )
}

interface SlackKakaoClient : SlackFileClient, SlackMessageClient
interface SlackInteractionClient : SlackMessageClient, SlackModalClient
interface SlackClient : SlackKakaoClient, SlackInteractionClient

data class SlackMessageDelivery(val responseTs: String)

class SlackMessageDeliveryException(
    val category: String,
) : RuntimeException("Slack message delivery failed: $category")
