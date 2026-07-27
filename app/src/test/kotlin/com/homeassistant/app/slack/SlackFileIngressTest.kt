package com.homeassistant.app.slack

import com.slack.api.model.File
import com.slack.api.model.event.MessageFileShareEvent
import com.homeassistant.domain.slackconversation.SlackPrincipal
import kotlin.test.Test
import kotlin.test.assertEquals

class SlackFileIngressTest {
    @Test
    fun `creates uploads for txt files shared in bot dm`() {
        val event = fileShareEvent(
            channelType = "im",
            files = listOf(file("kakao.txt", 1024, "https://slack/files/kakao.txt")),
        )

        val uploads = SlackFileIngress.from(event, principal(), maxFileSizeBytes = 10_485_760)

        assertEquals(1, uploads.size)
        assertEquals("U1", uploads.single().principal.slackUserId)
        assertEquals("D1", uploads.single().channelId)
        assertEquals("1710000000.000100", uploads.single().messageTs)
        assertEquals("kakao.txt", uploads.single().fileName)
        assertEquals("https://slack/files/kakao.txt", uploads.single().downloadUrl)
    }

    @Test
    fun `ignores non dm file shares`() {
        val event = fileShareEvent(
            channelType = "channel",
            files = listOf(file("kakao.txt", 1024, "https://slack/files/kakao.txt")),
        )

        assertEquals(emptyList(), SlackFileIngress.from(event, principal(), maxFileSizeBytes = 10_485_760))
    }

    @Test
    fun `ignores non txt and oversized files`() {
        val event = fileShareEvent(
            channelType = "im",
            files = listOf(
                file("image.png", 1024, "https://slack/files/image.png"),
                file("large.txt", 11_000_000, "https://slack/files/large.txt"),
            ),
        )

        assertEquals(emptyList(), SlackFileIngress.from(event, principal(), maxFileSizeBytes = 10_485_760))
    }

    private fun fileShareEvent(
        channelType: String,
        files: List<File>,
    ) = MessageFileShareEvent().apply {
        user = "U1"
        channel = "D1"
        ts = "1710000000.000100"
        this.channelType = channelType
        this.files = files
    }

    private fun file(
        name: String,
        size: Int,
        downloadUrl: String,
    ) = File().apply {
        this.name = name
        this.size = size
        this.urlPrivateDownload = downloadUrl
    }

    private fun principal() =
        SlackPrincipal("T1", "U1", "dad", "family-1")
}
