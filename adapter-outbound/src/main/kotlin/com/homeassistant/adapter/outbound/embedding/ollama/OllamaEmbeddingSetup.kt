package com.homeassistant.adapter.outbound.embedding.ollama

import com.homeassistant.common.json.JsonSerializer
import com.homeassistant.configuration.AppConfig
import kotlinx.serialization.Serializable
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

object OllamaEmbeddingSetup {
    fun prepare(
        runtimeRoot: Path = Path.of(AppConfig.DEFAULT_OLLAMA_RUNTIME_DIR),
        host: String = AppConfig.DEFAULT_OLLAMA_HOST,
        model: String = AppConfig.DEFAULT_EMBEDDING_MODEL_NAME,
    ) {
        val normalizedRoot = runtimeRoot.toAbsolutePath().normalize()
        val executable = OllamaDistributionInstaller().install(normalizedRoot)
        val modelsDirectory = normalizedRoot.resolve("models")
        Files.createDirectories(modelsDirectory)
        val endpoint = OllamaEndpoint.parse(host)
        val environment = mapOf(
            "OLLAMA_HOST" to endpoint.hostAndPort,
            "OLLAMA_MODELS" to modelsDirectory.toString(),
            "OLLAMA_DEBUG" to "false",
        )
        val runtime = OllamaServerRuntime(
            command = listOf(executable.toString(), "serve"),
            environment = environment,
            endpoint = endpoint,
            requiredExecutable = executable,
            requiredModelsDirectory = modelsDirectory,
        )

        runtime.use {
            it.start()
            if (!modelExists(endpoint.baseUrl, model)) {
                pullModel(executable, environment, model)
            }
            OllamaEmbeddingFactory.create(endpoint.baseUrl, model)
                .embed("home second brain embedding setup verification")
        }
    }

    private fun modelExists(baseUrl: String, model: String): Boolean {
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/api/tags"))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()
        val response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            "Unable to list Ollama models: HTTP ${response.statusCode()}"
        }
        val expectedNames = if (':' in model.substringAfterLast('/')) {
            setOf(model)
        } else {
            setOf(model, "$model:latest")
        }
        return JsonSerializer.json.decodeFromString<OllamaTagsResponse>(response.body())
            .models
            .any { it.name in expectedNames || it.model in expectedNames }
    }

    private fun pullModel(executable: Path, environment: Map<String, String>, model: String) {
        val process = ProcessBuilder(executable.toString(), "pull", model)
            .inheritIO()
            .also { it.environment().putAll(environment) }
            .start()
        if (!process.waitFor(MODEL_PULL_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.descendants().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly)
            process.destroyForcibly()
            throw IllegalStateException("Timed out while pulling Ollama embedding model $model")
        }
        check(process.exitValue() == 0) {
            "Ollama model pull failed for $model with exit code ${process.exitValue()}"
        }
    }

    private val HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()
    private const val MODEL_PULL_TIMEOUT_MINUTES = 60L
}

@Serializable
private data class OllamaTagsResponse(
    val models: List<OllamaTag> = emptyList(),
)

@Serializable
private data class OllamaTag(
    val name: String = "",
    val model: String = "",
)
