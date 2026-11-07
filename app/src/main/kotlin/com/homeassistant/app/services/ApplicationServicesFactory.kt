package com.homeassistant.app.services

import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import com.homeassistant.configuration.AppConfig
import com.homeassistant.configuration.Env
import com.homeassistant.domain.group.GroupPolicySettings
import com.homeassistant.llm.AgentBackend
import com.homeassistant.llm.AgentClientFactory

/** Composition root: builds each feature module once and hands their use cases and runtimes to [ApplicationServices]. */
object ApplicationServicesFactory {
    fun create(
        dbPath: String,
        groupSettings: GroupPolicySettings = GroupPolicySettings(),
    ): ApplicationServices {
        val agentBackend = agentBackend()
        val repositories = RepositoryFactory.create(dbPath)
        val identity = IdentityModule(repositories)
        val embeddings = EmbeddingModule(repositories)
        val memoryExtraction = MemoryExtractionModule(
            repositories = repositories,
            episodeEmbeddings = embeddings.episodeEmbeddings,
            componentEmbeddings = embeddings.componentEmbeddings,
            completionClient = AgentClientFactory.completion(agentBackend),
            accessPolicy = identity.users,
            groupSettings = groupSettings,
        )
        val chat = ChatModule(
            backend = agentBackend,
            repositories = repositories,
            queries = embeddings.queries,
            episodeEmbeddings = embeddings.episodeEmbeddings,
            componentEmbeddings = embeddings.componentEmbeddings,
            accessPolicy = identity.users,
        )
        return ApplicationServices(
            episodeAnalysis = memoryExtraction.episodeAnalysis,
            chatService = chat.chatService,
            users = identity.users,
            authentication = identity.authentication,
            runtimes = embeddings.runtimes + memoryExtraction.runtimes + chat.runtimes,
        )
    }

    /** Reads [AppConfig.ENV_VAR_AGENT_BACKEND]; an unknown value stops startup instead of silently using the default. */
    private fun agentBackend(): AgentBackend {
        val value = Env[AppConfig.ENV_VAR_AGENT_BACKEND] ?: AppConfig.DEFAULT_AGENT_BACKEND
        return AgentBackend.entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
            ?: error("Unknown ${AppConfig.ENV_VAR_AGENT_BACKEND}: $value (expected one of ${AgentBackend.entries.joinToString { it.name.lowercase() }})")
    }
}
