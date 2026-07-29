package com.homeassistant.nlp.embedding

import com.homeassistant.core.constants.AppConfig
import com.homeassistant.core.utils.JsonSerializer
import com.homeassistant.domain.memory.EmbeddingService
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.math.sqrt

internal class OllamaEmbeddingService(
    private val baseUrl: String,
    private val model: String,
    private val vectorSize: Int = AppConfig.DEFAULT_EMBEDDING_VECTOR_SIZE,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
) : EmbeddingService {
    override fun embed(text: String): List<Float> {
        val normalizedText = text.trim()
        require(normalizedText.isNotEmpty()) { "Embedding text must not be blank" }

        val request = HttpRequest.newBuilder(embedUri())
            .timeout(Duration.ofSeconds(120))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    JsonSerializer.json.encodeToString(OllamaEmbedRequest(model = model, input = normalizedText)),
                ),
            )
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            "Ollama embedding request failed status=${response.statusCode()} body=${response.body()}"
        }

        val vector = JsonSerializer.json
            .decodeFromString<OllamaEmbedResponse>(response.body())
            .embeddings
            .firstOrNull()
            ?: error("Ollama embedding response did not include embeddings")
        check(vector.size == vectorSize) {
            "Embedding vector size mismatch: expected=$vectorSize actual=${vector.size}"
        }
        return normalize(vector)
    }

    private fun embedUri(): URI =
        URI.create("${baseUrl.trimEnd('/')}/api/embed")
}

object EmbeddingServiceFactory {
    fun ollama(baseUrl: String, model: String): EmbeddingService =
        OllamaEmbeddingService(baseUrl, model)
}

@Serializable
private data class OllamaEmbedRequest(
    val model: String,
    val input: String,
)

@Serializable
private data class OllamaEmbedResponse(
    val embeddings: List<List<Float>> = emptyList(),
)

private fun normalize(vector: List<Float>): List<Float> {
    var normSquared = 0.0
    vector.forEach { value -> normSquared += value * value }
    val norm = sqrt(normSquared).toFloat()
    require(norm > 0f) { "Embedding vector norm must be positive" }
    return vector.map { it / norm }
}
