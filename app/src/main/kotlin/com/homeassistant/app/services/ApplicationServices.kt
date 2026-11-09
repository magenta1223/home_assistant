package com.homeassistant.app.services

import com.homeassistant.application.port.inbound.identity.UserAuthentication
import com.homeassistant.application.port.inbound.identity.UserRegistry
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysis
import com.homeassistant.application.port.inbound.chatservice.Chat
import com.homeassistant.application.port.inbound.finance.ImportFinanceTransactions
import com.homeassistant.infrastructure.runtime.Runtime
import com.homeassistant.infrastructure.runtime.RuntimeState

/** Serves the application's top-level use cases while [runtimes] run: they start in order and stop in reverse order. */
class ApplicationServices internal constructor(
    /** The episode-analysis use case. */
    val episodeAnalysis: EpisodeAnalysis,
    /** Episode-backed conversations, or null when the selected agent backend CLI is unavailable. */
    val chat: Chat? = null,
    /** Registered application users that may be selected as knowledge viewers. */
    val users: UserRegistry = UserRegistry.NONE,
    /** Registers people and keeps them logged in with sessions. */
    val authentication: UserAuthentication = UserAuthentication.NONE,
    /** Imports household finance ledgers. */
    val importFinanceTransactions: ImportFinanceTransactions? = null,
    private val runtimes: List<Runtime>,
) : AutoCloseable {
    /** Whether every managed runtime is running. */
    val isReady: Boolean
        get() = runtimes.all { it.state == RuntimeState.RUNNING }

    /** Starts the managed runtimes in order. */
    fun start() {
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
