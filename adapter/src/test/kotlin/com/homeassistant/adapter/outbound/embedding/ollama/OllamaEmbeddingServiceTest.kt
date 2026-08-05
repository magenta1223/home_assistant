package com.homeassistant.adapter.outbound.embedding.ollama

import com.homeassistant.core.utils.JsonSerializer
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OllamaEmbeddingServiceTest {
    private var server: HttpServer? = null

    @AfterTest
    fun stopServer() {
        server?.stop(0)
    }

    @Test
    fun `embedding rejects blank text`() {
        val service = OllamaEmbeddingService(
            baseUrl = "http://localhost:11434",
            model = "qllama/multilingual-e5-base",
        )

        assertFailsWith<IllegalArgumentException> {
            service.embed("   ")
        }
    }

    @Test
    fun `embedding posts to ollama embed endpoint and returns normalized vector`() {
        var requestPath = ""
        var requestBody = ""
        server = startServer { exchange ->
            requestPath = exchange.requestURI.path
            requestBody = exchange.requestBody.bufferedReader().use { it.readText() }
            exchange.respondJson(
                """
                {
                  "model": "qllama/multilingual-e5-base",
                  "embeddings": [
                    [${FloatArray(768) { 1f }.joinToString(",")}]
                  ]
                }
                """.trimIndent(),
            )
        }
        val service = OllamaEmbeddingService(
            baseUrl = "http://localhost:${server!!.address.port}",
            model = "qllama/multilingual-e5-base",
        )

        val vector = service.embed("query: 가족 일정")
        val requestJson = JsonSerializer.json.decodeFromString<JsonObject>(requestBody)

        assertEquals("/api/embed", requestPath)
        assertEquals("qllama/multilingual-e5-base", requestJson.getValue("model").jsonPrimitive.content)
        assertEquals("query: 가족 일정", requestJson.getValue("input").jsonPrimitive.content)
        assertEquals(768, vector.size)
        val norm = sqrt(vector.sumOf { (it * it).toDouble() })
        assertEquals(1.0, norm, absoluteTolerance = 0.0001)
    }

    @Test
    fun `embedding rejects unexpected vector size`() {
        server = startServer { exchange ->
            exchange.respondJson("""{"model":"qllama/multilingual-e5-base","embeddings":[[1.0,2.0,3.0]]}""")
        }
        val service = OllamaEmbeddingService(
            baseUrl = "http://localhost:${server!!.address.port}",
            model = "qllama/multilingual-e5-base",
        )

        assertFailsWith<IllegalStateException> {
            service.embed("query: 가족 일정")
        }
    }

    @Test
    fun `embedding fails when ollama returns error`() {
        server = startServer { exchange ->
            exchange.sendResponseHeaders(500, 0)
            exchange.responseBody.use { it.write("boom".toByteArray()) }
        }
        val service = OllamaEmbeddingService(
            baseUrl = "http://localhost:${server!!.address.port}",
            model = "qllama/multilingual-e5-base",
        )

        val error = assertFailsWith<IllegalStateException> {
            service.embed("query: 가족 일정")
        }
        assertTrue(error.message!!.contains("status=500"))
    }

    private fun startServer(handler: (HttpExchange) -> Unit): HttpServer {
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        httpServer.createContext("/api/embed") { exchange -> handler(exchange) }
        httpServer.start()
        return httpServer
    }

    private fun HttpExchange.respondJson(body: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
