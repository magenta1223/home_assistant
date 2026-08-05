package com.homeassistant.adapter.inbound.slack

import com.homeassistant.domain.slackconversation.SlackCodexSessionStore
import com.homeassistant.domain.topicanswer.TopicAnswerUseCase
import com.homeassistant.application.topicanalysis.TopicAnalysisUseCase
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors

object SlackRuntimeFactory {
    fun create(
        config: SlackConfig,
        topicAnalysis: TopicAnalysisUseCase,
        topicAnswer: TopicAnswerUseCase,
        codexSessions: SlackCodexSessionStore,
    ): SlackRuntime {
        val slackClient = SlackClientFactory.create(config.botToken)
        val reviewSessions = SlackTopicReviewSessionStoreFactory.inMemory()
        val executor = Executors.newFixedThreadPool(2)
        val conversationListeners = createConversationListeners(
            config,
            topicAnswer,
            codexSessions,
            slackClient,
        )
        val workflow = SlackKakaoAnalysisWorkflow(
                slackClient = slackClient,
                topicAnalysis = topicAnalysis,
                reviewSessions = reviewSessions,
                maxFileSizeBytes = config.maxFileSizeBytes,
            )
        val confirmationHandlers = SlackConfirmationHandlers(topicAnalysis, reviewSessions)
        val listeners = buildList {
            add(SlackKakaoListeners(config, workflow, executor))
            add(SlackConfirmationListeners(config, confirmationHandlers, reviewSessions, slackClient, executor))
            conversationListeners?.let(::add)
        }
        return SlackSocketRuntime(
            config = config,
            listeners = listeners,
            executor = executor,
        )
    }

    private fun createConversationListeners(
        config: SlackConfig,
        topicAnswer: TopicAnswerUseCase,
        sessions: SlackCodexSessionStore,
        slackClient: SlackMessageClient,
    ): SlackListenerRegistrar? {
        val client = CodexConversationConfig.fromEnv()
            ?.let(CodexConversationClientFactory::create)
            ?: return disabled("configuration missing or invalid")
        if (!client.validateVersion()) return disabled("CODEX_VERSION_MISMATCH")

        val now = System.currentTimeMillis()
        sessions.failStaleProcessing(
            before = now - SlackConversationService.SESSION_IDLE_TIMEOUT_MILLIS,
            now = now,
        )
        return SlackConversationListeners(
            SlackConversationService(
                identities = config.identityDirectory,
                sessions = sessions,
                contextProvider = HouseholdContextProvider(topicAnswer),
                codex = client,
                slack = slackClient,
            ),
        )
    }

    private fun disabled(reason: String): SlackListenerRegistrar? {
        log.info("Slack conversation disabled: {}", reason)
        return null
    }
}

private val log = LoggerFactory.getLogger(SlackRuntimeFactory::class.java)
