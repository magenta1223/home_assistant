package com.homeassistant.app.services

import com.homeassistant.adapter.inbound.slack.SlackRuntime
import com.homeassistant.application.port.input.identity.HouseholdMembers
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysis

/** Provides the application's top-level use cases and managed runtimes. */
interface ApplicationServices : AutoCloseable {
    /** Provides the memory-analysis use case. */
    val memoryAnalysis: MemoryAnalysis

    /** Provides the optional Slack runtime when Slack is configured. */
    val slackRuntime: SlackRuntime?

    /** Registered Slack members that may be selected as knowledge viewers. */
    val householdMembers: HouseholdMembers

    /** Reports whether required managed runtimes are available. */
    val isReady: Boolean

    /** Starts managed application runtimes. */
    fun start()
}
