package com.homeassistant.app.services

import com.homeassistant.adapter.inbound.slack.SlackConfig
import com.homeassistant.adapter.inbound.slack.SlackRuntimeFactory
import com.homeassistant.adapter.outbound.topicanalysis.TopicExtractorFactory
import com.homeassistant.adapter.outbound.codex.conversation.CodexConversationClientFactory
import com.homeassistant.adapter.outbound.codex.conversation.CodexConversationConfig
import com.homeassistant.adapter.outbound.embedding.ollama.OllamaEmbeddingFactory
import com.homeassistant.adapter.outbound.vector.qdrant.QdrantVectorStoreFactory
import com.homeassistant.application.memory.answer.AnswerFromMemories
import com.homeassistant.application.memory.search.SearchMemories
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisService
import com.homeassistant.application.topicanalysis.save.SaveTopicProposals
import com.homeassistant.configuration.AppConfig
import com.homeassistant.configuration.Env
import com.homeassistant.domain.identity.HouseholdAccessPolicies
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.adapter.outbound.vector.memory.SemanticMemoryIndexSearcherFactory
import com.homeassistant.adapter.outbound.vector.memory.SemanticMemoryIndexWriterFactory
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import org.slf4j.LoggerFactory


object ApplicationServicesFactory {
    private val log = LoggerFactory.getLogger(ApplicationServicesFactory::class.java)

    fun create(
        dbPath: String,
        httpUsers: Collection<UserId> = emptyList(),
    ): ApplicationServices {
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
        val memoryIndexWriter = SemanticMemoryIndexWriterFactory.create(textEmbedder, vectorStore)
        val semanticMemoryIndexSearcher = SemanticMemoryIndexSearcherFactory.create(textEmbedder, vectorStore)
        val slackConfig = SlackConfig.fromEnv()
        val slackAccessPolicy = slackConfig?.identityDirectory?.accessPolicy
        val httpAccessPolicy = HouseholdAccessPolicies.fixed(httpUsers)
        val accessPolicy = if (slackAccessPolicy == null && httpUsers.isEmpty()) {
            HouseholdAccessPolicies.denyAll()
        } else {
            HouseholdAccessPolicy { userId ->
                (slackAccessPolicy?.isAuthorized(userId) == true) ||
                    httpAccessPolicy.isAuthorized(userId)
            }
        }
        val topicSaver = SaveTopicProposals(
            topicCreator = repositories.topicCreator,
            memoryIndexWriter = memoryIndexWriter,
            canonicalMemoryReader = repositories.canonicalMemories,
            indexingOutbox = repositories.indexingOutbox,
        )
        val topicAnalysisService = TopicAnalysisService(
            topicExtractor = TopicExtractorFactory.create(),
            sourceRecords = repositories.sourceRecords,
            topicSaver = topicSaver,
            accessPolicy = accessPolicy,
        )
        val searchMemories = SearchMemories(
            memories = repositories.canonicalMemories,
            accessPolicy = accessPolicy,
            searcher = semanticMemoryIndexSearcher,
        )
        val memoryAnswer = AnswerFromMemories(searchMemories)
        val conversationClient = CodexConversationConfig.fromEnv()
            ?.let(CodexConversationClientFactory::create)
            ?.takeIf { it.validateVersion() }
        val slackRuntime = slackConfig?.let {
            SlackRuntimeFactory.create(
                it,
                topicAnalysisService,
                searchMemories,
                repositories.slackCodexSessions,
                conversationClient,
            )
        }
        if (slackRuntime == null) {
            log.info("Slack Socket Mode disabled: Slack token, team, or member mapping configuration is missing")
        }
        return DefaultApplicationServices(topicAnalysisService, memoryAnswer, slackRuntime)
    }
}
