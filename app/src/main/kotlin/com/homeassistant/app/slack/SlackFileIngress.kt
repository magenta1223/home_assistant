package com.homeassistant.app.slack

import com.slack.api.model.event.MessageFileShareEvent

data class SlackKakaoFileUpload(
    val slackUserId: String,
    val channelId: String,
    val messageTs: String,
    val fileId: String?,
    val fileName: String,
    val downloadUrl: String?,
)

object SlackFileIngress {
    fun from(event: MessageFileShareEvent, maxFileSizeBytes: Long): List<SlackKakaoFileUpload> {
        if (event.channelType != "im") return emptyList()
        val userId = event.user?.takeIf { it.isNotBlank() } ?: return emptyList()
        val channelId = event.channel?.takeIf { it.isNotBlank() } ?: return emptyList()
        val messageTs = event.threadTs ?: event.ts ?: return emptyList()

        return event.files.orEmpty().mapNotNull { file ->
            val fileName = file.name ?: file.title ?: return@mapNotNull null
            val size = file.size?.toLong() ?: return@mapNotNull null
            val url = file.urlPrivateDownload ?: file.urlPrivate ?: return@mapNotNull null
            if (!fileName.endsWith(".txt", ignoreCase = true)) return@mapNotNull null
            if (size > maxFileSizeBytes) return@mapNotNull null

            SlackKakaoFileUpload(
                slackUserId = userId,
                channelId = channelId,
                messageTs = messageTs,
                fileId = file.id,
                fileName = fileName,
                downloadUrl = url,
            )
        }
    }
}
