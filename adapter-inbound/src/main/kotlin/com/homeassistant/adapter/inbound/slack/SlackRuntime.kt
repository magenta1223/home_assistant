package com.homeassistant.adapter.inbound.slack

import com.homeassistant.application.memory.io.SearchMemoriesUseCase
import com.homeassistant.application.slackconversation.handle.ConversationTurnClient
import com.homeassistant.application.slackconversation.handle.HandleSlackConversation
import com.homeassistant.application.slackconversation.handle.HouseholdContextProvider
import com.homeassistant.application.slackconversation.handle.SlackCodexSessionStore
import com.homeassistant.application.memory.analysis.MemoryAnalysis
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
    val identityDirectory: SlackIdentityDirectory,
    val maxFileSizeBytes: Long,
) {
    companion object {
        fun fromEnv(): SlackConfig? {
            val appToken = Env[HomeAppConfig.ENV_VAR_SLACK_APP_TOKEN]?.takeIf { it.isNotBlank() }
            val botToken = Env[HomeAppConfig.ENV_VAR_SLACK_BOT_TOKEN]?.takeIf { it.isNotBlank() }
            val teamId = Env[HomeAppConfig.ENV_VAR_SLACK_TEAM_ID]?.takeIf { it.isNotBlank() }
            val mappingsJson = Env[HomeAppConfig.ENV_VAR_SLACK_MEMBER_SCOPES_JSON]
                ?.takeIf { it.isNotBlank() }
            if (appToken == null || botToken == null || teamId == null || mappingsJson == null) return null
            val identityDirectory = runCatching {
                SlackIdentityDirectoryFactory.fromJson(teamId, mappingsJson)
            }.getOrNull() ?: return null

            val maxFileSizeBytes = Env[HomeAppConfig.ENV_VAR_SLACK_MAX_FILE_SIZE_BYTES]
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?: HomeAppConfig.DEFAULT_SLACK_MAX_FILE_SIZE_BYTES

            return SlackConfig(
                appToken = appToken,
                botToken = botToken,
                teamId = teamId,
                identityDirectory = identityDirectory,
                maxFileSizeBytes = maxFileSizeBytes,
            )
        }
    }
}

object SlackRuntimeFactory {
    fun create(
        config: SlackConfig,
        memoryAnalysis: MemoryAnalysis,
        searchMemories: SearchMemoriesUseCase,
        codexSessions: SlackCodexSessionStore,
        conversationClient: ConversationTurnClient?,
    ): SlackRuntime {
        val slackClient = SlackApiClient(config.botToken)
        val executor = Executors.newFixedThreadPool(2)
        val conversationService = createConversationService(
            config,
            searchMemories,
            codexSessions,
            slackClient,
            conversationClient,
        )
        val workflow = SlackKakaoAnalysisWorkflow(
            slackClient = slackClient,
            memoryAnalysis = memoryAnalysis,
            maxFileSizeBytes = config.maxFileSizeBytes,
        )
        val listeners = SlackListeners(
            config = config,
            workflow = workflow,
            executor = executor,
            conversationService = conversationService,
        )
        return SlackSocketRuntime(
            config = config,
            listeners = listeners,
            executor = executor,
        )
    }

    private fun createConversationService(
        config: SlackConfig,
        searchMemories: SearchMemoriesUseCase,
        sessions: SlackCodexSessionStore,
        slackClient: SlackClient,
        conversationClient: ConversationTurnClient?,
    ): SlackConversationService? {
        val client = conversationClient ?: return disabled("configuration missing or invalid")

        val now = System.currentTimeMillis()
        sessions.failStaleProcessing(
            before = now - HandleSlackConversation.SESSION_IDLE_TIMEOUT_MILLIS,
            now = now,
        )
        return SlackConversationService(
            HandleSlackConversation(
                identities = config.identityDirectory,
                sessions = sessions,
                contextProvider = HouseholdContextProvider(searchMemories),
                conversationClient = client,
                answerPublisher = SlackConversationAnswerPublisher(slackClient),
            ),
        )
    }

    private fun disabled(reason: String): SlackConversationService? {
        log.info("Slack conversation disabled: {}", reason)
        return null
    }
}

private val log = LoggerFactory.getLogger(SlackRuntimeFactory::class.java)

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
