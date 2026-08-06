package com.homeassistant.adapter.inbound.slack

import com.slack.api.Slack
import com.slack.api.util.json.GsonFactory

internal object SlackApiComponentFactory {
    fun file(botToken: String, slack: Slack = Slack.getInstance()): SlackFileClient =
        SlackFileWebApiClient(botToken, slack, SlackTextDownloaderFactory.http(botToken))

    fun message(
        botToken: String,
        slack: Slack = Slack.getInstance(),
        messagePoster: SlackMessagePoster? = null,
    ): SlackMessageClient = SlackMessageWebApiClient(botToken, slack, messagePoster)

    fun modal(botToken: String, slack: Slack = Slack.getInstance()): SlackModalClient =
        SlackModalWebApiClient(botToken, slack)
}

private class SlackFileWebApiClient(
    private val botToken: String,
    private val slack: Slack,
    private val downloader: SlackTextDownloader,
) : SlackFileClient {
    override fun fileDownloadUrl(fileId: String): String? {
        val response = slack.methods(botToken).filesInfo { req -> req.file(fileId) }
        check(response.isOk) {
            "Slack files.info failed for $fileId: ${response.error}"
        }
        return response.file?.urlPrivateDownload ?: response.file?.urlPrivate
    }

    override fun downloadText(url: String, maxBytes: Long): String =
        downloader.download(url, maxBytes)
}

private class SlackMessageWebApiClient(
    private val botToken: String,
    private val slack: Slack,
    private val messagePoster: SlackMessagePoster?,
) : SlackMessageClient {
    private val gson = GsonFactory.createSnakeCase()

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
                if (blocks.isNotEmpty()) builder.blocksAsString(gson.toJson(blocks))
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
}

private class SlackModalWebApiClient(
    private val botToken: String,
    private val slack: Slack,
) : SlackModalClient {
    private val gson = GsonFactory.createSnakeCase()

    override fun openModal(triggerId: String, view: Map<String, Any>) {
        slack.methods(botToken).viewsOpen { req ->
            req.triggerId(triggerId)
                .viewAsString(gson.toJson(view))
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
