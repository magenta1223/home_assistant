package com.homeassistant.app.slack

import com.slack.api.Slack
import com.slack.api.util.json.GsonFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

class SlackWebApiClient(
    private val botToken: String,
    private val slack: Slack = Slack.getInstance(),
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) : SlackClient {
    private val gson = GsonFactory.createSnakeCase()

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
        return body.toString(StandardCharsets.UTF_8)
    }

    override fun postMessage(
        channelId: String,
        text: String,
        blocks: List<Map<String, Any>>,
        threadTs: String?,
    ) {
        slack.methods(botToken).chatPostMessage { req ->
            req.channel(channelId)
                .text(text)
                .blocksAsString(gson.toJson(blocks))
                .threadTs(threadTs)
        }
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
}
