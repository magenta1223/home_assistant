package com.homeassistant.app.services

import com.homeassistant.adapter.outbound.embedding.ollama.ManagedOllamaEmbeddingFactory
import com.homeassistant.application.port.outbound.embedding.EmbeddingGenerator
import com.homeassistant.configuration.AppConfig
import com.homeassistant.configuration.Env
import com.homeassistant.infrastructure.runtime.Runtime
import org.slf4j.LoggerFactory

/** Assembles the managed embedding model. */
internal class EmbeddingModule {
    private val model = Env[AppConfig.ENV_VAR_EMBEDDING_MODEL] ?: AppConfig.DEFAULT_EMBEDDING_MODEL_NAME
    private val ollama = ManagedOllamaEmbeddingFactory.create(model = model)

    /** Embeds documents and queries with the managed model. */
    val generator: EmbeddingGenerator = ollama

    /** The managed Ollama server. */
    val runtimes: List<Runtime> = listOf(ollama)

    init {
        LoggerFactory.getLogger(EmbeddingModule::class.java)
            .info("Managed Ollama embedding model={} baseUrl={}", model, AppConfig.DEFAULT_OLLAMA_BASE_URL)
    }
}
