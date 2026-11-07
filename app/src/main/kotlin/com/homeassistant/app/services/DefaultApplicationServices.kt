package com.homeassistant.app.services

import com.homeassistant.application.port.inbound.identity.UserAuthentication
import com.homeassistant.application.port.inbound.identity.UserRegistry
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysis
import com.homeassistant.application.port.inbound.chatservice.ChatService
import com.homeassistant.infrastructure.runtime.Runtime
import com.homeassistant.infrastructure.runtime.RuntimeState

/** Serves the use cases while [runtimes] run: they start in order and stop in reverse order. */
internal class DefaultApplicationServices(
    override val episodeAnalysis: EpisodeAnalysis,
    override val chatService: ChatService? = null,
    override val users: UserRegistry = UserRegistry.NONE,
    override val authentication: UserAuthentication = UserAuthentication.NONE,
    private val runtimes: List<Runtime>,
) : ApplicationServices {
    override val isReady: Boolean
        get() = runtimes.all { it.state == RuntimeState.RUNNING }

    override fun start() {
        runtimes.forEach(Runtime::start)
    }

    /** Stops every runtime even when one fails, then throws the first failure with the others suppressed. */
    override fun close() {
        val failures = runtimes.asReversed().mapNotNull { runCatching(it::stop).exceptionOrNull() }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }
}
