package com.homeassistant.adapter.inbound.slack

import com.homeassistant.application.port.input.memory.answer.MemoryAnswerWorkflow
import com.homeassistant.configuration.AppConfig as HomeAppConfig
import com.homeassistant.configuration.Env
import com.slack.api.bolt.App
import com.slack.api.bolt.AppConfig
import com.slack.api.bolt.socket_mode.SocketModeApp
import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class SlackConfig(
    val appToken: String,
    val botToken: String,
    val teamId: String,
    val legacyMemberMappings: List<LegacySlackMemberMapping>,
) {
    companion object {
        fun fromEnv(): SlackConfig? {
            val appToken = Env[HomeAppConfig.ENV_VAR_SLACK_APP_TOKEN]?.takeIf { it.isNotBlank() }
            val botToken = Env[HomeAppConfig.ENV_VAR_SLACK_BOT_TOKEN]?.takeIf { it.isNotBlank() }
            val teamId = Env[HomeAppConfig.ENV_VAR_SLACK_TEAM_ID]?.takeIf { it.isNotBlank() }
            if (appToken == null || botToken == null || teamId == null) return null
            val legacyMemberMappings = Env[HomeAppConfig.ENV_VAR_SLACK_MEMBER_SCOPES_JSON]
                ?.takeIf { it.isNotBlank() }
                ?.let { LegacySlackMemberMappings.fromJson(teamId, it) }
                .orEmpty()

            return SlackConfig(
                appToken = appToken,
                botToken = botToken,
                teamId = teamId,
                legacyMemberMappings = legacyMemberMappings,
            )
        }
    }
}

object SlackRuntimeFactory {
    fun create(
        config: SlackConfig,
        memoryAnswerWorkflow: MemoryAnswerWorkflow,
    ): SlackRuntime {
        val slackClient = SlackApiClient(config.botToken)
        val executor = Executors.newFixedThreadPool(2)
        val memoryAnswerAdapter = SlackMemoryAnswerAdapter(
            configuredTeamId = config.teamId,
            memoryAnswerWorkflow = memoryAnswerWorkflow,
            slack = slackClient,
            executor = executor,
        )
        val listeners = SlackListeners(
            memoryAnswerAdapter = memoryAnswerAdapter,
        )
        return SlackSocketRuntime(
            config = config,
            listeners = listeners,
            executor = executor,
        )
    }

}

/** Owns the lifecycle of the application's Slack event runtime. */
interface SlackRuntime : AutoCloseable {
    /** Starts listening for Slack events without blocking the caller. */
    fun startAsync()
}

internal class SlackSocketRuntime(
    private val config: SlackConfig,
    private val listeners: SlackListeners,
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

        listeners.register(app)

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
