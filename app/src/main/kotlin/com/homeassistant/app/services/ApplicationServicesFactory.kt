package com.homeassistant.app.services

import com.homeassistant.adapter.outbound.conversation.AgentChatGateway
import com.homeassistant.adapter.outbound.identity.Pbkdf2PasswordHasher
import com.homeassistant.adapter.outbound.episodeanalysis.EpisodeExtractorFactory
import com.homeassistant.adapter.outbound.embedding.ollama.ManagedOllamaEmbeddingFactory
import com.homeassistant.application.usecase.memoryextraction.episode.EpisodeAnalysisService
import com.homeassistant.configuration.AppConfig
import com.homeassistant.configuration.Env
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import com.homeassistant.adapter.outbound.reference.SourceReferenceInterpreterFactory
import com.homeassistant.adapter.outbound.componentextraction.ComponentKnowledgeExtractorFactory
import com.homeassistant.application.usecase.chatservice.ChatServiceImpl
import com.homeassistant.application.usecase.chatservice.ExpireIdleChats
import com.homeassistant.application.usecase.contextprovider.ContextProvider
import com.homeassistant.application.usecase.contextprovider.episode.EpisodeSearcher
import com.homeassistant.application.usecase.identity.UserAuthenticationService
import com.homeassistant.application.usecase.identity.UserRegistryService
import com.homeassistant.application.usecase.contextprovider.component.ComponentSearcher
import com.homeassistant.application.usecase.memoryextraction.component.AssignEpisodeToComponents
import com.homeassistant.application.usecase.memoryextraction.component.PrepareEpisodeEmbedding
import com.homeassistant.application.usecase.memoryextraction.group.MergeSmallGroups
import com.homeassistant.application.usecase.memoryextraction.group.OrganizeComponentIntoGroups
import com.homeassistant.application.usecase.memoryextraction.group.PrepareComponentEmbedding
import com.homeassistant.application.usecase.memoryextraction.UpdateMemoryHierarchyService
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
        val ollamaEmbedding = ManagedOllamaEmbeddingFactory.create(model = embeddingModel)
        val episodeEmbeddings = PrepareEpisodeEmbedding(
            ollamaEmbedding,
            repositories.episodeEmbeddings,
        )
        val componentEmbeddings = PrepareComponentEmbedding(
            repositories.components,
            ollamaEmbedding,
            repositories.componentEmbeddings,
        )
        val organizeComponent = OrganizeComponentIntoGroups(
            repositories.groups,
            componentEmbeddings,
            GroupOrganizationPlanner(GroupPlacementPolicy(groupSettings), GroupSplitPolicy(groupSettings)),
        )
        val assignEpisode = AssignEpisodeToComponents(
            repositories.components,
            episodeEmbeddings,
            componentEmbeddings,
            ComponentKnowledgeExtractorFactory.create(completionClient),
        )
        val mergeSmallGroups = MergeSmallGroups(repositories.groups, GroupMergePolicy(groupSettings))
        val updateHierarchy = UpdateMemoryHierarchyService(
            repositories.componentAssignmentOutbox,
            repositories.pendingComponents,
            assignEpisode,
            organizeComponent,
            mergeSmallGroups,
        )
        log.info("Managed Ollama embedding model={} baseUrl={}", embeddingModel, AppConfig.DEFAULT_OLLAMA_BASE_URL)

        val episodeAnalysisService = EpisodeAnalysisService(
            episodeExtractor = EpisodeExtractorFactory.create(completionClient),
            sourceRecords = repositories.sourceRecords,
            episodes = repositories.episodes,
            accessPolicy = users,
            referenceInterpreter = SourceReferenceInterpreterFactory.create(completionClient),
        )
        val componentSearch = ComponentSearcher(
            components = repositories.components,
            groups = repositories.groups,
            queries = ollamaEmbedding,
            episodes = repositories.episodes,
            componentEmbeddings = componentEmbeddings,
            accessPolicy = users,
        )
        val episodeSearch = EpisodeSearcher(repositories.episodes, ollamaEmbedding, episodeEmbeddings, users)
        val agentTimeoutSeconds = Env[AppConfig.ENV_VAR_AGENT_TIMEOUT_SECONDS]
            ?.toLongOrNull()
            ?: AppConfig.DEFAULT_AGENT_TIMEOUT_SECONDS
        val conversationGateway = AgentClientFactory.conversation(
            backend = agentBackend,
            timeout = Duration.ofSeconds(agentTimeoutSeconds),
        )?.let(::AgentChatGateway)
        log.info(
            "Agent backend={} conversation={}",
            agentBackend,
            if (conversationGateway == null) "unavailable" else "enabled",
        )
        val episodeConversation = conversationGateway?.let {
            val now = System.currentTimeMillis()
            repositories.episodeConversationSessions.failStaleProcessing(
                before = now - ChatServiceImpl.SESSION_IDLE_TIMEOUT_MILLIS,
                now = now,
            )
            ChatServiceImpl(
                sessions = repositories.episodeConversationSessions,
                contextProvider = ContextProvider(componentSearch, episodeSearch),
                chatGateway = it,
            )
        }
        val conversationExpiryWorker = conversationGateway?.let {
            EpisodeConversationExpiryWorker(
                ExpireIdleChats(
                    sessions = repositories.episodeConversationSessions,
                    chatGateway = it,
                ),
            )
        } ?: ConversationExpiryWorker.NONE
        return DefaultApplicationServices(
            episodeAnalysis = episodeAnalysisService,
            chatService = episodeConversation,
            users = users,
            authentication = UserAuthenticationService(
                repositories.userCredentials,
                repositories.userSessions,
                Pbkdf2PasswordHasher(),
            ),
            embeddingRuntime = ollamaEmbedding,
            conversationExpiryWorker = conversationExpiryWorker,
            componentAssignmentWorker = EpisodeComponentAssignmentWorker(updateHierarchy),
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
