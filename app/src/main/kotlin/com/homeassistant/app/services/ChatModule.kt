package com.homeassistant.app.services

import com.homeassistant.adapter.inbound.scheduler.IdleChatExpiryWorker
import com.homeassistant.adapter.outbound.conversation.AgentChatGateway
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryStores
import com.homeassistant.application.port.inbound.chatservice.Chat
import com.homeassistant.application.port.inbound.contexttool.ContextAccess
import com.homeassistant.application.port.inbound.contexttool.FindTransactions
import com.homeassistant.application.port.inbound.contexttool.SearchMemory
import com.homeassistant.application.port.outbound.embedding.EmbeddingGenerator
import com.homeassistant.application.usecase.chatservice.ChatContextDelivery
import com.homeassistant.application.usecase.chatservice.ChatService
import com.homeassistant.application.usecase.chatservice.ExpireIdleChatsService
import com.homeassistant.application.usecase.contextprovider.ContextAssembler
import com.homeassistant.application.usecase.contextprovider.MemoryContextProvider
import com.homeassistant.application.usecase.contextprovider.component.ComponentSearcher
import com.homeassistant.application.usecase.contexttool.ContextGrants
import com.homeassistant.application.usecase.contexttool.FindTransactionsService
import com.homeassistant.application.usecase.contexttool.SearchMemoryService
import com.homeassistant.application.usecase.contextprovider.episode.EpisodeSearcher
import com.homeassistant.application.usecase.embedding.PrepareEpisodeEmbedding
import com.homeassistant.application.usecase.embedding.PrepareComponentEmbedding
import com.homeassistant.configuration.AppConfig
import com.homeassistant.configuration.Env
import com.homeassistant.domain.identity.UserAccessPolicy
import com.homeassistant.infrastructure.runtime.Runtime
import com.homeassistant.llm.AgentBackend
import com.homeassistant.llm.AgentClientFactory
import org.slf4j.LoggerFactory
import java.time.Duration

/** Assembles memory-backed conversations on the [backend]'s chat client, when the backend offers one. */
internal class ChatModule(
    backend: AgentBackend,
    private val repositories: RepositoryStores,
    private val queries: EmbeddingGenerator,
    private val episodeEmbeddings: PrepareEpisodeEmbedding,
    private val componentEmbeddings: PrepareComponentEmbedding,
    private val accessPolicy: UserAccessPolicy,
) {
    private val chatClient = AgentClientFactory.conversation(backend = backend, timeout = agentTimeout())
    private val chatGateway = chatClient?.let { AgentChatGateway(it, CONTEXT_TOOL_URL) }
    private val memory = ContextAssembler(listOf(MemoryContextProvider(componentSearcher(), episodeSearcher())))
    private val contextGrants = ContextGrants(agentTimeout())
    private val delivery = contextDelivery(backend)

    /** Memory-backed conversations, or null when the backend offers no chat client. */
    val chat: Chat? = chatGateway?.let(::memoryBackedChat)

    /** Whose context an agent's context token reads, or null when chats receive their context up front. */
    val contextAccess: ContextAccess? = (delivery as? ChatContextDelivery.OnRequest)?.grants

    /** The memory search agents call during a turn, or null when chats receive their context up front. */
    val searchMemory: SearchMemory? = contextAccess?.let { SearchMemoryService(memory) }

    /** The transaction search agents call during a turn, or null when chats receive their context up front. */
    val findTransactions: FindTransactions? =
        contextAccess?.let { FindTransactionsService(repositories.financeTransactions) }

    /** The chat client and the worker that expires idle chats. */
    val runtimes: List<Runtime> = listOfNotNull(chatClient, chatGateway?.let(::expiryWorker))

    init {
        LoggerFactory.getLogger(ChatModule::class.java).info(
            "Agent backend={} conversation={} contextDelivery={}",
            backend,
            if (chatGateway == null) "unavailable" else "enabled",
            delivery.javaClass.simpleName,
        )
    }

    /** Serves chats with memory context. */
    private fun memoryBackedChat(gateway: AgentChatGateway): Chat =
        ChatService(
            sessions = repositories.chatSessions,
            contextDelivery = delivery,
            chatGateway = gateway,
        )

    private fun componentSearcher() = ComponentSearcher(
        components = repositories.components,
        groups = repositories.groups,
        queries = queries,
        episodes = repositories.episodes,
        componentEmbeddings = componentEmbeddings,
        accessPolicy = accessPolicy,
    )

    private fun episodeSearcher() = EpisodeSearcher(repositories.episodes, queries, episodeEmbeddings, accessPolicy)

    private fun expiryWorker(gateway: AgentChatGateway) = IdleChatExpiryWorker(
        ExpireIdleChatsService(sessions = repositories.chatSessions, chatGateway = gateway),
    )

    /**
     * Claude looks context up itself with the context tools; Codex cannot call them, so its chats receive the
     * retrieved reference with each question.
     */
    private fun contextDelivery(backend: AgentBackend): ChatContextDelivery = when (backend) {
        AgentBackend.CLAUDE -> ChatContextDelivery.OnRequest(contextGrants)
        AgentBackend.CODEX -> ChatContextDelivery.Prefetch(memory)
    }

    private fun agentTimeout(): Duration = Duration.ofSeconds(
        Env[AppConfig.ENV_VAR_AGENT_TIMEOUT_SECONDS]?.toLongOrNull() ?: AppConfig.DEFAULT_AGENT_TIMEOUT_SECONDS,
    )

    private companion object {
        const val CONTEXT_TOOL_URL =
            "http://${AppConfig.DEFAULT_HOST}:${AppConfig.DEFAULT_PORT}${AppConfig.ROUTE_CONTEXT_TOOLS}"
    }
}
