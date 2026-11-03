package com.homeassistant.app.services

import com.homeassistant.adapter.outbound.embedding.ollama.EmbeddingServerRuntime
import com.homeassistant.application.port.inbound.identity.UserAuthentication
import com.homeassistant.application.port.inbound.identity.UserRegistry
import com.homeassistant.application.port.inbound.group.OrganizePendingComponents
import com.homeassistant.application.port.inbound.episode.EpisodeAnalysis
import com.homeassistant.application.port.inbound.episode.Conversation
import com.homeassistant.application.port.inbound.component.OrganizePendingEpisodes

internal class DefaultApplicationServices(
    override val episodeAnalysis: EpisodeAnalysis,
    override val organizeComponents: OrganizePendingComponents,
    override val organizeEpisodes: OrganizePendingEpisodes,
    override val conversation: Conversation? = null,
    override val users: UserRegistry = UserRegistry.NONE,
    override val authentication: UserAuthentication = UserAuthentication.NONE,
    private val embeddingRuntime: EmbeddingServerRuntime,
    private val conversationExpiryWorker: ConversationExpiryWorker = ConversationExpiryWorker.NONE,
    private val componentAssignmentWorker: ComponentAssignmentWorker = ComponentAssignmentWorker.NONE,
    private val conversationGateway: AutoCloseable? = null,
) : ApplicationServices {
    override val isReady: Boolean
        get() = embeddingRuntime.isReady

    override fun start() {
        embeddingRuntime.start()
        componentAssignmentWorker.start()
        conversationExpiryWorker.start()
    }

    override fun close() {
        try {
            conversationExpiryWorker.close()
        } finally {
            try {
                componentAssignmentWorker.close()
            } finally {
                try {
                    conversationGateway?.close()
                } finally {
                    embeddingRuntime.close()
                }
            }
        }
    }
}
