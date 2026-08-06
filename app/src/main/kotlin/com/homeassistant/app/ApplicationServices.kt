package com.homeassistant.app

import com.homeassistant.adapter.inbound.slack.SlackConfig
import com.homeassistant.adapter.inbound.slack.SlackRuntime
import com.homeassistant.adapter.inbound.slack.SlackRuntimeFactory
import com.homeassistant.adapter.outbound.topicanalysis.TopicExtractorFactory
import com.homeassistant.adapter.outbound.codex.conversation.CodexConversationClientFactory
import com.homeassistant.adapter.outbound.codex.conversation.CodexConversationConfig
import com.homeassistant.adapter.outbound.embedding.ollama.OllamaEmbeddingFactory
import com.homeassistant.adapter.outbound.vector.qdrant.QdrantVectorStoreFactory
import com.homeassistant.application.memory.answer.AnswerFromMemories
import com.homeassistant.application.memory.answer.AnswerFromMemoriesUseCase
import com.homeassistant.application.memory.search.SearchMemories
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysis
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisUseCase
import com.homeassistant.application.topicanalysis.review.GetTopicAnalysisReview
import com.homeassistant.application.topicanalysis.save.SaveAnalyzedTopics
import com.homeassistant.application.topicanalysis.save.SaveAnalyzedTopicsUseCase
import com.homeassistant.configuration.AppConfig
import com.homeassistant.configuration.Env
import com.homeassistant.domain.identity.HouseholdAccessPolicies
import com.homeassistant.adapter.outbound.vector.memory.MemoryIndexerFactory
import com.homeassistant.adapter.outbound.vector.memory.MemorySearcherFactory
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import org.slf4j.LoggerFactory

interface ApplicationServices : AutoCloseable {
    val topicAnalysis: TopicAnalysisUseCase
    val saveAnalyzedTopics: SaveAnalyzedTopicsUseCase
    val memoryAnswer: AnswerFromMemoriesUseCase
    fun start()
}

private class DefaultApplicationServices(
    override val topicAnalysis: TopicAnalysisUseCase,
    override val saveAnalyzedTopics: SaveAnalyzedTopicsUseCase,
    override val memoryAnswer: AnswerFromMemoriesUseCase,
    private val slackRuntime: SlackRuntime?,
) : ApplicationServices {
    override fun start() {
        slackRuntime?.startAsync()
    }

    override fun close() {
        slackRuntime?.close()
    }
}

object ApplicationServicesFactory {
    fun create(dbPath: String): ApplicationServices {
        val repositories = RepositoryFactory.create(dbPath)
        val embeddingModel = Env[AppConfig.ENV_VAR_EMBEDDING_MODEL]
            ?: AppConfig.DEFAULT_EMBEDDING_MODEL_NAME
        val embeddingBaseUrl = Env[AppConfig.ENV_VAR_OLLAMA_BASE_URL]
            ?: AppConfig.DEFAULT_OLLAMA_BASE_URL
        log.info("Ollama embedding model={} baseUrl={}", embeddingModel, embeddingBaseUrl)

        val textEmbedder = OllamaEmbeddingFactory.create(embeddingBaseUrl, embeddingModel)
        val vectorStore = QdrantVectorStoreFactory.create(
            baseUrl = Env[AppConfig.ENV_VAR_QDRANT_URL] ?: AppConfig.DEFAULT_QDRANT_URL,
            collection = Env[AppConfig.ENV_VAR_QDRANT_COLLECTION] ?: AppConfig.DEFAULT_QDRANT_COLLECTION,
        )
        val memoryIndexer = MemoryIndexerFactory.create(textEmbedder, vectorStore)
        val memorySearcher = MemorySearcherFactory.create(textEmbedder, vectorStore)
        val slackConfig = SlackConfig.fromEnv()
        val accessPolicy = slackConfig?.identityDirectory?.accessPolicy
            ?: HouseholdAccessPolicies.denyAll()
        val topicAnalysis = TopicAnalysis(
            topicExtractor = TopicExtractorFactory.create(),
            sourceRecords = repositories.sourceRecords,
            reviewStore = repositories.topicAnalysisReviews,
            accessPolicy = accessPolicy,
        )
        val saveAnalyzedTopics = SaveAnalyzedTopics(
            topicCreator = repositories.topicCreator,
            reviewStore = repositories.topicAnalysisReviews,
            memoryIndexer = memoryIndexer,
            memoryIndexingSource = repositories.memoryIndexingSource,
            indexingOutbox = repositories.indexingOutbox,
            accessPolicy = accessPolicy,
        )
        val searchMemories = SearchMemories(
            memories = repositories.canonicalMemories,
            accessPolicy = accessPolicy,
            searcher = memorySearcher,
        )
        val memoryAnswer = AnswerFromMemories(searchMemories)
        val getTopicAnalysisReview = GetTopicAnalysisReview(repositories.topicAnalysisReviews, accessPolicy)
        val conversationClient = CodexConversationConfig.fromEnv()
            ?.let(CodexConversationClientFactory::create)
            ?.takeIf { it.validateVersion() }
        val slackRuntime = slackConfig?.let {
            SlackRuntimeFactory.create(
                it,
                topicAnalysis,
                getTopicAnalysisReview,
                saveAnalyzedTopics,
                searchMemories,
                repositories.slackCodexSessions,
                conversationClient,
            )
        }
        if (slackRuntime == null) {
            log.info("Slack Socket Mode disabled: Slack token, team, or member mapping configuration is missing")
        }
        return DefaultApplicationServices(topicAnalysis, saveAnalyzedTopics, memoryAnswer, slackRuntime)
    }
}

private val log = LoggerFactory.getLogger(ApplicationServicesFactory::class.java)
