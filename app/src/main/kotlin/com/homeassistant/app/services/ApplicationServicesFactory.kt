package com.homeassistant.app.services

import com.homeassistant.adapter.inbound.slack.SlackConfig
import com.homeassistant.adapter.inbound.slack.SlackRuntimeFactory
import com.homeassistant.adapter.outbound.memoryanalysis.MemoryExtractorFactory
import com.homeassistant.adapter.outbound.memoryanalysis.MemoryPlacementExtractorFactory
import com.homeassistant.adapter.outbound.codex.conversation.CodexConversationClientFactory
import com.homeassistant.adapter.outbound.codex.conversation.CodexConversationConfig
import com.homeassistant.adapter.outbound.embedding.ollama.ManagedOllamaEmbeddingFactory
import com.homeassistant.adapter.outbound.vector.qdrant.QdrantVectorStoreFactory
import com.homeassistant.application.usecase.memory.answer.MemoryAnswerContextProvider
import com.homeassistant.application.usecase.memory.analysis.MemoryAnalysisService
import com.homeassistant.application.usecase.memory.placement.MemoryPlacementService
import com.homeassistant.configuration.AppConfig
import com.homeassistant.configuration.Env
import com.homeassistant.domain.identity.HouseholdAccessPolicies
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.adapter.outbound.vector.memory.SemanticMemoryIndexSearcherFactory
import com.homeassistant.adapter.outbound.vector.memory.SemanticMemoryIndexWriterFactory
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import com.homeassistant.application.usecase.memory.search.MemorySearcher
import com.homeassistant.application.usecase.memory.write.MemoryProposalsPersister
import com.homeassistant.application.usecase.memory.write.MemoryIndexingOutboxProcessor
import com.homeassistant.application.usecase.memory.conversation.HandleMemoryConversation
import com.homeassistant.application.usecase.memory.conversation.MemoryConversationContextProvider
import com.homeassistant.application.port.input.identity.ConversationIdentity
import com.homeassistant.application.usecase.identity.HouseholdMemberService
import org.slf4j.LoggerFactory


object ApplicationServicesFactory {
    private val log = LoggerFactory.getLogger(ApplicationServicesFactory::class.java)

    fun create(
        dbPath: String,
        httpUsers: Collection<UserId> = emptyList(),
    ): ApplicationServices {
        val repositories = RepositoryFactory.create(dbPath)
        val householdMembers = HouseholdMemberService(repositories.householdMembers)
        val embeddingModel = Env[AppConfig.ENV_VAR_EMBEDDING_MODEL]
            ?: AppConfig.DEFAULT_EMBEDDING_MODEL_NAME
        val managedEmbedding = ManagedOllamaEmbeddingFactory.create(model = embeddingModel)
        val textEmbedder = managedEmbedding.embedder
        log.info("Managed Ollama embedding model={} baseUrl={}", embeddingModel, AppConfig.DEFAULT_OLLAMA_BASE_URL)
        val vectorStore = QdrantVectorStoreFactory.create(
            baseUrl = Env[AppConfig.ENV_VAR_QDRANT_URL] ?: AppConfig.DEFAULT_QDRANT_URL,
            collection = Env[AppConfig.ENV_VAR_QDRANT_COLLECTION] ?: AppConfig.DEFAULT_QDRANT_COLLECTION,
        )
        val memoryIndexWriter = SemanticMemoryIndexWriterFactory.create(textEmbedder, vectorStore)
        val memoryIndexing = MemoryIndexingOutboxProcessor(
            outbox = repositories.memoryIndexingOutbox,
            indexWriter = memoryIndexWriter,
        )
        val semanticMemoryIndexSearcher = SemanticMemoryIndexSearcherFactory.create(textEmbedder, vectorStore)
        val slackConfig = SlackConfig.fromEnv()
        slackConfig?.legacyMemberMappings.orEmpty().forEach { legacy ->
            householdMembers.reserveLegacy(
                identity = ConversationIdentity(legacy.teamId, legacy.slackUserId),
                userId = legacy.userId,
            )
        }
        val httpAccessPolicy = HouseholdAccessPolicies.fixed(httpUsers)
        val accessPolicy = HouseholdAccessPolicy { userId ->
            householdMembers.isAuthorized(userId) || httpAccessPolicy.isAuthorized(userId)
        }
        val memorySaver = MemoryProposalsPersister(
            batchWriter = repositories.canonicalMemoryBatchWriter,
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
            memoryIndexing = memoryIndexing,
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
        val conversationClient = CodexConversationConfig.local()
            ?.let(CodexConversationClientFactory::create)
            ?.takeIf { it.isAvailable() }
        val memoryConversation = conversationClient?.let {
            val now = System.currentTimeMillis()
            repositories.memoryConversationSessions.failStaleProcessing(
                before = now - HandleMemoryConversation.SESSION_IDLE_TIMEOUT_MILLIS,
                now = now,
            )
            HandleMemoryConversation(
                sessions = repositories.memoryConversationSessions,
                contextProvider = MemoryConversationContextProvider(answerContext),
                conversationClient = it,
            )
        }
        val slackRuntime = slackConfig?.let { config ->
            if (memoryConversation == null) {
                log.info("Slack memory answers disabled: local Codex CLI is unavailable or its timeout is invalid")
            }
            SlackRuntimeFactory.create(config, memoryConversation, householdMembers)
        }
        if (slackRuntime == null) {
            log.info("Slack Socket Mode disabled: Slack token or team configuration is missing")
        }
        return DefaultApplicationServices(
            memoryAnalysis = memoryAnalysisService,
            slackRuntime = slackRuntime,
            householdMembers = householdMembers,
            embeddingRuntime = managedEmbedding.runtime,
            indexingWorker = MemoryIndexingWorker(memoryIndexing),
        )
    }
}
