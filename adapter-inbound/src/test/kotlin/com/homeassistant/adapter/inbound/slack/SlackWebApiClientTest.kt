package com.homeassistant.adapter.inbound.slack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SlackWebApiClientTest {
    @Test
    fun `postMessage returns only a verified successful Slack timestamp`() {
        val delivery = client(response(ok = true, ts = "171.001"))
            .postMessage("D1", "answer", emptyList())

        assertEquals("171.001", delivery.responseTs)
    }

    @Test
    fun `postMessage rejects isOk false even when a timestamp exists`() {
        val error = assertFailsWith<SlackMessageDeliveryException> {
            client(response(ok = false, ts = "171.001", error = "channel_not_found"))
                .postMessage("D1", "answer", emptyList())
        }

        assertEquals("channel_not_found", error.category)
    }

    @Test
    fun `postMessage rejects a successful response without a timestamp`() {
        val error = assertFailsWith<SlackMessageDeliveryException> {
            client(response(ok = true, ts = null))
                .postMessage("D1", "answer", emptyList())
        }

        assertEquals("MISSING_RESPONSE_TS", error.category)
    }

    private fun response(
        ok: Boolean,
        ts: String?,
        error: String? = null,
    ) = SlackPostMessageResponse(ok, ts, error)

    private fun client(response: SlackPostMessageResponse) =
        SlackApiClient(
            botToken = "token",
            messagePoster = SlackMessagePoster { _, _, _, _ -> response },
        )
}
