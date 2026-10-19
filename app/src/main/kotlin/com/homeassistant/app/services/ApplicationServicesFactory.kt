package com.homeassistant.app.services

import com.homeassistant.adapter.outbound.memoryanalysis.MemoryExtractorFactory
import com.homeassistant.adapter.outbound.embedding.ollama.ManagedOllamaEmbeddingFactory
import com.homeassistant.adapter.outbound.memoryconversation.ConversationGatewayFactory
import com.homeassistant.adapter.outbound.vector.qdrant.ManagedQdrantVectorStoreFactory
import com.homeassistant.application.usecase.memory.answer.MemoryAnswerContextProvider
import com.homeassistant.application.usecase.memory.analysis.MemoryAnalysisService
import com.homeassistant.configuration.AppConfig
import com.homeassistant.configuration.Env
import com.homeassistant.domain.identity.UserAccessPolicies
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.adapter.outbound.vector.memory.SemanticMemoryIndexSearcherFactory
import com.homeassistant.adapter.outbound.vector.memory.SemanticMemoryIndexWriterFactory
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import com.homeassistant.adapter.outbound.reference.SourceReferenceInterpreterFactory
import com.homeassistant.application.usecase.memory.search.MemorySearcher
import com.homeassistant.application.usecase.memory.write.MemoryProposalsPersister
import com.homeassistant.application.usecase.memory.write.MemoryIndexingOutboxProcessor
import com.homeassistant.application.usecase.memory.conversation.HandleMemoryConversation
import com.homeassistant.application.usecase.memory.conversation.ExpireIdleMemoryConversations
import com.homeassistant.application.usecase.memory.conversation.MemoryConversationContextProvider
import com.homeassistant.application.usecase.identity.UserRegistryService
import com.homeassistant.adapter.outbound.embedding.memory.MemoryEmbeddingAdapter
import com.homeassistant.adapter.outbound.embedding.topic.TopicEmbeddingAdapter
import com.homeassistant.adapter.outbound.topicassignment.TopicAssignmentGeneratorFactory
import com.homeassistant.application.usecase.topic.*
import com.homeassistant.application.usecase.group.*
import com.homeassistant.domain.group.*
import org.slf4j.LoggerFactory
import java.time.Duration


object ApplicationServicesFactory {
    private val log = LoggerFactory.getLogger(ApplicationServicesFactory::class.java)

    fun create(
        dbPath: String,
        httpUsers: Collection<UserId> = emptyList(),
        groupSettings: GroupPolicySettings = GroupPolicySettings(),
    ): ApplicationServices {
        val repositories = RepositoryFactory.create(dbPath)
        val users = UserRegistryService(repositories.users)
        val embeddingModel = Env[AppConfig.ENV_VAR_EMBEDDING_MODEL]
            ?: AppConfig.DEFAULT_EMBEDDING_MODEL_NAME
        val managedEmbedding = ManagedOllamaEmbeddingFactory.create(model = embeddingModel)
        val textEmbedder = managedEmbedding.embedder
        val topicEmbeddings = PrepareTopicEmbedding(TopicEmbeddingAdapter(textEmbedder, embeddingModel), repositories.topicEmbeddings)
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
            repositories.pendingMemories,
            AssignMemoryToTopics(
                repositories.topicCatalog,
                PrepareMemoryEmbedding(MemoryEmbeddingAdapter(textEmbedder, embeddingModel), repositories.memoryEmbeddings),
                topicEmbeddings,
                TopicAssignmentGeneratorFactory.create(),
                repositories.topicMaintenance,
            ),
            RefreshGroupedTopicEmbeddings(repositories.groupSnapshots, repositories.groupChanges, topicEmbeddings),
            organizeTopics,
        )
        log.info("Managed Ollama embedding model={} baseUrl={}", embeddingModel, AppConfig.DEFAULT_OLLAMA_BASE_URL)
        val managedVectorStore = ManagedQdrantVectorStoreFactory.create(
            baseUrl = Env[AppConfig.ENV_VAR_QDRANT_URL] ?: AppConfig.DEFAULT_QDRANT_URL,
            collection = Env[AppConfig.ENV_VAR_QDRANT_COLLECTION] ?: AppConfig.DEFAULT_QDRANT_COLLECTION,
        )
        val vectorStore = managedVectorStore.store
        val memoryIndexWriter = SemanticMemoryIndexWriterFactory.create(textEmbedder, vectorStore)
        val memoryIndexing = MemoryIndexingOutboxProcessor(
            outbox = repositories.memoryIndexingOutbox,
            indexWriter = memoryIndexWriter,
        )
        val semanticMemoryIndexSearcher = SemanticMemoryIndexSearcherFactory.create(textEmbedder, vectorStore)
        val httpAccessPolicy = UserAccessPolicies.fixed(httpUsers)
        val accessPolicy = UserAccessPolicy { userId ->
            users.isAuthorized(userId) || httpAccessPolicy.isAuthorized(userId)
        }
        val memorySaver = MemoryProposalsPersister(
            batchWriter = repositories.canonicalMemoryBatchWriter,
        )
        val memoryAnalysisService = MemoryAnalysisService(
            memoryExtractor = MemoryExtractorFactory.create(),
            sourceRecords = repositories.sourceRecords,
            memorySaver = memorySaver,
            accessPolicy = accessPolicy,
            memoryIndexing = memoryIndexing,
            referenceInterpreter = SourceReferenceInterpreterFactory.create(),
        )
        val memorySearcherImpl = MemorySearcher(
            memories = repositories.canonicalMemories,
            accessPolicy = accessPolicy,
            searcher = semanticMemoryIndexSearcher,
        )
        val answerContext = MemoryAnswerContextProvider(
            memorySearcher = memorySearcherImpl,
        )
        val codexTimeoutSeconds = Env[AppConfig.ENV_VAR_CODEX_TIMEOUT_SECONDS]
            ?.toLongOrNull()
            ?: AppConfig.DEFAULT_CODEX_TIMEOUT_SECONDS
        val conversationGateway = ConversationGatewayFactory.create(
            timeout = Duration.ofSeconds(codexTimeoutSeconds),
        )
        val memoryConversation = conversationGateway?.let {
            val now = System.currentTimeMillis()
            repositories.memoryConversationSessions.failStaleProcessing(
                before = now - HandleMemoryConversation.SESSION_IDLE_TIMEOUT_MILLIS,
                now = now,
            )
            HandleMemoryConversation(
                sessions = repositories.memoryConversationSessions,
                contextProvider = MemoryConversationContextProvider(answerContext),
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
            memoryConversation = memoryConversation,
            users = users,
            vectorRuntime = managedVectorStore.runtime,
            embeddingRuntime = managedEmbedding.runtime,
            indexingWorker = MemoryIndexingWorker(memoryIndexing),
            conversationExpiryWorker = conversationExpiryWorker,
            conversationGateway = conversationGateway,
        )
    }
}
