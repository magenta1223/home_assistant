package com.homeassistant.adapter.inbound.slack

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

internal interface SlackTextDownloader {
    fun download(url: String, maxBytes: Long): String
}

private class HttpSlackTextDownloader(
    private val botToken: String,
    private val httpClient: HttpClient,
) : SlackTextDownloader {
    override fun download(url: String, maxBytes: Long): String {
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
}

internal object SlackTextDownloaderFactory {
    fun http(
        botToken: String,
        httpClient: HttpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build(),
    ): SlackTextDownloader = HttpSlackTextDownloader(botToken, httpClient)
}

internal object SlackTextDecoder {
    fun decode(body: ByteArray): String =
        when {
            body.startsWith(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) ->
                Charsets.UTF_16LE.decode(ByteBuffer.wrap(body, 2, body.size - 2)).toString()
            body.startsWith(byteArrayOf(0xFE.toByte(), 0xFF.toByte())) ->
                Charsets.UTF_16BE.decode(ByteBuffer.wrap(body, 2, body.size - 2)).toString()
            body.looksLikeUtf16Le() -> Charsets.UTF_16LE.decode(ByteBuffer.wrap(body)).toString()
            body.looksLikeUtf16Be() -> Charsets.UTF_16BE.decode(ByteBuffer.wrap(body)).toString()
            else -> decodeUtf8OrMs949(body)
        }

    private fun decodeUtf8OrMs949(body: ByteArray): String =
        try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString()
        } catch (_: CharacterCodingException) {
            Charset.forName("MS949").decode(ByteBuffer.wrap(body)).toString()
        }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun ByteArray.looksLikeUtf16Le(): Boolean =
        size >= 8 && oddNullByteRatio() > 0.3 && evenNullByteRatio() < 0.05

    private fun ByteArray.looksLikeUtf16Be(): Boolean =
        size >= 8 && evenNullByteRatio() > 0.3 && oddNullByteRatio() < 0.05

    private fun ByteArray.evenNullByteRatio(): Double = nullByteRatio(startIndex = 0)
    private fun ByteArray.oddNullByteRatio(): Double = nullByteRatio(startIndex = 1)

    private fun ByteArray.nullByteRatio(startIndex: Int): Double {
        val sampled = indices.count { it % 2 == startIndex }
        if (sampled == 0) return 0.0
        val nulls = indices.count { it % 2 == startIndex && this[it] == 0.toByte() }
        return nulls.toDouble() / sampled
    }
}
