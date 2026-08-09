package com.homeassistant.app.memory

import com.homeassistant.adapter.outbound.embedding.ollama.ManagedOllamaEmbeddingFactory
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import com.homeassistant.adapter.outbound.vector.memory.SemanticMemoryIndexWriterFactory
import com.homeassistant.adapter.outbound.vector.qdrant.QdrantVectorStoreFactory
import com.homeassistant.application.usecase.memory.write.MemoryIndexingOutboxProcessor
import com.homeassistant.configuration.AppConfig
import com.homeassistant.configuration.Env
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/** Recovery command: `gradlew reindexMemories --args="path/to/database.sqlite"`. */
fun main(args: Array<String>) {
    val dbPath = requireExistingDatabase(args.firstOrNull() ?: AppConfig.DEFAULT_DB_PATH)
    val repositories = RepositoryFactory.create(dbPath.toString())
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
        log.info(
            "Canonical memory reindex finished: completed={} failed={} superseded={}",
            result.completed,
            result.failed,
            result.superseded,
        )
        check(result.failed == 0 && result.superseded == 0) {
            "Canonical memory reindex incomplete: failed=${result.failed} superseded=${result.superseded}"
        }
    } finally {
        managedEmbedding.runtime.close()
    }
}

internal fun requireExistingDatabase(path: String): Path {
    val resolved = Path.of(path).toAbsolutePath().normalize()
    require(Files.isRegularFile(resolved)) { "Canonical-memory database does not exist: $resolved" }
    return resolved
}

private val log = LoggerFactory.getLogger("MemoryReindex")
