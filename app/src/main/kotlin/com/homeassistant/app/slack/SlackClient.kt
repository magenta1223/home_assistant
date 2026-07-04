package com.homeassistant.app.slack

interface SlackClient {
    fun fileDownloadUrl(fileId: String): String?

    fun downloadText(url: String, maxBytes: Long): String

    fun postMessage(
        channelId: String,
        text: String,
        blocks: List<Map<String, Any>>,
        threadTs: String? = null,
    )

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
