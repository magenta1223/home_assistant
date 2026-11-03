package com.homeassistant.app.services

import com.homeassistant.application.port.inbound.identity.UserAuthentication
import com.homeassistant.application.port.inbound.identity.UserRegistry
import com.homeassistant.application.port.inbound.group.OrganizePendingComponents
import com.homeassistant.application.port.inbound.episode.analysis.EpisodeAnalysis
import com.homeassistant.application.port.inbound.episode.conversation.EpisodeConversation
import com.homeassistant.application.port.inbound.component.OrganizePendingEpisodes

/** Provides the application's top-level use cases and managed runtimes. */
interface ApplicationServices : AutoCloseable {
    /** Provides the episode-analysis use case. */
    val episodeAnalysis: EpisodeAnalysis

    /** Provides episode-backed conversations when the selected agent backend CLI is available. */
    val episodeConversation: EpisodeConversation?

    /** Registered application users that may be selected as knowledge viewers. */
    val users: UserRegistry

    /** Registers people and keeps them logged in with sessions. */
    val authentication: UserAuthentication

    /** Explicit trusted maintenance; starting the application does not run this use case. */
    val organizeComponents: OrganizePendingComponents

    /** Episode → Component assignment from the outbox, then Component → Group; the started worker runs it periodically. */
    val organizeEpisodes: OrganizePendingEpisodes

    /** Describes a Component per viewer on demand; nothing is generated ahead of a request. */

    /** Reports whether required managed runtimes are available. */
    val isReady: Boolean

    /** Starts managed application runtimes. */
    fun start()
}
