package com.homeassistant.adapter.outbound.runtime.distribution.downloader

import org.slf4j.LoggerFactory
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration

internal class HttpAssetDownloader(
    private val client: HttpClient,
) : AssetDownloader {
    constructor() : this(
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build()
    )

    private val log = LoggerFactory.getLogger(javaClass)

    override fun download(sourceUri: URI, target: Path) {
        val request = buildHttpRequest(sourceUri)
        val response = getResponse(request)

        response.body().use { input ->
            check(response.statusCode() in 200..299) {
                "Download failed: HTTP ${response.statusCode()}"
            }
            val contentLength = response
                .headers()
                .firstValueAsLong("Content-Length")
                .orElse(-1L)
            write(input, target, contentLength)
        }
    }

    private fun buildHttpRequest(uri: URI): HttpRequest {
        return HttpRequest.newBuilder(uri)
            .timeout(Duration.ofHours(1))
            .GET()
            .build()
    }

    private fun getResponse(request: HttpRequest): HttpResponse<InputStream> {
        return client.send(request, HttpResponse.BodyHandlers.ofInputStream())
    }

    private fun write(input: InputStream, target: Path, contentLength: Long) {
        Files.newOutputStream(target, StandardOpenOption.CREATE_NEW).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var downloadedBytes = 0L
            var nextProgress = 10

            while (true) {
                val count = input.read(buffer)
                if (count < 0) break

                output.write(buffer, 0, count)
                downloadedBytes += count

                if (contentLength > 0) {
                    val progress = (downloadedBytes * 100 / contentLength).toInt()
                    if (progress >= nextProgress) {
                        log.info("Download progress={}%, bytes={}", progress, downloadedBytes)
                        nextProgress += 10
                    }
                }
            }
        }
    }
}
