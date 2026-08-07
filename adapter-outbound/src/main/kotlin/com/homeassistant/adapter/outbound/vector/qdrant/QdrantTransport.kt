package com.homeassistant.adapter.outbound.vector.qdrant

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** Sends raw HTTP requests to the Qdrant API. */
internal interface QdrantTransport {
    /** Sends an HTTP request to Qdrant and returns the response body. */
    fun request(method: String, path: String, body: String): String
}

private class HttpQdrantTransport(
    private val baseUrl: String,
    private val client: HttpClient,
) : QdrantTransport {
    override fun request(method: String, path: String, body: String): String {
        val request = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + path))
            .method(method, HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json")
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            "Qdrant request failed status=${response.statusCode()} body=${response.body()}"
        }
        return response.body()
    }
}

internal object QdrantTransportFactory {
    fun http(
        baseUrl: String,
        client: HttpClient = HttpClient.newHttpClient(),
    ): QdrantTransport = HttpQdrantTransport(baseUrl, client)
}
