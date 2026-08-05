package com.homeassistant.adapter.inbound.slack

import com.slack.api.bolt.App
import com.slack.api.bolt.AppConfig
import com.slack.api.bolt.socket_mode.SocketModeApp
import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutorService

interface SlackRuntime : AutoCloseable {
    fun startAsync()
}

internal class SlackSocketRuntime(
    private val config: SlackConfig,
    private val listeners: List<SlackListenerRegistrar>,
    private val executor: ExecutorService,
) : SlackRuntime {
    private val log = LoggerFactory.getLogger(javaClass)
    private val socketModeApp: SocketModeApp

    init {
        val app = App(
            AppConfig.builder()
                .singleTeamBotToken(config.botToken)
                .build(),
        )

        listeners.forEach { it.register(app) }

        socketModeApp = SocketModeApp(config.appToken, app)
    }

    override fun startAsync() {
        socketModeApp.startAsync()
        log.info("Slack Socket Mode runtime started")
    }

    override fun close() {
        runCatching { socketModeApp.close() }
            .onFailure { log.warn("Failed to close Slack Socket Mode runtime: ${it.message}") }
        executor.shutdown()
    }
}
