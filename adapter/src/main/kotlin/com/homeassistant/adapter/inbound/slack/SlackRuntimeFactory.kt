package com.homeassistant.adapter.inbound.slack

import com.homeassistant.application.slackconversation.handle.HandleSlackConversation
import com.homeassistant.application.slackconversation.handle.HouseholdContextProvider
import com.homeassistant.application.slackconversation.handle.ConversationTurnClient
import com.homeassistant.application.slackconversation.handle.SlackCodexSessionStore
import com.homeassistant.application.memory.search.SearchMemoriesUseCase
import com.homeassistant.application.topicanalysis.analyze.AnalyzeSourceUseCase
import com.homeassistant.application.topicanalysis.review.GetTopicAnalysisReviewUseCase
import com.homeassistant.application.topicanalysis.save.SaveAnalyzedTopicsUseCase
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors

object SlackRuntimeFactory {
    fun create(
        config: SlackConfig,
        analyzeSource: AnalyzeSourceUseCase,
        getTopicAnalysisReview: GetTopicAnalysisReviewUseCase,
        saveAnalyzedTopics: SaveAnalyzedTopicsUseCase,
        searchMemories: SearchMemoriesUseCase,
        codexSessions: SlackCodexSessionStore,
        conversationClient: ConversationTurnClient?,
    ): SlackRuntime {
        val slackClient = SlackClientFactory.create(config.botToken)
        val reviewContexts = SlackReviewContextStoreFactory.inMemory()
        val executor = Executors.newFixedThreadPool(2)
        val conversationListeners = createConversationListeners(
            config,
            searchMemories,
            codexSessions,
            slackClient,
            conversationClient,
        )
        val workflow = SlackKakaoAnalysisWorkflow(
                slackClient = slackClient,
                analyzeSource = analyzeSource,
                reviewContexts = reviewContexts,
                maxFileSizeBytes = config.maxFileSizeBytes,
            )
        val confirmationHandlers = SlackConfirmationHandlers(saveAnalyzedTopics, getTopicAnalysisReview, reviewContexts)
        val listeners = buildList {
            add(SlackKakaoListeners(config, workflow, executor))
            add(SlackConfirmationListeners(config, confirmationHandlers, reviewContexts, slackClient, executor))
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
        searchMemories: SearchMemoriesUseCase,
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
                    contextProvider = HouseholdContextProvider(searchMemories),
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
