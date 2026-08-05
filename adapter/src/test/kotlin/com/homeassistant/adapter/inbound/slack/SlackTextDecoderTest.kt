package com.homeassistant.adapter.inbound.slack

import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals

class SlackTextDecoderTest {
    @Test
    fun `decodes UTF-8 text`() {
        assertEquals("가족 대화", SlackTextDecoder.decode("가족 대화".toByteArray()))
    }

    @Test
    fun `falls back to MS949 text`() {
        val encoded = "카카오 대화".toByteArray(Charset.forName("MS949"))

        assertEquals("카카오 대화", SlackTextDecoder.decode(encoded))
    }

    @Test
    fun `decodes UTF-16 little endian text with BOM`() {
        val encoded = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "가족 대화".toByteArray(Charsets.UTF_16LE)

        assertEquals("가족 대화", SlackTextDecoder.decode(encoded))
    }
}
