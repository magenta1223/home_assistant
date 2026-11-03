package com.homeassistant.app.services

import com.homeassistant.adapter.outbound.conversation.AgentConversationGateway
import com.homeassistant.adapter.outbound.identity.Pbkdf2PasswordHasher
import com.homeassistant.adapter.outbound.episodeanalysis.EpisodeExtractorFactory
import com.homeassistant.adapter.outbound.embedding.ollama.ManagedOllamaEmbeddingFactory
import com.homeassistant.application.usecase.episode.analysis.EpisodeAnalysisService
import com.homeassistant.configuration.AppConfig
import com.homeassistant.configuration.Env
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import com.homeassistant.adapter.outbound.reference.SourceReferenceInterpreterFactory
import com.homeassistant.adapter.outbound.componentextraction.ComponentKnowledgeExtractorFactory
import com.homeassistant.application.usecase.episode.conversation.HandleEpisodeConversation
import com.homeassistant.application.usecase.episode.conversation.ExpireIdleEpisodeConversations
import com.homeassistant.application.usecase.episode.conversation.EpisodeConversationContextProvider
import com.homeassistant.application.usecase.episode.search.EpisodeSearcher
import com.homeassistant.application.usecase.identity.UserAuthenticationService
import com.homeassistant.application.usecase.identity.UserRegistryService
import com.homeassistant.adapter.outbound.embedding.episode.EpisodeEmbeddingAdapter
import com.homeassistant.adapter.outbound.embedding.episode.QueryEmbeddingAdapter
import com.homeassistant.application.usecase.component.*
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
        val passageEmbedder = EpisodeEmbeddingAdapter(textEmbedder, embeddingModel, managedEmbedding.runtime)
        val episodeEmbeddings = PrepareEpisodeEmbedding(
            passageEmbedder,
            repositories.episodeEmbeddings,
        )
        val componentEmbeddings = PrepareComponentEmbedding(
            ComponentStatementVectors(repositories.componentMaintenance, passageEmbedder),
            repositories.componentEmbeddings,
        )
        val organizeComponents = OrganizePendingComponentsService(
            repositories.pendingComponents,
            OrganizeComponent(
                repositories.groupSnapshots,
                repositories.groupChanges,
                componentEmbeddings,
                GroupOrganizationPlanner(GroupPlacementPolicy(groupSettings), GroupSplitPolicy(groupSettings)),
            ),
        )
        val organizeEpisodes = OrganizePendingEpisodesService(
            repositories.componentAssignmentOutbox,
            ExtractComponents(
                repositories.componentMaintenance,
                episodeEmbeddings,
                componentEmbeddings,
                ComponentKnowledgeExtractorFactory.create(completionClient),
            ),
            organizeComponents,
            MergeSmallGroups(repositories.groupSnapshots, repositories.groupChanges, GroupMergePolicy(groupSettings)),
        )
        log.info("Managed Ollama embedding model={} baseUrl={}", embeddingModel, AppConfig.DEFAULT_OLLAMA_BASE_URL)

        val episodeAnalysisService = EpisodeAnalysisService(
            episodeExtractor = EpisodeExtractorFactory.create(completionClient),
            sourceRecords = repositories.sourceRecords,
            episodeWriter = repositories.episodeWriter,
            accessPolicy = users,
            referenceInterpreter = SourceReferenceInterpreterFactory.create(completionClient),
        )
        val queryEmbedder = QueryEmbeddingAdapter(textEmbedder)
        val componentSearch = ComponentSearcher(
            components = repositories.componentMaintenance,
            groups = repositories.groups,
            queries = queryEmbedder,
            episodes = repositories.canonicalEpisodes,
            componentEmbeddings = componentEmbeddings,
            accessPolicy = users,
        )
        val episodeSearch = EpisodeSearcher(repositories.canonicalEpisodes, queryEmbedder, episodeEmbeddings, users)
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
        val episodeConversation = conversationGateway?.let {
            val now = System.currentTimeMillis()
            repositories.episodeConversationSessions.failStaleProcessing(
                before = now - HandleEpisodeConversation.SESSION_IDLE_TIMEOUT_MILLIS,
                now = now,
            )
            HandleEpisodeConversation(
                sessions = repositories.episodeConversationSessions,
                contextProvider = EpisodeConversationContextProvider(componentSearch, episodeSearch),
                conversationGateway = it,
            )
        }
        val conversationExpiryWorker = conversationGateway?.let {
            EpisodeConversationExpiryWorker(
                ExpireIdleEpisodeConversations(
                    sessions = repositories.episodeConversationSessions,
                    conversationGateway = it,
                ),
            )
        } ?: ConversationExpiryWorker.NONE
        return DefaultApplicationServices(
            episodeAnalysis = episodeAnalysisService,
            organizeComponents = organizeComponents,
            organizeEpisodes = organizeEpisodes,
            episodeConversation = episodeConversation,
            users = users,
            authentication = UserAuthenticationService(
                repositories.userCredentials,
                repositories.userSessions,
                Pbkdf2PasswordHasher(),
            ),
            embeddingRuntime = managedEmbedding.runtime,
            conversationExpiryWorker = conversationExpiryWorker,
            componentAssignmentWorker = EpisodeComponentAssignmentWorker(organizeEpisodes),
            conversationGateway = conversationGateway,
        )
    }

    /** Reads [AppConfig.ENV_VAR_AGENT_BACKEND]; an unknown value stops startup instead of silently using the default. */
    private fun agentBackend(): AgentBackend {
        val value = Env[AppConfig.ENV_VAR_AGENT_BACKEND] ?: AppConfig.DEFAULT_AGENT_BACKEND
        return AgentBackend.entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
            ?: error("Unknown ${AppConfig.ENV_VAR_AGENT_BACKEND}: $value (expected one of ${AgentBackend.entries.joinToString { it.name.lowercase() }})")
    }
}
