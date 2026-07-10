package com.homeassistant.app

import com.homeassistant.app.routes.configureRoutes
import com.homeassistant.app.slack.InMemorySlackTopicReviewSessionStore
import com.homeassistant.app.slack.SlackConfig
import com.homeassistant.app.slack.SlackConfirmationHandlers
import com.homeassistant.app.slack.SlackKakaoAnalysisWorkflow
import com.homeassistant.app.slack.SlackSocketRuntime
import com.homeassistant.app.slack.SlackWebApiClient
import com.homeassistant.core.constants.AppConfig
import com.homeassistant.core.constants.Env
import com.homeassistant.core.utils.JsonSerializer
import com.homeassistant.domain.kakao.KakaoImportService
import com.homeassistant.domain.topicanswer.TopicAnswerService
import com.homeassistant.domain.topicanswer.UnavailableTopicClaimSearchIndex
import com.homeassistant.nlp.backend.AiProvider
import com.homeassistant.nlp.backend.LmBackendFactory
import com.homeassistant.nlp.topicanalysis.impl.KakaoMessageTopicAnalysisService
import com.homeassistant.repository.repo.RepositoryFactory
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.io.PrintStream

private val log = LoggerFactory.getLogger("Application")

fun main() {
    System.setOut(PrintStream(System.out, true, Charsets.UTF_8))
    System.setErr(PrintStream(System.err, true, Charsets.UTF_8))
    log.info("Starting server on port ${AppConfig.DEFAULT_PORT}")
    embeddedServer(Netty, port = AppConfig.DEFAULT_PORT, module = Application::module).start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json(JsonSerializer.json)
    }

    install(CallLogging) {
        level = Level.INFO
    }

    val dbPath = environment
        .config
        .propertyOrNull(AppConfig.CONFIG_KEY_DB_PATH)
        ?.getString()
        ?: AppConfig.DEFAULT_DB_PATH

    log.info("Database: $dbPath")

    val repositories = RepositoryFactory.create(dbPath)
    val llmBackend = LmBackendFactory.create(
        AiProvider.from(Env[AppConfig.ENV_VAR_AI_PROVIDER] ?: AppConfig.DEFAULT_AI_PROVIDER),
    )
    val topicClaimSearchIndex = UnavailableTopicClaimSearchIndex
    val kakaoTopicAnalysis = KakaoMessageTopicAnalysisService(
        backend = llmBackend,
        importService = KakaoImportService(repositories.kakaoMessages),
        topicRepository = repositories.topicAnalysis,
        previewRepository = repositories.kakaoAnalysisPreviews,
        topicClaimSearchIndex = topicClaimSearchIndex,
    )
    val topicAnswer = TopicAnswerService(repositories.topicAnalysis, topicClaimSearchIndex)
    val slackConfig = SlackConfig.fromEnv()
    if (slackConfig == null) {
        log.info("Slack Socket Mode disabled: SLACK_APP_TOKEN or SLACK_BOT_TOKEN is missing")
    } else {
        val slackClient = SlackWebApiClient(slackConfig.botToken)
        val reviewSessions = InMemorySlackTopicReviewSessionStore()
        val slackRuntime = SlackSocketRuntime(
            config = slackConfig,
            workflow = SlackKakaoAnalysisWorkflow(
                slackClient = slackClient,
                topicAnalysis = kakaoTopicAnalysis,
                reviewSessions = reviewSessions,
                maxFileSizeBytes = slackConfig.maxFileSizeBytes,
            ),
            confirmationHandlers = SlackConfirmationHandlers(kakaoTopicAnalysis, reviewSessions),
            reviewSessions = reviewSessions,
            slackClient = slackClient,
        )
        slackRuntime.startAsync()
        monitor.subscribe(ApplicationStopped) {
            slackRuntime.close()
        }
    }

    configureRoutes(kakaoTopicAnalysis, topicAnswer)
}
