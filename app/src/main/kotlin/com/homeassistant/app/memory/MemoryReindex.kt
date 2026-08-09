package com.homeassistant.app.memory

import com.homeassistant.adapter.outbound.embedding.ollama.ManagedOllamaEmbeddingFactory
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import com.homeassistant.adapter.outbound.vector.memory.SemanticMemoryIndexWriterFactory
import com.homeassistant.adapter.outbound.vector.qdrant.QdrantVectorStoreFactory
import com.homeassistant.application.usecase.memory.write.MemoryIndexingOutboxProcessor
import com.homeassistant.configuration.AppConfig
import com.homeassistant.configuration.Env
import org.slf4j.LoggerFactory

/** Recovery command: `gradlew reindexMemories --args="path/to/database.sqlite"`. */
fun main(args: Array<String>) {
    val dbPath = args.firstOrNull() ?: AppConfig.DEFAULT_DB_PATH
    val repositories = RepositoryFactory.create(dbPath)
    val embeddingModel = Env[AppConfig.ENV_VAR_EMBEDDING_MODEL]
        ?: AppConfig.DEFAULT_EMBEDDING_MODEL_NAME
    val managedEmbedding = ManagedOllamaEmbeddingFactory.create(model = embeddingModel)
    managedEmbedding.runtime.start()
    try {
        val vectorStore = QdrantVectorStoreFactory.create(
            baseUrl = Env[AppConfig.ENV_VAR_QDRANT_URL] ?: AppConfig.DEFAULT_QDRANT_URL,
            collection = Env[AppConfig.ENV_VAR_QDRANT_COLLECTION] ?: AppConfig.DEFAULT_QDRANT_COLLECTION,
        )
        val result = MemoryIndexingOutboxProcessor(
            outbox = repositories.memoryIndexingOutbox,
            indexWriter = SemanticMemoryIndexWriterFactory.create(managedEmbedding.embedder, vectorStore),
            retryDelayMillis = 0,
        ).reindexAll()
        log.info("Canonical memory reindex finished: completed={} failed={}", result.completed, result.failed)
        check(result.failed == 0) { "${result.failed} canonical memories could not be indexed" }
    } finally {
        managedEmbedding.runtime.close()
    }
}

private val log = LoggerFactory.getLogger("MemoryReindex")
