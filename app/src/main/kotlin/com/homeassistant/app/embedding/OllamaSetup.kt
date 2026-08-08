package com.homeassistant.app.embedding

import com.homeassistant.adapter.outbound.embedding.ollama.OllamaEmbeddingSetup
import com.homeassistant.configuration.AppConfig
import com.homeassistant.configuration.Env

fun main() {
    val model = Env[AppConfig.ENV_VAR_EMBEDDING_MODEL]
        ?.takeIf(String::isNotBlank)
        ?: AppConfig.DEFAULT_EMBEDDING_MODEL_NAME
    OllamaEmbeddingSetup.prepare(model = model)
    println("Managed Ollama and embedding model '$model' are ready")
}
