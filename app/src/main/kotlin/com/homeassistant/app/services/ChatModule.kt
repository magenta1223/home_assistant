package com.homeassistant.app.services

import com.homeassistant.adapter.outbound.conversation.AgentChatGateway
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryStores
import com.homeassistant.application.port.inbound.chatservice.ChatService
import com.homeassistant.application.port.outbound.embedding.EmbeddingGenerator
import com.homeassistant.application.usecase.chatservice.ChatServiceImpl
import com.homeassistant.application.usecase.chatservice.ExpireIdleChats
import com.homeassistant.application.usecase.contextprovider.ContextAssembler
import com.homeassistant.application.usecase.contextprovider.MemoryContextProvider
import com.homeassistant.application.usecase.contextprovider.component.ComponentSearcher
import com.homeassistant.application.usecase.contextprovider.episode.EpisodeSearcher
import com.homeassistant.application.usecase.memoryextraction.component.PrepareEpisodeEmbedding
import com.homeassistant.application.usecase.memoryextraction.group.PrepareComponentEmbedding
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
    private val chatGateway = chatClient?.let(::AgentChatGateway)

    /** Memory-backed conversations, or null when the backend offers no chat client. */
    val chatService: ChatService? = chatGateway?.let(::memoryBackedChat)

    /** The chat client and the worker that expires idle chats. */
    val runtimes: List<Runtime> = listOfNotNull(chatClient, chatGateway?.let(::expiryWorker))

    init {
        LoggerFactory.getLogger(ChatModule::class.java).info(
            "Agent backend={} conversation={}",
            backend,
            if (chatGateway == null) "unavailable" else "enabled",
        )
    }

    /** Fails chats a previous run left processing, then serves chats with memory context. */
    private fun memoryBackedChat(gateway: AgentChatGateway): ChatService {
        val now = System.currentTimeMillis()
        repositories.episodeConversationSessions.failStaleProcessing(
            before = now - ChatServiceImpl.SESSION_IDLE_TIMEOUT_MILLIS,
            now = now,
        )
        return ChatServiceImpl(
            sessions = repositories.episodeConversationSessions,
            contextProvider = ContextAssembler(listOf(MemoryContextProvider(componentSearcher(), episodeSearcher()))),
            chatGateway = gateway,
        )
    }

    private fun componentSearcher() = ComponentSearcher(
        components = repositories.components,
        groups = repositories.groups,
        queries = queries,
        episodes = repositories.episodes,
        componentEmbeddings = componentEmbeddings,
        accessPolicy = accessPolicy,
    )

    private fun episodeSearcher() = EpisodeSearcher(repositories.episodes, queries, episodeEmbeddings, accessPolicy)

    private fun expiryWorker(gateway: AgentChatGateway) = ChatClientExpiryWorker(
        ExpireIdleChats(sessions = repositories.episodeConversationSessions, chatGateway = gateway),
    )

    private fun agentTimeout(): Duration = Duration.ofSeconds(
        Env[AppConfig.ENV_VAR_AGENT_TIMEOUT_SECONDS]?.toLongOrNull() ?: AppConfig.DEFAULT_AGENT_TIMEOUT_SECONDS,
    )
}
