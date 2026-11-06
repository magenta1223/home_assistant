package com.homeassistant.app.services

import com.homeassistant.application.port.inbound.identity.UserAuthentication
import com.homeassistant.application.port.inbound.identity.UserRegistry
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysis
import com.homeassistant.application.port.inbound.chatservice.ChatService

/** Provides the application's top-level use cases and managed runtimes. */
interface ApplicationServices : AutoCloseable {
    /** Provides the episode-analysis use case. */
    val episodeAnalysis: EpisodeAnalysis

    /** Provides episode-backed conversations when the selected agent backend CLI is available. */
    val chatService: ChatService?

    /** Registered application users that may be selected as knowledge viewers. */
    val users: UserRegistry

    /** Registers people and keeps them logged in with sessions. */
    val authentication: UserAuthentication

    /** Describes a Component per viewer on demand; nothing is generated ahead of a request. */

    /** Reports whether required managed runtimes are available. */
    val isReady: Boolean

    /** Starts managed application runtimes. */
    fun start()
}
