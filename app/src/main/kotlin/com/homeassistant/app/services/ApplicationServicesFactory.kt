package com.homeassistant.app.services

import com.homeassistant.adapter.inbound.scheduler.UpdateMemoryWorker
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import com.homeassistant.configuration.AppConfig
import com.homeassistant.configuration.Env
import com.homeassistant.domain.memory.xmemory.group.GroupPolicySettings
import com.homeassistant.llm.AgentBackend
import com.homeassistant.llm.AgentClientFactory

/** Composition root: builds each feature module once and hands their use cases and runtimes to [ApplicationServices]. */
object ApplicationServicesFactory {
    fun create(
        dbPath: String,
        groupSettings: GroupPolicySettings = GroupPolicySettings(),
    ): ApplicationServices {
        val agentBackend = agentBackend()
        val completionClient = AgentClientFactory.completion(agentBackend)
        val repositories = RepositoryFactory.create(dbPath)
        val identity = IdentityModule(repositories)
        val embeddings = EmbeddingModule()
        val sourceImport = SourceImportModule(repositories, completionClient, identity.users)
        val memory = when (memoryMethod()) {
            MemoryMethod.XMEMORY ->
                XMemoryModule(repositories, embeddings.generator, completionClient, identity.users, groupSettings)
        }
        val chat = ChatModule(backend = agentBackend, repositories = repositories, memoryContext = memory.context)
        val finance = FinanceModule(repositories)
        return ApplicationServices(
            sourceImport = sourceImport.sourceImport,
            chat = chat.chat,
            users = identity.users,
            authentication = identity.authentication,
            households = identity.households,
            importFinanceTransactions = finance.importTransactions,
            findMonthlySpending = finance.findMonthlySpending,
            paymentMethodSettings = finance.paymentMethodSettings,
            contextAccess = chat.contextAccess,
            searchMemory = chat.searchMemory,
            findTransactions = chat.findTransactions,
            runtimes = embeddings.runtimes + sourceImport.runtimes + UpdateMemoryWorker(memory.updateMemory) + chat.runtimes,
        )
    }

    /** Reads [AppConfig.ENV_VAR_AGENT_BACKEND]; an unknown value stops startup instead of silently using the default. */
    private fun agentBackend(): AgentBackend {
        val value = Env[AppConfig.ENV_VAR_AGENT_BACKEND] ?: AppConfig.DEFAULT_AGENT_BACKEND
        return AgentBackend.entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
            ?: error("Unknown ${AppConfig.ENV_VAR_AGENT_BACKEND}: $value (expected one of ${AgentBackend.entries.joinToString { it.name.lowercase() }})")
    }

    /** Reads [AppConfig.ENV_VAR_MEMORY_METHOD]; an unknown value stops startup instead of silently using the default. */
    private fun memoryMethod(): MemoryMethod {
        val value = Env[AppConfig.ENV_VAR_MEMORY_METHOD] ?: AppConfig.DEFAULT_MEMORY_METHOD
        return MemoryMethod.entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
            ?: error("Unknown ${AppConfig.ENV_VAR_MEMORY_METHOD}: $value (expected one of ${MemoryMethod.entries.joinToString { it.name.lowercase() }})")
    }
}
