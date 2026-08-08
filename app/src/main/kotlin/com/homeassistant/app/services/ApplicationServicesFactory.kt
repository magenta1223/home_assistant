package com.homeassistant.app.services

import com.homeassistant.adapter.inbound.slack.SlackConfig
import com.homeassistant.adapter.inbound.slack.SlackRuntimeFactory
import com.homeassistant.adapter.outbound.memoryanalysis.MemoryExtractorFactory
import com.homeassistant.adapter.outbound.memoryanalysis.MemoryPlacementExtractorFactory
import com.homeassistant.adapter.outbound.codex.conversation.CodexConversationClientFactory
import com.homeassistant.adapter.outbound.codex.conversation.CodexConversationConfig
import com.homeassistant.adapter.outbound.embedding.ollama.OllamaEmbeddingFactory
import com.homeassistant.adapter.outbound.vector.qdrant.QdrantVectorStoreFactory
import com.homeassistant.application.memory.memorygroundedchat.MemoryGroundedChatbot
import com.homeassistant.application.memory.memorygroundedchat.MemoryAnswerContextProvider
import com.homeassistant.application.memory.analysis.MemoryAnalysisService
import com.homeassistant.application.memory.tree.MemoryPlacementService
import com.homeassistant.configuration.AppConfig
import com.homeassistant.configuration.Env
import com.homeassistant.domain.identity.HouseholdAccessPolicies
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.adapter.outbound.vector.memory.SemanticMemoryIndexSearcherFactory
import com.homeassistant.adapter.outbound.vector.memory.SemanticMemoryIndexWriterFactory
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import com.homeassistant.application.memory.read.MemorySearcher
import com.homeassistant.application.memory.write.MemoryProposalsPersister
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
        val memorySaver = MemoryProposalsPersister(
            memoryWriter = repositories.memoryWriter,
            memoryIndexWriter = memoryIndexWriter,
        )
        val memoryAnalysisService = MemoryAnalysisService(
            memoryExtractor = MemoryExtractorFactory.create(),
            sourceRecords = repositories.sourceRecords,
            memorySaver = memorySaver,
            accessPolicy = accessPolicy,
            memoryPlacement = MemoryPlacementService(
                memoryReader = repositories.canonicalMemories,
                extractor = MemoryPlacementExtractorFactory.create(),
                tree = repositories.memoryTree,
            ),
        )
        val memorySearcherImpl = MemorySearcher(
            memories = repositories.canonicalMemories,
            accessPolicy = accessPolicy,
            searcher = semanticMemoryIndexSearcher,
        )
        val answerContext = MemoryAnswerContextProvider(
            memorySearcher = memorySearcherImpl,
            memories = repositories.canonicalMemories,
            semanticSearcher = semanticMemoryIndexSearcher,
        )
        val memoryAnswer = MemoryGroundedChatbot(answerContext)
        val conversationClient = CodexConversationConfig.fromEnv()
            ?.let(CodexConversationClientFactory::create)
            ?.takeIf { it.validateVersion() }
        val slackRuntime = slackConfig?.let {
            SlackRuntimeFactory.create(
                it,
                memoryAnalysisService,
                answerContext,
                repositories.slackCodexSessions,
                conversationClient,
            )
        }
        if (slackRuntime == null) {
            log.info("Slack Socket Mode disabled: Slack token, team, or member mapping configuration is missing")
        }
        return DefaultApplicationServices(memoryAnalysisService, memoryAnswer, slackRuntime)
    }
}
