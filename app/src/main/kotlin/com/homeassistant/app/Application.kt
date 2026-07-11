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
import com.homeassistant.domain.memory.QdrantVectorStore
import com.homeassistant.domain.kakao.KakaoImportService
import com.homeassistant.domain.topicanswer.TopicAnswerService
import com.homeassistant.domain.topicanswer.VectorTopicClaimSearchIndex
import com.homeassistant.nlp.backend.AiProvider
import com.homeassistant.nlp.backend.LmBackendFactory
import com.homeassistant.nlp.embedding.OllamaEmbeddingService
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
    val embeddingModelName = Env[AppConfig.ENV_VAR_EMBEDDING_MODEL]
        ?: AppConfig.DEFAULT_EMBEDDING_MODEL_NAME
    val embeddingBaseUrl = Env[AppConfig.ENV_VAR_OLLAMA_BASE_URL]
        ?: AppConfig.DEFAULT_OLLAMA_BASE_URL
    log.info("Ollama embedding model=$embeddingModelName baseUrl=$embeddingBaseUrl")
    val embeddingService = OllamaEmbeddingService(
        baseUrl = embeddingBaseUrl,
        model = embeddingModelName,
    )
    val topicClaimSearchIndex = VectorTopicClaimSearchIndex(
        embeddingService = embeddingService,
        vectorStore = QdrantVectorStore(
            baseUrl = Env[AppConfig.ENV_VAR_QDRANT_URL] ?: AppConfig.DEFAULT_QDRANT_URL,
            collection = Env[AppConfig.ENV_VAR_QDRANT_COLLECTION] ?: AppConfig.DEFAULT_QDRANT_COLLECTION,
        ),
    )
    val kakaoTopicAnalysis = KakaoMessageTopicAnalysisService(
        backend = llmBackend,
        importService = KakaoImportService(repositories.kakaoMessages),
        topicRepository = repositories.topicAnalysis,
        previewRepository = repositories.kakaoAnalysisPreviews,
        topicClaimSearchIndex = topicClaimSearchIndex,
        indexingOutbox = repositories.indexingOutbox,
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
