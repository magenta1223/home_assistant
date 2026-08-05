package com.homeassistant.adapter.inbound.slack

import com.homeassistant.application.slackconversation.handle.HandleSlackConversation
import com.homeassistant.application.slackconversation.handle.HouseholdContextProvider
import com.homeassistant.application.slackconversation.handle.ConversationTurnClient
import com.homeassistant.domain.slackconversation.SlackCodexSessionStore
import com.homeassistant.application.topicanswer.answer.TopicAnswerUseCase
import com.homeassistant.application.topicanalysis.TopicAnalysisUseCase
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors

object SlackRuntimeFactory {
    fun create(
        config: SlackConfig,
        topicAnalysis: TopicAnalysisUseCase,
        topicAnswer: TopicAnswerUseCase,
        codexSessions: SlackCodexSessionStore,
        conversationClient: ConversationTurnClient?,
    ): SlackRuntime {
        val slackClient = SlackClientFactory.create(config.botToken)
        val reviewSessions = SlackTopicReviewSessionStoreFactory.inMemory()
        val executor = Executors.newFixedThreadPool(2)
        val conversationListeners = createConversationListeners(
            config,
            topicAnswer,
            codexSessions,
            slackClient,
            conversationClient,
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
        conversationClient: ConversationTurnClient?,
    ): SlackListenerRegistrar? {
        val client = conversationClient ?: return disabled("configuration missing or invalid")

        val now = System.currentTimeMillis()
        sessions.failStaleProcessing(
            before = now - HandleSlackConversation.SESSION_IDLE_TIMEOUT_MILLIS,
            now = now,
        )
        return SlackConversationListeners(
            SlackConversationService(
                HandleSlackConversation(
                    identities = config.identityDirectory,
                    sessions = sessions,
                    contextProvider = HouseholdContextProvider(topicAnswer),
                    conversationClient = client,
                    answerPublisher = SlackConversationAnswerPublisher(slackClient),
                ),
            ),
        )
    }

    private fun disabled(reason: String): SlackListenerRegistrar? {
        log.info("Slack conversation disabled: {}", reason)
        return null
    }
}

private val log = LoggerFactory.getLogger(SlackRuntimeFactory::class.java)
