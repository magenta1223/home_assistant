package com.homeassistant.app.services

import com.homeassistant.adapter.outbound.embedding.ollama.ManagedOllamaEmbeddingFactory
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryStores
import com.homeassistant.application.port.outbound.embedding.EmbeddingGenerator
import com.homeassistant.application.usecase.memoryextraction.component.PrepareEpisodeEmbedding
import com.homeassistant.application.usecase.memoryextraction.group.PrepareComponentEmbedding
import com.homeassistant.configuration.AppConfig
import com.homeassistant.configuration.Env
import com.homeassistant.infrastructure.runtime.Runtime
import org.slf4j.LoggerFactory

/** Assembles the managed embedding model and the Episode and Component embeddings shared by extraction and search. */
internal class EmbeddingModule(repositories: RepositoryStores) {
    private val model = Env[AppConfig.ENV_VAR_EMBEDDING_MODEL] ?: AppConfig.DEFAULT_EMBEDDING_MODEL_NAME
    private val ollama = ManagedOllamaEmbeddingFactory.create(model = model)

    /** Embeds query text with the managed model. */
    val queries: EmbeddingGenerator = ollama

    /** Provides stored or newly generated Episode embeddings. */
    val episodeEmbeddings = PrepareEpisodeEmbedding(ollama, repositories.episodeEmbeddings)

    /** Provides stored or newly generated Component embeddings. */
    val componentEmbeddings = PrepareComponentEmbedding(ollama, repositories.componentEmbeddings)

    /** The managed Ollama server. */
    val runtimes: List<Runtime> = listOf(ollama)

    init {
        LoggerFactory.getLogger(EmbeddingModule::class.java)
            .info("Managed Ollama embedding model={} baseUrl={}", model, AppConfig.DEFAULT_OLLAMA_BASE_URL)
    }
}
