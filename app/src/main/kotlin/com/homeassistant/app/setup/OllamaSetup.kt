package com.homeassistant.app.setup

import com.homeassistant.adapter.outbound.embedding.ollama.install.OllamaEmbeddingSetup
import com.homeassistant.configuration.AppConfig
import com.homeassistant.configuration.Env

/**
 * Gradle entry point for the root `setupEmbedding` task.
 *
 * The invocation chain is `setupEmbedding` -> `:app:setupEmbedding` -> this function ->
 * [OllamaEmbeddingSetup.prepare].
 */
fun main() {
    val model = Env[AppConfig.ENV_VAR_EMBEDDING_MODEL]
        ?.takeIf(String::isNotBlank)
        ?: AppConfig.DEFAULT_EMBEDDING_MODEL_NAME
    OllamaEmbeddingSetup.prepare(model = model)
    println("Managed Ollama and embedding model '$model' are ready")
}
