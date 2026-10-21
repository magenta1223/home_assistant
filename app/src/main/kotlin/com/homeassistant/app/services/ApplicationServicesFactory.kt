package com.homeassistant.app.services

import com.homeassistant.adapter.inbound.kakao.localdb.KakaoLocalDbConfig
import com.homeassistant.adapter.inbound.kakao.localdb.KakaoLocalDbSync
import com.homeassistant.adapter.inbound.kakao.localdb.KakaoPlainDbStore
import com.homeassistant.adapter.inbound.kakao.localdb.KakaoSyncCursor
import com.homeassistant.adapter.outbound.conversation.AgentConversationGateway
import com.homeassistant.adapter.outbound.memoryanalysis.MemoryExtractorFactory
import com.homeassistant.adapter.outbound.embedding.ollama.ManagedOllamaEmbeddingFactory
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysis
import com.homeassistant.application.usecase.memory.analysis.MemoryAnalysisService
import com.homeassistant.configuration.AppConfig
import com.homeassistant.configuration.Env
import com.homeassistant.domain.identity.UserAccessPolicies
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import com.homeassistant.adapter.outbound.reference.SourceReferenceInterpreterFactory
import com.homeassistant.adapter.outbound.topicdescription.TopicDescriptionGeneratorFactory
import com.homeassistant.application.usecase.memory.write.MemoryProposalsPersister
import com.homeassistant.application.usecase.memory.conversation.HandleMemoryConversation
import com.homeassistant.application.usecase.memory.conversation.ExpireIdleMemoryConversations
import com.homeassistant.application.usecase.memory.conversation.MemoryConversationContextProvider
import com.homeassistant.application.usecase.identity.UserRegistryService
import com.homeassistant.adapter.outbound.embedding.memory.MemoryEmbeddingAdapter
import com.homeassistant.adapter.outbound.embedding.memory.QueryEmbeddingAdapter
import com.homeassistant.application.usecase.topic.*
import com.homeassistant.application.usecase.group.*
import com.homeassistant.domain.group.*
import com.homeassistant.llm.AgentBackend
import com.homeassistant.llm.AgentClientFactory
import org.slf4j.LoggerFactory
import java.time.Duration


object ApplicationServicesFactory {
    private val log = LoggerFactory.getLogger(ApplicationServicesFactory::class.java)

    fun create(
        dbPath: String,
        httpUsers: Collection<UserId> = emptyList(),
        groupSettings: GroupPolicySettings = GroupPolicySettings(),
    ): ApplicationServices {
        val agentBackend = agentBackend()
        val completionClient = AgentClientFactory.completion(agentBackend)
        val repositories = RepositoryFactory.create(dbPath)
        val users = UserRegistryService(repositories.users)
        val embeddingModel = Env[AppConfig.ENV_VAR_EMBEDDING_MODEL]
            ?: AppConfig.DEFAULT_EMBEDDING_MODEL_NAME
        val managedEmbedding = ManagedOllamaEmbeddingFactory.create(model = embeddingModel)
        val textEmbedder = managedEmbedding.embedder
        val memoryEmbeddings = PrepareMemoryEmbedding(
            MemoryEmbeddingAdapter(textEmbedder, embeddingModel, managedEmbedding.runtime),
            repositories.memoryEmbeddings,
        )
        val topicEmbeddings = PrepareTopicEmbedding(
            TopicCentroidVectors(repositories.topicMaintenance, memoryEmbeddings.space),
            repositories.topicEmbeddings,
        )
        val organizeTopics = OrganizePendingTopicsService(
            repositories.pendingTopics,
            OrganizeTopic(
                repositories.groupSnapshots,
                repositories.groupChanges,
                topicEmbeddings,
                GroupOrganizationPlanner(GroupPlacementPolicy(groupSettings), GroupSplitPolicy(groupSettings)),
            ),
        )
        val organizeMemories = OrganizePendingMemoriesService(
            repositories.topicAssignmentOutbox,
            AssignMemoryToTopics(repositories.topicMaintenance, memoryEmbeddings),
            RefreshGroupedTopicEmbeddings(repositories.groupSnapshots, repositories.groupChanges, topicEmbeddings),
            organizeTopics,
        )
        log.info("Managed Ollama embedding model={} baseUrl={}", embeddingModel, AppConfig.DEFAULT_OLLAMA_BASE_URL)
        val httpAccessPolicy = UserAccessPolicies.fixed(httpUsers)
        val accessPolicy = UserAccessPolicy { userId ->
            users.isAuthorized(userId) || httpAccessPolicy.isAuthorized(userId)
        }
        val memorySaver = MemoryProposalsPersister(
            batchWriter = repositories.canonicalMemoryBatchWriter,
        )
        val memoryAnalysisService = MemoryAnalysisService(
            memoryExtractor = MemoryExtractorFactory.create(completionClient),
            sourceRecords = repositories.sourceRecords,
            memorySaver = memorySaver,
            accessPolicy = accessPolicy,
            referenceInterpreter = SourceReferenceInterpreterFactory.create(completionClient),
        )
        val topicSearch = TopicSearcher(
            topics = repositories.topicMaintenance,
            queries = QueryEmbeddingAdapter(textEmbedder),
            memories = repositories.canonicalMemories,
            accessPolicy = accessPolicy,
        )
        val describeTopic = TopicDescriber(
            topics = repositories.topicMaintenance,
            memories = repositories.canonicalMemories,
            descriptions = repositories.topicDescriptions,
            generator = TopicDescriptionGeneratorFactory.create(completionClient),
            accessPolicy = accessPolicy,
        )
        val agentTimeoutSeconds = Env[AppConfig.ENV_VAR_AGENT_TIMEOUT_SECONDS]
            ?.toLongOrNull()
            ?: AppConfig.DEFAULT_AGENT_TIMEOUT_SECONDS
        val conversationGateway = AgentClientFactory.conversation(
            backend = agentBackend,
            timeout = Duration.ofSeconds(agentTimeoutSeconds),
        )?.let(::AgentConversationGateway)
        log.info(
            "Agent backend={} conversation={}",
            agentBackend,
            if (conversationGateway == null) "unavailable" else "enabled",
        )
        val memoryConversation = conversationGateway?.let {
            val now = System.currentTimeMillis()
            repositories.memoryConversationSessions.failStaleProcessing(
                before = now - HandleMemoryConversation.SESSION_IDLE_TIMEOUT_MILLIS,
                now = now,
            )
            HandleMemoryConversation(
                sessions = repositories.memoryConversationSessions,
                contextProvider = MemoryConversationContextProvider(topicSearch),
                conversationGateway = it,
            )
        }
        val conversationExpiryWorker = conversationGateway?.let {
            MemoryConversationExpiryWorker(
                ExpireIdleMemoryConversations(
                    sessions = repositories.memoryConversationSessions,
                    conversationGateway = it,
                ),
            )
        } ?: ConversationExpiryWorker.NONE
        return DefaultApplicationServices(
            memoryAnalysis = memoryAnalysisService,
            organizeTopics = organizeTopics,
            organizeMemories = organizeMemories,
            describeTopic = describeTopic,
            memoryConversation = memoryConversation,
            users = users,
            embeddingRuntime = managedEmbedding.runtime,
            conversationExpiryWorker = conversationExpiryWorker,
            topicAssignmentWorker = MemoryTopicAssignmentWorker(organizeMemories),
            kakaoSyncWorker = kakaoSyncWorker(memoryAnalysisService),
            conversationGateway = conversationGateway,
        )
    }

    /** Collects allowlisted chats from the plaintext Kakao local DB; no allowlist means no worker. */
    private fun kakaoSyncWorker(memoryAnalysis: MemoryAnalysis): KakaoSyncWorker {
        val config = KakaoLocalDbConfig.fromEnv() ?: return KakaoSyncWorker.NONE
        log.info("Kakao local DB sync db={} chats={}", config.dbPath, config.chats.size)
        return KakaoLocalDbSyncWorker(
            KakaoLocalDbSync(
                store = KakaoPlainDbStore(config.dbPath),
                memoryAnalysis = memoryAnalysis,
                cursor = KakaoSyncCursor(config.cursorPath),
                config = config,
            ),
        )
    }

    /** Reads [AppConfig.ENV_VAR_AGENT_BACKEND]; an unknown value stops startup instead of silently using the default. */
    private fun agentBackend(): AgentBackend {
        val value = Env[AppConfig.ENV_VAR_AGENT_BACKEND] ?: AppConfig.DEFAULT_AGENT_BACKEND
        return AgentBackend.entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
            ?: error("Unknown ${AppConfig.ENV_VAR_AGENT_BACKEND}: $value (expected one of ${AgentBackend.entries.joinToString { it.name.lowercase() }})")
    }
}
